package com.operator.mypack.services;

import com.operator.mypack.config.ConfigManager;
import com.operator.mypack.config.LangManager;
import com.operator.mypack.config.Settings;
import com.operator.mypack.database.PlayerStatusRepository;
import com.operator.mypack.pack.ContentRegistry;
import com.operator.mypack.pack.InstalledPack;
import com.operator.mypack.resourcepack.ResourcePackBuilder;
import com.operator.mypack.resourcepack.ResourcePackServer;
import com.operator.mypack.tasks.Schedulers;
import com.operator.mypack.utils.HashUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds the merged resource pack, hosts it and pushes it to players.
 *
 * <ul>
 *   <li>Building and hashing happen on an async thread; the result is published atomically.</li>
 *   <li>The pack is pushed with {@code player.setResourcePack(UUID, url, sha1, prompt, force)} on join and after every
 *       rebuild that changed it. The UUID is stable, so a new version <em>replaces</em> the old pack on the client
 *       instead of stacking next to it. {@code server.properties} is never touched.</li>
 *   <li>The URL is built from {@code resource-pack.http.public-host}; nothing is hard-coded to localhost.</li>
 * </ul>
 */
public final class ResourcePackService {

    /** A finished build: the archive, its SHA-1 and where clients download it ({@code url} may be {@code null}). */
    public record Published(String sha1, byte[] zip, String url, long builtAtMillis, int fileCount, List<String> warnings) {
    }

    /** Outcome of {@link #rebuild}. */
    public record BuildReport(boolean success, Published published, boolean changed, String error) {
    }

    private final ConfigManager config;
    private final LangManager lang;
    private final Schedulers schedulers;
    private final PlayerStatusRepository statusRepository;
    private final ResourcePackServer server;
    private final Path dataFolder;
    private final Logger log;
    private final AtomicReference<Published> published = new AtomicReference<>();
    private volatile boolean serverFailed;

