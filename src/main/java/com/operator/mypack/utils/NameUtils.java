package com.operator.mypack.utils;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Identifier helpers shared by the pack loader, the resource pack builder and the model baker.
 */
public final class NameUtils {

    private static final Pattern UNSAFE = Pattern.compile("[^a-z0-9_\\-]");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.\\-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_.\\-/]+");
    private static final Pattern MULTI_UNDERSCORE = Pattern.compile("_{2,}");

    private NameUtils() {
    }

    /**
     * Lower-cases the input and maps every character outside {@code [a-z0-9_-]} to {@code _}.
     * The result is always usable as a part of a resource location.
     */
    public static String safe(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "_";
        }
        return UNSAFE.matcher(raw.toLowerCase(Locale.ROOT)).replaceAll("_");
    }

    /** Like {@link #safe(String)} but also collapses runs of underscores and trims them; never empty. */
    public static String slug(String raw, String fallback) {
        String s = MULTI_UNDERSCORE.matcher(safe(raw)).replaceAll("_");
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '_') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '_') {
            end--;
        }
        s = s.substring(start, end);
        return s.isEmpty() ? fallback : s;
    }

    public static boolean isValidNamespace(String namespace) {
        return namespace != null && !namespace.isEmpty() && NAMESPACE.matcher(namespace).matches();
    }

    public static boolean isValidPath(String path) {
        return path != null && !path.isEmpty() && PATH.matcher(path).matches();
    }

    /**
     * Builds the {@code item_model} key for one baked cube: {@code <ns>:<model>_<bone>_<cube>}, all lower-case and
     * passed through {@link #safe(String)}. The same string (without the namespace) is the item definition file name.
     */
    public static String cubeModelPath(String model, String bone, String cube) {
        return safe(model) + "_" + safe(bone) + "_" + safe(cube);
    }

    /** Strips a trailing extension (everything after the last dot of the last path segment). */
    public static String stripExtension(String fileName) {
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        int dot = fileName.lastIndexOf('.');
        return dot > slash ? fileName.substring(0, dot) : fileName;
    }

    /** Last path segment of a '/'-separated path. */
    public static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
