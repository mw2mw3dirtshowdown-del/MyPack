package com.operator.mypack.pack;

import java.util.List;

/**
 * A pack that is loaded in memory.
 *
 * @param sourceFile  file or folder name inside the packs directory
 * @param fingerprint content fingerprint used to detect changes between reloads
 * @param issues      problems found while loading (warnings and skipped files)
 */
public record InstalledPack(
        PackManifest manifest,
        String sourceFile,
        PackFiles files,
        PackContent content,
        String fingerprint,
        List<Issues.Issue> issues) {

    public String namespace() {
        return manifest.namespace();
    }

    public String displayName() {
        return manifest.name();
    }
}