    public ResourcePackService(ConfigManager config, LangManager lang, Schedulers schedulers,
                               PlayerStatusRepository statusRepository, Path dataFolder, Logger log) {
        this.config = config;
        this.lang = lang;
        this.schedulers = schedulers;
        this.statusRepository = statusRepository;
        this.dataFolder = dataFolder;
        this.log = log;
        this.server = new ResourcePackServer(log);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts the embedded HTTP server (when enabled and no external URL is configured). */
    public void startServer() {
        Settings.ResourcePack rp = config.settings().resourcePack();
        if (!rp.enabled()) {
            log.info("Resource pack support is disabled in config.yml");
            return;
        }
        if (!rp.externalUrl().isBlank()) {
            log.info("Resource pack is hosted externally at " + rp.externalUrl() + " (embedded HTTP server not started)");
            return;
        }
        if (!rp.http().enabled()) {
            log.warning("resource-pack.http.enabled is false and no external-url is set: the pack is built but never sent to players");
            return;
        }
        try {
            server.start(rp.http().bindAddress(), rp.http().port());
            serverFailed = false;
        } catch (IOException e) {
            serverFailed = true;
            log.severe("Could not start the resource pack HTTP server on " + rp.http().bindAddress() + ":" + rp.http().port()
                    + " (" + e.getMessage() + "). Change resource-pack.http.port or free the port.");
        }
        if (rp.http().publicHost().isBlank()) {
            log.warning("resource-pack.http.public-host is empty: using " + ResourcePackServer.detectLocalAddress()
                    + ", which only works for players on the same network. Set it to your server's public address.");
        }
    }

    /** Stops the HTTP server; called from {@code onDisable}. */
    public void stop() {
        server.stop();
    }

    public boolean isServerRunning() {
        return server.isRunning();
    }

    public boolean hasServerFailed() {
        return serverFailed;
    }

    // ------------------------------------------------------------------ building

    /**
     * Rebuilds the pack from {@code packs} on an async thread. When the content changed the new pack is pushed to every
     * online player.
     */
    public CompletableFuture<BuildReport> rebuild(List<InstalledPack> packs, ContentRegistry.Snapshot snapshot) {
        Settings.ResourcePack rp = config.settings().resourcePack();
        if (!rp.enabled()) {
            return CompletableFuture.completedFuture(new BuildReport(true, published.get(), false, null));
        }
        return schedulers.supplyAsync(() -> build(packs, snapshot, rp)).thenApply(report -> {
            if (report.success() && report.changed()) {
                pushAll();
            }
            return report;
        });
    }

    private BuildReport build(List<InstalledPack> packs, ContentRegistry.Snapshot snapshot, Settings.ResourcePack rp) {
        try {
            byte[] icon = readIcon();
            ResourcePackBuilder.Result result = ResourcePackBuilder.build(packs, snapshot,
                    new ResourcePackBuilder.Options(rp.packFormat(), rp.supportedMin(), rp.supportedMax(), rp.description(), icon));
            for (String warning : result.warnings()) {
                log.warning("[resource pack] " + warning);
            }

            String url = null;
            if (!rp.externalUrl().isBlank()) {
                url = rp.externalUrl();
            } else if (server.isRunning()) {
                server.publish(result.sha1Hex(), result.zip());
                url = ResourcePackServer.publicUrl(rp.http(), server.port(), result.sha1Hex());
            }
            writeCache(result);

            Published previous = published.get();
            boolean changed = previous == null || !previous.sha1().equals(result.sha1Hex())
                    || !java.util.Objects.equals(previous.url(), url);
            Published next = new Published(result.sha1Hex(), result.zip(), url, System.currentTimeMillis(),
                    result.fileCount(), result.warnings());
            published.set(next);
            log.info("Resource pack built: " + result.fileCount() + " files, " + (result.zip().length / 1024) + " KiB, sha1 "
                    + result.sha1Hex() + (url == null ? " (not hosted)" : " -> " + url));
            if (!rp.externalUrl().isBlank()) {
                log.info("External hosting: upload plugins/MyPack/cache/resourcepack.zip to " + rp.externalUrl()
                        + " (its SHA-1 must be " + result.sha1Hex() + ").");
            }
            return new BuildReport(true, next, changed, null);
        } catch (ResourcePackBuilder.BuildException e) {
            log.severe("Resource pack build failed: " + e.getMessage());
            return new BuildReport(false, published.get(), false, e.getMessage());
        } catch (RuntimeException | IOException e) {
            log.log(Level.SEVERE, "Resource pack build crashed", e);
            return new BuildReport(false, published.get(), false, String.valueOf(e.getMessage()));
        }
    }

    private byte[] readIcon() throws IOException {
        Path icon = dataFolder.resolve("pack.png");
        return Files.isRegularFile(icon) && Files.size(icon) <= 1024 * 1024 ? Files.readAllBytes(icon) : null;
    }

    private void writeCache(ResourcePackBuilder.Result result) {
        try {
            Path cache = dataFolder.resolve("cache");
            Files.createDirectories(cache);
            Files.write(cache.resolve("resourcepack.zip"), result.zip());
            Files.writeString(cache.resolve("resourcepack.sha1"), result.sha1Hex() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("Could not write the resource pack cache file: " + e.getMessage());
        }
    }

    public Published published() {
        return published.get();
    }

    // ------------------------------------------------------------------ pushing

    /** Stable id of the pack on clients; a new version with the same id replaces the previous one. */
    public UUID packId() {
        String raw = config.settings().resourcePack().packId();
        if (raw.isBlank()) {
            return UUID.nameUUIDFromBytes("mypack:default".getBytes(StandardCharsets.UTF_8));
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(("mypack:" + raw).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Sends the current pack to {@code player}. Must be called on the player's thread. */
    public boolean push(Player player) {
        Settings.ResourcePack rp = config.settings().resourcePack();
        Published current = published.get();
        if (!rp.enabled() || current == null || current.url() == null) {
            return false;
        }
        player.setResourcePack(packId(), current.url(), HashUtils.fromHex(current.sha1()),
                lang.get("resourcepack.prompt"), rp.force());
        return true;
    }

    /** Schedules {@link #push} for every online player on that player's own thread. */
    public int pushAll() {
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            schedulers.entity(player, () -> push(player), null);
            count++;
        }
        return count;
    }

    // ------------------------------------------------------------------ client feedback

    /** Records the client's answer and tells the player when something went wrong. */
    public void onStatus(Player player, UUID id, PlayerResourcePackStatusEvent.Status status) {
        if (!id.equals(packId())) {
            return;
        }
        Published current = published.get();
        String hash = current == null ? "unknown" : current.sha1();
        schedulers.async(() -> {
            try {
                statusRepository.upsert(player.getUniqueId(), hash, status.name());
            } catch (SQLException | RuntimeException e) {
                log.log(Level.FINE, "Could not store resource pack status", e);
            }
        });
        switch (status) {
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> {
                log.warning(player.getName() + " could not load the resource pack (" + status + "). Check that "
                        + (current == null ? "the pack URL" : current.url()) + " is reachable from the internet.");
                lang.send(player, "resourcepack.failed");
            }
            case DECLINED -> lang.send(player, config.settings().resourcePack().force()
                    ? "resourcepack.declined-required" : "resourcepack.declined");
            default -> {
                // loaded / accepted / downloaded: nothing to tell the player
            }
        }
    }

    /** Number of players per client status for the current pack hash. */
    public Map<String, Integer> statusCounts() throws SQLException {
        Published current = published.get();
        return current == null ? Map.of() : statusRepository.countByStatus(current.sha1());
    }
}
