package com.operator.mypack.pack;

import com.operator.mypack.config.Settings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeZipTest {

    private static final Settings.Limits LIMITS = new Settings.Limits(10_000_000L, 20_000_000L, 1_000_000L, 100);

    @TempDir
    Path tmp;

    private Path zip(String name, Map<String, byte[]> entries) throws IOException {
        Path file = tmp.resolve(name);
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return file;
    }

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("extracts a normal archive including nested folders")
    void extractsNormalArchive() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.json", text("{}"));
        entries.put("textures/items/a.png", new byte[]{1, 2, 3});
        entries.put("empty/", new byte[0]);
        Path archive = zip("ok.zip", entries);
        Path target = tmp.resolve("out");

        SafeZip.Result result = SafeZip.extract(archive, target, LIMITS);

        assertEquals(2, result.files());
        assertEquals(5, result.bytes());
        assertEquals("{}", Files.readString(target.resolve("manifest.json")));
        assertTrue(Files.isRegularFile(target.resolve("textures/items/a.png")));
        assertTrue(Files.isDirectory(target.resolve("empty")));
    }

    @Test
    @DisplayName("zip slip: '../' entries are rejected and nothing is written outside the target")
    void rejectsZipSlip() throws IOException {
        Path archive = zip("slip.zip", Map.of("../evil.txt", text("pwned")));
        Path target = tmp.resolve("out");
        SafeZip.ZipSecurityException e = assertThrows(SafeZip.ZipSecurityException.class,
                () -> SafeZip.extract(archive, target, LIMITS));
        assertTrue(e.getMessage().contains("traversal"), e.getMessage());
        assertFalse(Files.exists(tmp.resolve("evil.txt")));
    }

    @Test
    @DisplayName("nested traversal 'a/../../x' is rejected too")
    void rejectsNestedTraversal() throws IOException {
        Path archive = zip("slip2.zip", Map.of("a/../../x.txt", text("x")));
        assertThrows(SafeZip.ZipSecurityException.class, () -> SafeZip.extract(archive, tmp.resolve("out"), LIMITS));
        assertFalse(Files.exists(tmp.resolve("x.txt")));
    }

    @Test
    @DisplayName("absolute paths, drive letters and backslashes are rejected")
    void rejectsAbsoluteAndBackslash() throws IOException {
        for (String name : new String[]{"/etc/passwd", "C:/Windows/x.txt", "dir\\file.txt"}) {
            Path archive = zip("bad-" + Math.abs(name.hashCode()) + ".zip", Map.of(name, text("x")));
            assertThrows(SafeZip.ZipSecurityException.class,
                    () -> SafeZip.extract(archive, tmp.resolve("out-" + Math.abs(name.hashCode())), LIMITS), name);
        }
    }

    @Test
    @DisplayName("entries that differ only by case are rejected (they would overwrite each other)")
    void rejectsCaseCollisions() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.json", text("a"));
        entries.put("MANIFEST.json", text("b"));
        Path archive = zip("case.zip", entries);
        assertThrows(SafeZip.ZipSecurityException.class, () -> SafeZip.extract(archive, tmp.resolve("out"), LIMITS));
    }

    @Test
    @DisplayName("zip bomb: a single huge entry is stopped by the per-entry limit")
    void stopsOversizedEntry() throws IOException {
        Path archive = zip("bomb.zip", Map.of("big.bin", new byte[5_000_000])); // compresses to a few KB
        assertTrue(Files.size(archive) < 100_000, "test archive must be tiny to prove the point");
        SafeZip.ZipSecurityException e = assertThrows(SafeZip.ZipSecurityException.class,
                () -> SafeZip.extract(archive, tmp.resolve("out"), LIMITS));
        assertTrue(e.getMessage().contains("too large"), e.getMessage());
        assertEquals(0, countFiles(tmp.resolve("out")), "partial files must be cleaned up");
    }

    @Test
    @DisplayName("zip bomb: many medium entries are stopped by the total limit")
    void stopsTotalSize() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            entries.put("f" + i + ".bin", new byte[900_000]);
        }
        Settings.Limits tight = new Settings.Limits(10_000_000L, 3_000_000L, 1_000_000L, 100);
        Path archive = zip("total.zip", entries);
        SafeZip.ZipSecurityException e = assertThrows(SafeZip.ZipSecurityException.class,
                () -> SafeZip.extract(archive, tmp.resolve("out"), tight));
        assertTrue(e.getMessage().contains("limit"), e.getMessage());
    }

    @Test
    @DisplayName("entry count limit is enforced before extraction starts")
    void enforcesEntryCount() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            entries.put("f" + i, text("x"));
        }
        Settings.Limits few = new Settings.Limits(10_000_000L, 20_000_000L, 1_000_000L, 10);
        Path archive = zip("many.zip", entries);
        assertThrows(SafeZip.ZipSecurityException.class, () -> SafeZip.extract(archive, tmp.resolve("out"), few));
        assertFalse(Files.exists(tmp.resolve("out/f0")));
    }

    @Test
    @DisplayName("archive size limit is enforced")
    void enforcesArchiveSize() throws IOException {
        Path archive = zip("a.zip", Map.of("a", text("hello")));
        Settings.Limits tiny = new Settings.Limits(10L, 20_000_000L, 1_000_000L, 10);
        assertThrows(SafeZip.ZipSecurityException.class, () -> SafeZip.extract(archive, tmp.resolve("out"), tiny));
    }

    private static long countFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).count();
        }
    }
}
