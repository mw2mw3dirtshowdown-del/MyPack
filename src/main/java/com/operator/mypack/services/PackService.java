package com.operator.mypack.services;

import com.operator.mypack.api.event.PacksReloadedEvent;
import com.operator.mypack.config.ConfigManager;
import com.operator.mypack.config.Settings;
import com.operator.mypack.database.AuditRepository;
import com.operator.mypack.database.PackRepository;
import com.operator.mypack.pack.ContentRegistry;
import com.operator.mypack.pack.DependencyResolver;
import com.operator.mypack.pack.InstalledPack;
import com.operator.mypack.pack.Issues;
import com.operator.mypack.pack.PackContent;
import com.operator.mypack.pack.PackFiles;
import com.operator.mypack.pack.PackLoader;
import com.operator.mypack.pack.PackManifest;
import com.operator.mypack.pack.PackScanner;
import com.operator.mypack.pack.RegistryValidator;
import com.operator.mypack.tasks.Schedulers;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Owns the pack lifecycle: discover, install, enable/disable, uninstall and reload.
 *
 * <p>The database ({@code packs} table) is the source of truth for which packs are installed and enabled; the packs
 * directory is the source of truth for their content. Every operation ends in one blocking {@code sync} that runs on an
 * async thread (file I/O, JSON parsing, hashing) and then publishes the result on the global region thread (registry
 * swap, recipes, listeners). Operations are serialised, so two overlapping reloads can never publish stale state.</p>
 *
 * <p>Pipeline per pack: manifest &rarr; safe extraction ({@code SafeZip}) &rarr; remove the old namespace from the
 * registry &rarr; load items/models/mobs/sounds/loot/recipes &rarr; rebuild the resource pack &rarr; push it. A bad file
 * is reported and skipped, never a crash.</p>
 */
public final class PackService {

    public enum State {
        LOADED, DISABLED, REJECTED, INVALID, MISSING, NOT_INSTALLED
    }

    /** What is known about one source in the packs directory (or one database row whose source is gone). */
    public record PackStatus(String source, String name, String namespace, String version, State state, String detail,
                             InstalledPack pack) {
    }

    /** Result of a sync. */
    public record ReloadReport(List<PackStatus> statuses, int loaded, int contentCount,
                               ResourcePackService.BuildReport resourcePack) {
    }

    private static final Pattern SAFE_SOURCE = Pattern.compile("[A-Za-z0-9 ._()\\-]{1,200}");
    private static final long SWAP_TIMEOUT_SECONDS = 60L;
    private static final long BUILD_TIMEOUT_SECONDS = 180L;

    private final ConfigManager config;
    private final Schedulers schedulers;
    private final ContentRegistry registry;
    private final PackRepository packRepository;
    private final AuditRepository auditRepository;
    private final RecipeService recipes;
    private final ResourcePackService resourcePack;
    private final Path dataFolder;
    private final Logger log;
    private final List<ContentListener> listeners = new CopyOnWriteArrayList<>();
    private final Object operationLock = new Object();
    private volatile List<InstalledPack> loaded = List.of();
    private volatile List<PackStatus> statuses = List.of();

    public PackService(ConfigManager config, Schedulers schedulers, ContentRegistry registry, PackRepository packRepository,
                       AuditRepository auditRepository, RecipeService recipes, ResourcePackService resourcePack,
                       Path dataFolder, Logger log) {
        this.config = config;
        this.schedulers = schedulers;
        this.registry = registry;
        this.packRepository = packRepository;
        this.auditRepository = auditRepository;
        this.recipes = recipes;
        this.resourcePack = resourcePack;
        this.dataFolder = dataFolder;
        this.log = log;
    }

