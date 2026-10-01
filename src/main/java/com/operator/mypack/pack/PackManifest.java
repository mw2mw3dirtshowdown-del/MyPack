package com.operator.mypack.pack;

import java.util.List;
import java.util.UUID;

/**
 * Parsed {@code manifest.json}. The layout follows Bedrock add-ons ({@code header}, {@code modules},
 * {@code dependencies}); the namespace that owns every identifier of the pack comes from {@code metadata.namespace}.
 */
public record PackManifest(
        int formatVersion,
        UUID uuid,
        String name,
        String description,
        Version version,
        Version minEngineVersion,
        String namespace,
        List<String> authors,
        String license,
        String url,
        List<Module> modules,
        List<Dependency> dependencies) {

    /** A {@code data} (behaviour) or {@code resources} module declared by the pack. */
    public record Module(String type, UUID uuid, Version version) {
    }

    /** A hard dependency on another pack, resolved by uuid and a minimum version. */
    public record Dependency(UUID uuid, Version minVersion) {
    }
}
