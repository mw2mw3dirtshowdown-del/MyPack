package com.operator.mypack.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.operator.mypack.utils.JsonUtils;
import com.operator.mypack.utils.NameUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Parses and validates {@code manifest.json}. */
public final class ManifestParser {

    /** Raised when a manifest cannot be used; carries every problem that was found. */
    public static final class ManifestException extends Exception {
        private static final long serialVersionUID = 1L;

        private final List<String> problems;

        public ManifestException(List<String> problems) {
            super(String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    private ManifestParser() {
    }

    /**
     * @param json         raw bytes of manifest.json
     * @param fallbackName used to derive a namespace when the manifest does not declare one
     * @param issues       receives non-fatal remarks (for example a derived namespace)
     */
    public static PackManifest parse(byte[] json, String fallbackName, Issues issues) throws ManifestException {
        List<String> errors = new ArrayList<>();
        JsonObject root;
        try {
            root = JsonUtils.parseObject(json);
        } catch (RuntimeException e) {
            throw new ManifestException(List.of("manifest.json is not valid JSON: " + e.getMessage()));
        }

        int formatVersion = JsonUtils.integer(root, "format_version", 2);
        if (formatVersion != 1 && formatVersion != 2) {
            errors.add("unsupported format_version " + formatVersion + " (expected 1 or 2)");
        }

        JsonObject header = JsonUtils.object(root, "header").orElse(null);
        if (header == null) {
            throw new ManifestException(List.of("manifest.json has no \"header\" object"));
        }

        String name = JsonUtils.string(header, "name", "").trim();
        if (name.isEmpty()) {
            errors.add("header.name is required");
        } else if (name.length() > 128) {
            errors.add("header.name is longer than 128 characters");
        }

        UUID uuid = parseUuid(JsonUtils.string(header, "uuid", null));
        if (uuid == null) {
            errors.add("header.uuid is required and must be a UUID");
        }

        Version version = Version.fromJson(header.get("version")).orElse(null);
        if (version == null) {
            errors.add("header.version is required: [major, minor, patch] or \"1.2.3\"");
        }

        Version minEngine = Version.fromJson(header.get("min_engine_version")).orElse(null);

        JsonObject metadata = JsonUtils.object(root, "metadata").orElse(new JsonObject());
        String namespace = JsonUtils.string(metadata, "namespace", JsonUtils.string(header, "namespace", "")).trim();
        if (namespace.isEmpty()) {
            namespace = NameUtils.slug(name.isEmpty() ? fallbackName : name, "pack");
            issues.warn("manifest.json", "metadata.namespace is missing; derived '" + namespace + "' from the pack name");
        } else if (!NameUtils.isValidNamespace(namespace)) {
            errors.add("metadata.namespace '" + namespace + "' must match [a-z0-9_.-]+");
        }
        if ("minecraft".equals(namespace) || "mypack".equals(namespace)) {
            errors.add("metadata.namespace '" + namespace + "' is reserved");
        }

        if (!errors.isEmpty()) {
            throw new ManifestException(errors);
        }

        List<PackManifest.Module> modules = new ArrayList<>();
        for (JsonElement element : JsonUtils.array(root, "modules").orElse(new JsonArray())) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject module = element.getAsJsonObject();
            String type = JsonUtils.string(module, "type", "").toLowerCase(java.util.Locale.ROOT);
            if (!type.equals("data") && !type.equals("resources") && !type.equals("client_data")) {
                issues.warn("manifest.json", "ignoring unsupported module type '" + type + "'");
                continue;
            }
            UUID moduleUuid = parseUuid(JsonUtils.string(module, "uuid", null));
            Version moduleVersion = Version.fromJson(module.get("version")).orElse(version);
            if (moduleUuid == null) {
                issues.warn("manifest.json", "module '" + type + "' has no valid uuid");
                continue;
            }
            modules.add(new PackManifest.Module(type, moduleUuid, moduleVersion));
        }

        List<PackManifest.Dependency> dependencies = new ArrayList<>();
        for (JsonElement element : JsonUtils.array(root, "dependencies").orElse(new JsonArray())) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject dep = element.getAsJsonObject();
            UUID depUuid = parseUuid(JsonUtils.string(dep, "uuid", null));
            if (depUuid == null) {
                // Bedrock also allows script module dependencies ("module_name"); they have no meaning here.
                issues.warn("manifest.json", "ignoring dependency without a valid uuid");
                continue;
            }
            Version min = Version.fromJson(dep.get("version")).orElse(Version.ZERO);
            dependencies.add(new PackManifest.Dependency(depUuid, min));
        }

        return new PackManifest(
                formatVersion,
                uuid,
                name,
                JsonUtils.string(header, "description", ""),
                version,
                minEngine,
                namespace,
                authors(metadata),
                JsonUtils.string(metadata, "license", ""),
                JsonUtils.string(metadata, "url", ""),
                List.copyOf(modules),
                List.copyOf(dependencies));
    }

    private static List<String> authors(JsonObject metadata) {
        return JsonUtils.stringList(metadata, "authors");
    }

    private static UUID parseUuid(String text) {
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Convenience used by tests and the dependency resolver. */
    public static Optional<UUID> tryUuid(String text) {
        return Optional.ofNullable(parseUuid(text));
    }
}
