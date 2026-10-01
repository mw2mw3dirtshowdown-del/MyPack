package com.operator.mypack.content;

import com.operator.mypack.utils.NameUtils;
import org.bukkit.NamespacedKey;

import java.util.Optional;

/** A namespaced identifier ({@code aether:blaster}); namespace and path follow Minecraft's resource location rules. */
public record ContentId(String namespace, String path) {

    public ContentId {
        if (!NameUtils.isValidNamespace(namespace)) {
            throw new IllegalArgumentException("invalid namespace '" + namespace + "' (allowed: a-z 0-9 _ . -)");
        }
        if (!NameUtils.isValidPath(path)) {
            throw new IllegalArgumentException("invalid identifier path '" + path + "' (allowed: a-z 0-9 _ . - /)");
        }
    }

    /**
     * Parses {@code "ns:path"}; a bare {@code "path"} gets {@code defaultNamespace}.
     *
     * @throws IllegalArgumentException when the text is not a valid identifier
     */
    public static ContentId parse(String raw, String defaultNamespace) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("identifier is empty");
        }
        String text = raw.trim();
        int colon = text.indexOf(':');
        if (colon < 0) {
            return new ContentId(defaultNamespace, text);
        }
        return new ContentId(text.substring(0, colon), text.substring(colon + 1));
    }

    public static Optional<ContentId> tryParse(String raw, String defaultNamespace) {
        try {
            return Optional.of(parse(raw, defaultNamespace));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** {@code namespace:path}. */
    public String full() {
        return namespace + ":" + path;
    }

    public NamespacedKey toKey() {
        return new NamespacedKey(namespace, path);
    }

    @Override
    public String toString() {
        return full();
    }
}
