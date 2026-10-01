package com.operator.mypack.pack;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Finds the directory that contains {@code manifest.json}. */
public final class PackLocator {

    private PackLocator() {
    }

    /**
     * {@code root} itself when it contains the manifest, otherwise the single sub-directory that does (zip files made
     * by "compress folder" put everything in one top level folder). Empty when there is no unambiguous manifest.
     */
    public static Optional<Path> findRoot(Path root) {
        if (Files.isRegularFile(root.resolve("manifest.json"))) {
            return Optional.of(root);
        }
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path child : children) {
                if (Files.isRegularFile(child.resolve("manifest.json"))) {
                    candidates.add(child);
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }
}
