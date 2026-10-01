package com.operator.mypack.pack;

import com.operator.mypack.config.Settings;
import com.operator.mypack.utils.FileUtils;
import com.operator.mypack.utils.HashUtils;
import com.operator.mypack.utils.NameUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The first stage of the install pipeline: finds every pack in the packs directory, extracts archives safely into a
 * cache (reusing an earlier extraction when the archive did not change), locates and parses {@code manifest.json}.
 * One broken or hostile source becomes a {@link Failure}; it never prevents the other packs from loading.
 *
 * <p>Blocking file I/O: call it from an async thread.</p>
 */
public final class PackScanner {

    public static final Set<String> ARCHIVE_EXTENSIONS = Set.of(".zip", ".mcpack", ".mcaddon", ".mypack");
    private static final String COMPLETE_MARKER = ".mypack-extracted";

    /** A source that was found, extracted and has a valid manifest. */
    public record Candidate(String source, boolean archive, String fingerprint, Path root, PackManifest manifest, Issues issues) {
    }

    /** A source that could not be used, with the reason shown to the administrator. */
    public record Failure(String source, String reason) {
    }

    /** Everything one scan produced. */
    public record Result(List<Candidate> candidates, List<Failure> failures) {
    }

    private final Path packsDirectory;
    private final Path cacheDirectory;
    private final Settings.Limits limits;
    private final Logger log;

    public PackScanner(Path packsDirectory, Path cacheDirectory, Settings.Limits limits, Logger log) {
        this.packsDirectory = packsDirectory;
        this.cacheDirectory = cacheDirectory;
        this.limits = limits;
        this.log = log;
    }

    public Result scan() throws IOException {
        Files.createDirectories(packsDirectory);
        Files.createDirectories(cacheDirectory);
        List<Candidate> candidates = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(packsDirectory)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                if (Files.isDirectory(path) || (Files.isRegularFile(path) && isArchive(name))) {
                    sources.add(path);
                } else {
                    log.fine("Ignoring non-pack file in the packs directory: " + name);
                }
            }
        }
        sources.sort(java.util.Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        for (Path source : sources) {
            String name = source.getFileName().toString();
            try {
                scanOne(source, name).ifPresentOrElse(candidates::add, () -> failures.add(new Failure(name, "no manifest.json found")));
            } catch (SafeZip.ZipSecurityException e) {
                failures.add(new Failure(name, "rejected archive: " + e.getMessage()));
                log.warning("Rejected archive " + name + ": " + e.getMessage());
            } catch (ManifestParser.ManifestException e) {
                failures.add(new Failure(name, "invalid manifest: " + e.getMessage()));
            } catch (IOException e) {
                failures.add(new Failure(name, "cannot be read: " + e.getMessage()));
            } catch (RuntimeException e) {
                failures.add(new Failure(name, "unexpected error: " + e));
                log.log(Level.WARNING, "Unexpected error while scanning " + name, e);
            }
        }
        return new Result(List.copyOf(candidates), List.copyOf(failures));
    }

    private Optional<Candidate> scanOne(Path source, String name) throws IOException, ManifestParser.ManifestException {
        boolean archive = Files.isRegularFile(source);
        String fingerprint;
        Path root;
        if (archive) {
            fingerprint = HashUtils.sha256Hex(source);
            root = extract(source, name, fingerprint);
        } else {
            fingerprint = fingerprintOfDirectory(source);
            root = source;
        }
        Optional<Path> manifestRoot = PackLocator.findRoot(root);
        if (manifestRoot.isEmpty()) {
            return Optional.empty();
        }
        Issues issues = new Issues();
        PackManifest manifest = ManifestParser.parse(Files.readAllBytes(manifestRoot.get().resolve("manifest.json")),
                NameUtils.stripExtension(name), issues);
        return Optional.of(new Candidate(name, archive, fingerprint, manifestRoot.get(), manifest, issues));
    }

    /** Extracts into {@code cache/<name>-<fingerprint prefix>}; an earlier complete extraction is reused. */
    private Path extract(Path archive, String name, String fingerprint) throws IOException {
        Path target = cacheDirectory.resolve(cacheName(name, fingerprint));
        if (Files.isRegularFile(target.resolve(COMPLETE_MARKER))) {
            return target;
        }
        FileUtils.deleteRecursively(target);
        Path temp = cacheDirectory.resolve(target.getFileName() + ".tmp");
        FileUtils.deleteRecursively(temp);
        try {
            SafeZip.extract(archive, temp, limits);
            Files.writeString(temp.resolve(COMPLETE_MARKER), fingerprint, StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            FileUtils.deleteRecursively(temp);
            throw e;
        }
        return target;
    }

    /** Removes cache folders that no loaded archive uses any more. */
    public void pruneCache(Set<String> keepFolderNames) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDirectory, Files::isDirectory)) {
            for (Path dir : stream) {
                if (!keepFolderNames.contains(dir.getFileName().toString())) {
                    FileUtils.deleteRecursively(dir);
                }
            }
        } catch (IOException e) {
            log.fine("Could not prune the extraction cache: " + e.getMessage());
        }
    }

    public static String cacheName(String sourceName, String fingerprint) {
        return NameUtils.slug(sourceName, "pack") + "-" + fingerprint.substring(0, 12);
    }

    public static boolean isArchive(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : ARCHIVE_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** Hash over every file's relative path, size and modification time: cheap and sensitive to any edit. */
    public static String fingerprintOfDirectory(Path root) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String relative : FileUtils.listFiles(root)) {
            Path file = root.resolve(relative);
            FileTime time = Files.getLastModifiedTime(file);
            sb.append(relative).append('|').append(Files.size(file)).append('|').append(time.toMillis()).append('\n');
        }
        return HashUtils.sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