    public void addListener(ContentListener listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ queries

    public List<InstalledPack> loadedPacks() {
        return loaded;
    }

    public List<PackStatus> statuses() {
        return statuses;
    }

    /** Finds a status by source file name, pack name, namespace or pack uuid (case-insensitive). */
    public Optional<PackStatus> find(String reference) {
        String ref = reference.trim().toLowerCase(Locale.ROOT);
        for (PackStatus status : statuses) {
            if (status.source().toLowerCase(Locale.ROOT).equals(ref)
                    || status.name().toLowerCase(Locale.ROOT).equals(ref)
                    || status.namespace().toLowerCase(Locale.ROOT).equals(ref)
                    || (status.pack() != null && status.pack().manifest().uuid().toString().equals(ref))) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }

    public Path packsDirectory() {
        return dataFolder.resolve(config.settings().packs().directory());
    }

    // ------------------------------------------------------------------ operations

    public CompletableFuture<ReloadReport> reload(String actor) {
        return schedulers.supplyAsync(() -> {
            synchronized (operationLock) {
                return syncBlocking(actor, Set.of());
            }
        });
    }

    /** Installs (and enables) the pack found at {@code packs/<source>} even when auto-install is off. */
    public CompletableFuture<ReloadReport> install(String source, String actor) {
        if (!SAFE_SOURCE.matcher(source).matches() || source.startsWith(".")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("not a valid pack file name"));
        }
        return schedulers.supplyAsync(() -> {
            synchronized (operationLock) {
                if (!Files.exists(packsDirectory().resolve(source))) {
                    throw new IllegalArgumentException("no such file or folder in the packs directory: " + source);
                }
                return syncBlocking(actor, Set.of(source));
            }
        });
    }

    /** Enables or disables a pack; returns {@code false} when the reference matches nothing. */
    public CompletableFuture<Boolean> setEnabled(String reference, boolean enabled, String actor) {
        return schedulers.supplyAsync(() -> {
            synchronized (operationLock) {
                Optional<PackStatus> status = find(reference);
                if (status.isEmpty()) {
                    return false;
                }
                PackStatus s = status.get();
                if (s.state() == State.NOT_INSTALLED) {
                    if (enabled) {
                        syncBlocking(actor, Set.of(s.source()));
                        return true;
                    }
                    return false;
                }
                try {
                    Optional<PackRepositoryRecord> record = findRecord(s.source());
                    if (record.isPresent()) {
                        packRepository.setEnabled(record.get().uuid(), enabled, System.currentTimeMillis());
                        audit(record.get().uuid(), enabled ? "enable" : "disable", actor, s.source());
                    }
                } catch (SQLException e) {
                    log.log(Level.WARNING, "Could not store the enabled state of " + s.source(), e);
                }
                syncBlocking(actor, Set.of());
                return true;
            }
        });
    }

    /**
     * Removes a pack: the source is moved to {@code trash/} (recoverable), the database row is deleted and the content
     * is unregistered. Already spawned mobs and placed furniture of the pack are cleaned up by their services.
     */
    public CompletableFuture<Boolean> uninstall(String reference, String actor) {
        return schedulers.supplyAsync(() -> {
            synchronized (operationLock) {
                Optional<PackStatus> status = find(reference);
                if (status.isEmpty()) {
                    return false;
                }
                PackStatus s = status.get();
                try {
                    Optional<PackRepositoryRecord> record = findRecord(s.source());
                    if (record.isPresent()) {
                        packRepository.delete(record.get().uuid());
                        audit(record.get().uuid(), "uninstall", actor, s.source());
                    }
                    Path source = packsDirectory().resolve(s.source());
                    if (Files.exists(source)) {
                        Path trash = Files.createDirectories(dataFolder.resolve("trash"));
                        Files.move(source, trash.resolve(s.source() + "." + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (SQLException | IOException e) {
                    log.log(Level.WARNING, "Could not uninstall " + s.source(), e);
                    throw new IllegalStateException("could not uninstall " + s.source() + ": " + e.getMessage(), e);
                }
                syncBlocking(actor, Set.of());
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ the sync engine

    private ReloadReport syncBlocking(String actor, Set<String> forceInstall) {
        Settings settings = config.settings();
        List<PackStatus> result = new ArrayList<>();
        PackScanner scanner = new PackScanner(packsDirectory(), dataFolder.resolve("cache").resolve("packs"),
                settings.packs().limits(), log);
        PackScanner.Result scan;
        try {
            scan = scanner.scan();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the packs directory: " + e.getMessage(), e);
        }
        for (PackScanner.Failure failure : scan.failures()) {
            result.add(new PackStatus(failure.source(), failure.source(), "-", "-", State.INVALID, failure.reason(), null));
            log.warning("Pack " + failure.source() + " was skipped: " + failure.reason());
        }

        Map<String, PackRepositoryRecord> records = loadRecords();
        Set<String> seenSources = new HashSet<>();
        Set<UUID> seenUuids = new HashSet<>();
        List<PackScanner.Candidate> enabled = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (PackScanner.Candidate candidate : scan.candidates()) {
            seenSources.add(candidate.source());
            PackManifest manifest = candidate.manifest();
            if (!seenUuids.add(manifest.uuid())) {
                result.add(status(candidate, State.REJECTED, "another pack in the directory has the same uuid"));
                continue;
            }
            PackRepositoryRecord record = records.get(candidate.source());
            boolean forced = forceInstall.contains(candidate.source());
            boolean isEnabled;
            if (record == null) {
                if (!settings.packs().autoInstall() && !forced) {
                    result.add(status(candidate, State.NOT_INSTALLED, "not installed - run /mypack install " + candidate.source()));
                    continue;
                }
                isEnabled = forced || settings.packs().autoEnable();
                store(candidate, isEnabled, now, now);
                audit(manifest.uuid(), "install", actor, candidate.source());
                log.info("Installed pack '" + manifest.name() + "' (" + candidate.source() + ")" + (isEnabled ? "" : " [disabled]"));
            } else {
                isEnabled = record.enabled() || forced;
                boolean changed = !record.uuid().equals(manifest.uuid()) || !record.fingerprint().equals(candidate.fingerprint())
                        || !record.version().equals(manifest.version().toString()) || !record.namespace().equals(manifest.namespace())
                        || (forced && !record.enabled());
                if (changed) {
                    if (!record.uuid().equals(manifest.uuid())) {
                        deleteRecord(record.uuid());
                    }
                    store(candidate, isEnabled, record.installedAt(), now);
                    audit(manifest.uuid(), "update", actor, candidate.source());
                    log.info("Pack '" + manifest.name() + "' changed on disk and will be reloaded");
                }
            }
            if (!isEnabled) {
                result.add(status(candidate, State.DISABLED, "disabled - /mypack enable " + manifest.namespace()));
                continue;
            }
            enabled.add(candidate);
        }
        for (PackRepositoryRecord record : records.values()) {
            if (!seenSources.contains(record.sourceFile())) {
                result.add(new PackStatus(record.sourceFile(), record.name(), record.namespace(), record.version(), State.MISSING,
                        "the file or folder is gone from the packs directory", null));
            }
        }

        // dependency order, then load content
        Map<UUID, PackScanner.Candidate> byUuid = new HashMap<>();
        List<PackManifest> manifests = new ArrayList<>();
        for (PackScanner.Candidate candidate : enabled) {
            byUuid.put(candidate.manifest().uuid(), candidate);
            manifests.add(candidate.manifest());
        }
        DependencyResolver.Result resolved = DependencyResolver.resolve(manifests);
        for (DependencyResolver.Rejection rejection : resolved.rejected()) {
            PackScanner.Candidate candidate = byUuid.get(rejection.manifest().uuid());
            result.add(status(candidate, State.REJECTED, rejection.reason()));
            log.warning("Pack '" + rejection.manifest().name() + "' was rejected: " + rejection.reason());
        }

        List<InstalledPack> packs = new ArrayList<>();
        for (PackManifest manifest : resolved.ordered()) {
            PackScanner.Candidate candidate = byUuid.get(manifest.uuid());
            Issues issues = new Issues();
            issues.addAll(candidate.issues().all());
            PackFiles files = new PackFiles(candidate.root());
            PackContent content = PackLoader.load(manifest, files, issues);
            packs.add(new InstalledPack(manifest, candidate.source(), files, content, candidate.fingerprint(), List.copyOf(issues.all())));
        }

        // cross-pack reference checks on a scratch registry, then attach the findings to their packs
        ContentRegistry scratch = new ContentRegistry();
        scratch.replaceAll(packs);
        ContentRegistry.Snapshot snapshot = scratch.snapshot();
        Map<String, Issues> crossIssues = RegistryValidator.validate(snapshot);
        List<InstalledPack> finalPacks = new ArrayList<>();
        for (InstalledPack pack : packs) {
            Issues extra = crossIssues.get(pack.namespace());
            if (extra == null) {
                finalPacks.add(pack);
                continue;
            }
            List<Issues.Issue> combined = new ArrayList<>(pack.issues());
            combined.addAll(extra.all());
            finalPacks.add(new InstalledPack(pack.manifest(), pack.sourceFile(), pack.files(), pack.content(), pack.fingerprint(),
                    List.copyOf(combined)));
        }
        for (InstalledPack pack : finalPacks) {
            long warnings = pack.issues().stream().filter(i -> i.level() == Issues.Level.WARN).count();
            long errors = pack.issues().stream().filter(i -> i.level() == Issues.Level.ERROR).count();
            result.add(new PackStatus(pack.sourceFile(), pack.manifest().name(), pack.namespace(), pack.manifest().version().toString(),
                    State.LOADED, errors + " error(s), " + warnings + " warning(s)", pack));
            for (Issues.Issue issue : pack.issues()) {
                log.warning("[" + pack.namespace() + "] " + issue);
            }
        }

        publish(finalPacks, snapshot);

        Set<String> keep = new HashSet<>();
        for (PackScanner.Candidate candidate : scan.candidates()) {
            if (candidate.archive()) {
                keep.add(PackScanner.cacheName(candidate.source(), candidate.fingerprint()));
            }
        }
        scanner.pruneCache(keep);

        ResourcePackService.BuildReport build = waitFor(resourcePack.rebuild(finalPacks, snapshot), BUILD_TIMEOUT_SECONDS, "resource pack build");
        audit(null, "reload", actor, finalPacks.size() + " pack(s) loaded");

        result.sort(java.util.Comparator.comparing((PackStatus s) -> s.source().toLowerCase(Locale.ROOT)));
        this.loaded = List.copyOf(finalPacks);
        this.statuses = List.copyOf(result);
        log.info("Packs: " + finalPacks.size() + " loaded, " + (result.size() - finalPacks.size()) + " not loaded, "
                + snapshot.totalCount() + " definitions (" + snapshot.items().size() + " items, " + snapshot.recipes().size()
                + " recipes, " + snapshot.mobs().size() + " mobs, " + snapshot.furniture().size() + " furniture, "
                + snapshot.lootTables().size() + " loot tables, " + snapshot.sounds().size() + " sounds)");
        return new ReloadReport(List.copyOf(result), finalPacks.size(), snapshot.totalCount(), build);
    }

    /** Swaps the live registry, syncs recipes and notifies listeners on the global region thread. */
    private void publish(List<InstalledPack> packs, ContentRegistry.Snapshot snapshot) {
        List<String> namespaces = packs.stream().map(InstalledPack::namespace).toList();
        CompletableFuture<Void> swapped = schedulers.supplyGlobal(() -> {
            registry.replaceAll(packs);
            recipes.sync(snapshot);
            for (ContentListener listener : listeners) {
                try {
                    listener.onContentReplaced(snapshot);
                } catch (RuntimeException e) {
                    log.log(Level.SEVERE, "A content listener failed", e);
                }
            }
            Bukkit.getPluginManager().callEvent(new PacksReloadedEvent(namespaces, snapshot.totalCount()));
            return null;
        });
        waitFor(swapped, SWAP_TIMEOUT_SECONDS, "registry swap");
    }

    private <T> T waitFor(CompletableFuture<T> future, long seconds, String what) {
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(what + " was interrupted", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException(what + " failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ persistence helpers (degrade gracefully)

    /** Row of the pack registry; a thin local view so the sync code does not depend on the repository record type. */
    private record PackRepositoryRecord(UUID uuid, String namespace, String name, String version, String sourceFile,
                                        String fingerprint, boolean enabled, long installedAt) {
    }

    private Map<String, PackRepositoryRecord> loadRecords() {
        Map<String, PackRepositoryRecord> map = new HashMap<>();
        try {
            for (PackRepository.PackRecord r : packRepository.findAll()) {
                map.put(r.sourceFile(), new PackRepositoryRecord(r.uuid(), r.namespace(), r.name(), r.version(), r.sourceFile(),
                        r.fingerprint(), r.enabled(), r.installedAt()));
            }
        } catch (SQLException | RuntimeException e) {
            log.log(Level.WARNING, "Could not read the pack registry from the database; treating every pack as new", e);
        }
        return map;
    }

    private Optional<PackRepositoryRecord> findRecord(String source) throws SQLException {
        return packRepository.findBySource(source).map(r -> new PackRepositoryRecord(r.uuid(), r.namespace(), r.name(),
                r.version(), r.sourceFile(), r.fingerprint(), r.enabled(), r.installedAt()));
    }

    private void store(PackScanner.Candidate candidate, boolean enabled, long installedAt, long now) {
        PackManifest m = candidate.manifest();
        try {
            packRepository.upsert(new PackRepository.PackRecord(m.uuid(), m.namespace(), m.name(), m.version().toString(),
                    candidate.source(), candidate.fingerprint(), enabled, installedAt, now));
        } catch (SQLException | RuntimeException e) {
            log.log(Level.WARNING, "Could not store pack '" + m.name() + "' in the database", e);
        }
    }

    private void deleteRecord(UUID uuid) {
        try {
            packRepository.delete(uuid);
        } catch (SQLException | RuntimeException e) {
            log.log(Level.WARNING, "Could not delete an outdated pack record", e);
        }
    }

    private void audit(UUID pack, String action, String actor, String detail) {
        try {
            auditRepository.log(pack, action, actor == null ? "console" : actor, detail);
        } catch (SQLException | RuntimeException e) {
            log.log(Level.FINE, "Could not write the audit log", e);
        }
    }

    private static PackStatus status(PackScanner.Candidate candidate, State state, String detail) {
        PackManifest m = candidate.manifest();
        return new PackStatus(candidate.source(), m.name(), m.namespace(), m.version().toString(), state, detail, null);
    }
}
