package com.operator.mypack.content;

import com.google.gson.JsonObject;
import com.operator.mypack.pack.Issues;
import com.operator.mypack.utils.JsonUtils;

/**
 * What a parser needs to know about the file it is reading: the pack namespace (which owns every identifier) and where
 * to report problems.
 */
public record ParseContext(String namespace, String file, Issues issues) {

    public void warn(String message) {
        issues.warn(file, message);
    }

    public void error(String message) {
        issues.error(file, message);
    }

    /**
     * Reads {@code description.identifier} and enforces that it belongs to the pack's namespace. Returns {@code null}
     * (after reporting an error) when the identifier is missing, malformed or foreign.
     */
    public ContentId identifier(JsonObject description) {
        String raw = JsonUtils.string(description, "identifier", null);
        if (raw == null || raw.isBlank()) {
            error("description.identifier is required");
            return null;
        }
        ContentId id;
        try {
            id = ContentId.parse(raw, namespace);
        } catch (IllegalArgumentException e) {
            error("identifier '" + raw + "': " + e.getMessage());
            return null;
        }
        if (!id.namespace().equals(namespace)) {
            error("identifier '" + raw + "' must use the pack namespace '" + namespace + "'");
            return null;
        }
        return id;
    }
}
