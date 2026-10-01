package com.operator.mypack.pack;

import com.operator.mypack.config.Settings;
import com.operator.mypack.utils.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts untrusted archives. Defends against path traversal ("zip slip"), absolute paths, backslash tricks,
 * duplicate / case-colliding entries and zip bombs (entry count, per-entry and total extracted size are all enforced on
 * the bytes actually read, never on the sizes the archive claims).
 */
public final class SafeZip {

    /** Raised when an archive violates the extraction rules. */
    public static final class ZipSecurityException extends IOException {
        private static final long serialVersionUID = 1L;

        public ZipSecurityException(String message) {
            super(message);
        }
    }

    /** Statistics of a finished extraction. */
    public record Result(int files, long bytes) {
    }

    private SafeZip() {
    }

    public static Result extract(Path archive, Path targetDir, Settings.Limits limits) throws IOException {
        long archiveSize = Files.size(archive);
        if (archiveSize > limits.maxArchiveBytes()) {
            throw new ZipSecurityException("archive is " + archiveSize + " bytes, the limit is " + limits.maxArchiveBytes());
        }
        Files.createDirectories(targetDir);
        Path root = targetDir.toAbsolutePath().normalize();

        Set<String> seen = new HashSet<>();
        int files = 0;
        long total = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            if (zip.size() > limits.maxEntries()) {
                throw new ZipSecurityException("archive has " + zip.size() + " entries, the limit is " + limits.maxEntries());
            }
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = validateName(entry.getName());
                if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                    throw new ZipSecurityException("duplicate entry (or entry differing only by case): " + name);
                }
                Path destination = FileUtils.resolveInside(root, name);
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                long remainingTotal = limits.maxExtractedBytes() - total;
                long allowed = Math.min(limits.maxEntryBytes(), remainingTotal);
                long written;
                try (InputStream in = zip.getInputStream(entry)) {
                    written = copyLimited(in, destination, allowed, name, remainingTotal < limits.maxEntryBytes());
                }
                total += written;
                files++;
            }
        }
        return new Result(files, total);
    }

    /** Rejects entry names that could escape the target directory or are ambiguous on some file systems. */
    static String validateName(String raw) throws ZipSecurityException {
        if (raw == null || raw.isEmpty()) {
            throw new ZipSecurityException("entry with an empty name");
        }
        if (raw.indexOf('\0') >= 0) {
            throw new ZipSecurityException("entry name contains a NUL character");
        }
        if (raw.indexOf('\\') >= 0) {
            throw new ZipSecurityException("entry name contains a backslash: " + raw);
        }
        if (raw.startsWith("/") || (raw.length() >= 2 && raw.charAt(1) == ':')) {
            throw new ZipSecurityException("absolute path in archive: " + raw);
        }
        for (String segment : raw.split("/")) {
            if (segment.equals("..")) {
                throw new ZipSecurityException("path traversal in archive entry: " + raw);
            }
        }
        return raw;
    }

    private static long copyLimited(InputStream in, Path destination, long allowedBytes, String name, boolean totalLimit)
            throws IOException {
        long written = 0;
        Path temp = Files.createTempFile(destination.getParent(), ".mypack-", ".part");
        try (OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                written += read;
                if (written > allowedBytes) {
                    throw new ZipSecurityException((totalLimit ? "extracted size limit exceeded at " : "entry too large: ") + name);
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
        return written;
    }
}
