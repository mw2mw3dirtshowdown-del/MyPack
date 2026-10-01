package com.operator.mypack.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A {@code major.minor.patch} version as used by pack manifests ({@code [1, 2, 3]} or {@code "1.2.3"}). */
public record Version(int major, int minor, int patch) implements Comparable<Version> {

    private static final Pattern TEXT = Pattern.compile("(\\d{1,6})(?:\\.(\\d{1,6}))?(?:\\.(\\d{1,6}))?(?:[-+].*)?");

    public static final Version ZERO = new Version(0, 0, 0);

    public Version {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("version components must not be negative");
        }
    }

    /** Parses {@code "1"}, {@code "1.2"} or {@code "1.2.3"} (a {@code -suffix} is ignored). */
    public static Version parse(String text) {
        return tryParse(text).orElseThrow(() -> new IllegalArgumentException("not a version: '" + text + "'"));
    }

    public static Optional<Version> tryParse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = TEXT.matcher(text.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Version(
                Integer.parseInt(m.group(1)),
                m.group(2) == null ? 0 : Integer.parseInt(m.group(2)),
                m.group(3) == null ? 0 : Integer.parseInt(m.group(3))));
    }

    /** Parses either a JSON array of 1-3 non-negative integers or a version string. */
    public static Optional<Version> fromJson(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Optional.empty();
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (array.isEmpty() || array.size() > 3) {
                return Optional.empty();
            }
            int[] parts = new int[3];
            for (int i = 0; i < array.size(); i++) {
                JsonElement item = array.get(i);
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) {
                    return Optional.empty();
                }
                int value = item.getAsInt();
                if (value < 0) {
                    return Optional.empty();
                }
                parts[i] = value;
            }
            return Optional.of(new Version(parts[0], parts[1], parts[2]));
        }
        if (element.isJsonPrimitive()) {
            return tryParse(element.getAsString());
        }
        return Optional.empty();
    }

    /** {@code true} when this version is the same as or newer than {@code minimum}. */
    public boolean atLeast(Version minimum) {
        return compareTo(minimum) >= 0;
    }

    @Override
    public int compareTo(Version other) {
        int c = Integer.compare(major, other.major);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(minor, other.minor);
        return c != 0 ? c : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
