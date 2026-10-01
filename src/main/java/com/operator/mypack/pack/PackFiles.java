package com.operator.mypack.pack;

import com.operator.mypack.utils.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only view of one pack's files. Every lookup is resolved inside the pack root, so a crafted path in a JSON file
 * can never read outside the pack.
 */
public final class PackFiles {

    /** Upper bound for a single file read into memory (JSON documents, textures). */
    public static final long MAX_READ_BYTES = 128L * 1024L * 1024L;

    private final Path root;

    public PackFiles(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public boolean exists(String relative) {
        try {
            return Files.isRegularFile(FileUtils.resolveInside(root, relative));
        } catch (IOException e) {
            return false;
        }
    }

    public byte[] read(String relative) throws IOException {
        Path file = FileUtils.resolveInside(root, relative);
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("no such file in pack: " + relative);
        }
        long size = Files.size(file);
        if (size > MAX_READ_BYTES) {
            throw new IOException(relative + " is too large (" + size + " bytes)");
        }
        return Files.readAllBytes(file);
    }

    /** All files below {@code directory} (recursively) whose name ends with {@code suffix}; pack-relative paths. */
    public List<String> list(String directory, String suffix) {
        List<String> out = new ArrayList<>();
        try {
            Path dir = FileUtils.resolveInside(root, directory);
            String prefix = directory.endsWith("/") ? directory : directory + "/";
            for (String relative : FileUtils.listFiles(dir)) {
                if (suffix.isEmpty() || relative.toLowerCase(java.util.Locale.ROOT).endsWith(suffix)) {
                    out.add(prefix + relative);
                }
            }
        } catch (IOException e) {
            // unreadable or missing directory: treated as empty
        }
        return out;
    }

    /** Every file of the pack as a pack-relative path. */
    public List<String> listAll() {
        try {
            return FileUtils.listFiles(root);
        } catch (IOException e) {
            return List.of();
        }
    }
}
