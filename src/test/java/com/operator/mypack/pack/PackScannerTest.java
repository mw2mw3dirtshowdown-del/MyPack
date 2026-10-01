package com.operator.mypack.pack;

import com.operator.mypack.config.Settings;
import com.operator.mypack.testsupport.PackFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackScannerTest {

    private static final Settings.Limits LIMITS = new Settings.Limits(50_000_000L, 100_000_000L, 20_000_000L, 5_000);

    @TempDir
    Path tmp;

    Path packs;
    Path cache;
    PackScanner scanner;

    @BeforeEach
    void setUp() throws IOException {
        packs = Files.createDirectories(tmp.resolve("packs"));
        cache = tmp.resolve("cache");
        scanner = new PackScanner(packs, cache, LIMITS, Logger.getAnonymousLogger());
    }

    /** Zips the directory {@code source} into {@code target}; with {@code topFolder} the files sit in one folder. */
    private static void zipDirectory(Path source, Path target, String topFolder) throws IOException {
        try (OutputStream out = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(out);
             Stream<Path> files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).collect(Collectors.toList())) {
                String name = source.relativize(file).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(topFolder == null ? name : topFolder + "/" + name));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
        }
    }

    @Test
    @DisplayName("finds folder packs and archives (.zip/.mcpack/.mcaddon/.mypack) and ignores everything else")
    void discoversSources() throws Exception {
        PackFixture.create(packs.resolve("folder-pack"));
        Path staging = tmp.resolve("staging");
        PackFixture.create(staging);
        zipDirectory(staging, packs.resolve("zipped.zip"), null);
        zipDirectory(staging, packs.resolve("bedrock.mcpack"), null);
        Files.writeString(packs.resolve("notes.txt"), "ignore me");
        Files.writeString(packs.resolve(".hidden.zip"), "ignore me too");
        Files.createDirectories(packs.resolve(".git"));

        PackScanner.Result result = scanner.scan();
        List<String> names = result.candidates().stream().map(PackScanner.Candidate::source).toList();
        assertEquals(List.of("bedrock.mcpack", "folder-pack", "zipped.zip"), names, "sorted, only real packs");
        assertTrue(result.failures().isEmpty(), result.failures().toString());
        PackScanner.Candidate folder = result.candidates().get(1);
        assertFalse(folder.archive());
        assertEquals("aether", folder.manifest().namespace());
        assertTrue(result.candidates().get(0).archive());
    }

    @Test
    @DisplayName("an archive whose files sit in one top level folder is unwrapped")
    void unwrapsTopLevelFolder() throws Exception {
        Path staging = tmp.resolve("staging");
        PackFixture.create(staging);
        zipDirectory(staging, packs.resolve("wrapped.zip"), "Aether Arsenal");
        PackScanner.Candidate candidate = scanner.scan().candidates().get(0);
        assertEquals("manifest.json", candidate.root().resolve("manifest.json").getFileName().toString());
        assertTrue(Files.isRegularFile(candidate.root().resolve("items/aether_blaster.json")));
        assertEquals("Aether Arsenal", candidate.root().getFileName().toString());
    }

    @Test
    @DisplayName("extraction is cached by content hash and re-done when the archive changes")
    void extractionCaching() throws Exception {
        Path staging = tmp.resolve("staging");
        PackFixture.create(staging);
        Path zip = packs.resolve("cached.zip");
        zipDirectory(staging, zip, null);

        PackScanner.Candidate first = scanner.scan().candidates().get(0);
        Path marker = first.root().resolve("items/aether_blaster.json");
        Files.writeString(marker, "LOCAL EDIT"); // would be overwritten by a fresh extraction
        PackScanner.Candidate second = scanner.scan().candidates().get(0);
        assertEquals(first.root(), second.root());
        assertEquals("LOCAL EDIT", Files.readString(marker), "unchanged archive: the cache is reused, not re-extracted");

        PackFixture.write(staging, "items/another.json", "{\"minecraft:item\":{\"description\":{\"identifier\":\"aether:another\"}}}");
        zipDirectory(staging, zip, null);
        PackScanner.Candidate third = scanner.scan().candidates().get(0);
        assertNotEquals(first.fingerprint(), third.fingerprint());
        assertNotEquals(first.root(), third.root());
        assertTrue(Files.isRegularFile(third.root().resolve("items/another.json")));

        scanner.pruneCache(Set.of(third.root().getFileName().toString()));
        assertFalse(Files.exists(first.root()), "stale extraction is pruned");
        assertTrue(Files.exists(third.root()));
    }

    @Test
    @DisplayName("a hostile archive fails alone: zip slip is rejected, other packs still load, nothing escapes the cache")
    void hostileArchiveIsIsolated() throws Exception {
        PackFixture.create(packs.resolve("good-pack"));
        try (OutputStream out = Files.newOutputStream(packs.resolve("evil.zip")); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("../../escaped.txt"));
            zip.write("pwned".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        PackScanner.Result result = scanner.scan();
        assertEquals(List.of("good-pack"), result.candidates().stream().map(PackScanner.Candidate::source).toList());
        assertEquals(1, result.failures().size());
        assertEquals("evil.zip", result.failures().get(0).source());
        assertTrue(result.failures().get(0).reason().contains("traversal"), result.failures().get(0).reason());
        assertFalse(Files.exists(tmp.resolve("escaped.txt")));
        assertFalse(Files.exists(cache.getParent().resolve("escaped.txt")));
        try (Stream<Path> leftovers = Files.list(cache)) {
            assertTrue(leftovers.noneMatch(p -> p.toString().endsWith(".tmp")), "no half extracted directories are left behind");
        }
    }

    @Test
    @DisplayName("invalid or missing manifests are reported per source")
    void manifestFailures() throws Exception {
        PackFixture.write(packs, "no-manifest/readme.txt", "hello");
        PackFixture.write(packs, "bad-manifest/manifest.json", "{ \"header\": {} }");
        PackFixture.create(packs.resolve("fine"));
        PackScanner.Result result = scanner.scan();
        assertEquals(List.of("fine"), result.candidates().stream().map(PackScanner.Candidate::source).toList());
        assertEquals(2, result.failures().size());
        assertTrue(result.failures().stream().anyMatch(f -> f.source().equals("no-manifest") && f.reason().contains("no manifest.json")));
        assertTrue(result.failures().stream().anyMatch(f -> f.source().equals("bad-manifest") && f.reason().contains("invalid manifest")));
    }

    @Test
    @DisplayName("oversized archives are refused before extraction")
    void sizeLimit() throws Exception {
        Path staging = tmp.resolve("staging");
        PackFixture.create(staging);
        zipDirectory(staging, packs.resolve("big.zip"), null);
        PackScanner tight = new PackScanner(packs, cache, new Settings.Limits(100L, 100_000_000L, 20_000_000L, 5_000),
                Logger.getAnonymousLogger());
        PackScanner.Result result = tight.scan();
        assertTrue(result.candidates().isEmpty());
        assertTrue(result.failures().get(0).reason().contains("limit"), result.failures().get(0).reason());
    }

    @Test
    @DisplayName("a folder fingerprint changes when any file changes")
    void directoryFingerprint() throws Exception {
        Path dir = packs.resolve("fp");
        PackFixture.create(dir);
        String before = PackScanner.fingerprintOfDirectory(dir);
        assertEquals(before, PackScanner.fingerprintOfDirectory(dir), "stable while nothing changes");
        PackFixture.write(dir, "items/core_shard.json", "{\"minecraft:item\":{\"description\":{\"identifier\":\"aether:core_shard\"}}}");
        String edited = PackScanner.fingerprintOfDirectory(dir);
        assertNotEquals(before, edited);
        Files.setLastModifiedTime(dir.resolve("manifest.json"), FileTime.fromMillis(12345L));
        assertNotEquals(edited, PackScanner.fingerprintOfDirectory(dir), "touching a file counts as a change");
    }

    @Test
    @DisplayName("cache folder names are filesystem safe and archive detection is case-insensitive")
    void naming() {
        assertEquals("my_pack_v2-" + "0123456789ab", PackScanner.cacheName("My Pack v2.zip".replace(".zip", ""), "0123456789abcdef"));
        assertTrue(PackScanner.isArchive("A.ZIP"));
        assertTrue(PackScanner.isArchive("addon.McAddon"));
        assertFalse(PackScanner.isArchive("addon.rar"));
    }
}
