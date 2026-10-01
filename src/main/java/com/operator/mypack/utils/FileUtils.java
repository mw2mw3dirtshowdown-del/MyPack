package com.operator.mypack.utils;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/** Blocking file helpers. Callers are responsible for invoking them off the main/region threads. */
public final class FileUtils {

    private FileUtils() {
    }

    /** Recursively deletes {@code dir} if it exists. Symbolic links are removed, never followed. */
    public static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Lists regular files below {@code root} as '/'-separated relative paths, sorted for deterministic output.
     * Symbolic links are skipped.
     */
    public static List<String> listFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)).forEach(p -> {
                StringBuilder sb = new StringBuilder();
                for (Path part : root.relativize(p)) {
                    if (sb.length() > 0) {
                        sb.append('/');
                    }
                    sb.append(part);
                }
                out.add(sb.toString());
            });
        }
        Collections.sort(out);
        return out;
    }

    /** Resolves {@code relative} below {@code root} and rejects anything that escapes it (path traversal guard). */
    public static Path resolveInside(Path root, String relative) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(relative).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IOException("path escapes its root: " + relative);
        }
        return resolved;
    }

    public static long sizeOrZero(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }
}
