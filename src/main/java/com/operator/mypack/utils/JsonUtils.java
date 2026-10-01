package com.operator.mypack.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lenient JSON helpers. Bedrock add-ons are routinely authored with comments and trailing commas, so every parse
 * goes through a lenient {@link JsonReader}.
 */
public final class JsonUtils {

    public static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private JsonUtils() {
    }

    public static JsonElement parse(String json) {
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setStrictness(com.google.gson.Strictness.LENIENT);
        return JsonParser.parseReader(reader);
    }

    public static JsonElement parse(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1); // strip BOM written by some Windows editors
        }
        return parse(text);
    }

    public static JsonElement parse(Path file) throws IOException {
        return parse(Files.readAllBytes(file));
    }

    /** Parses a document whose root must be an object. */
    public static JsonObject parseObject(byte[] bytes) {
        JsonElement element = parse(bytes);
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("root element must be a JSON object");
        }
        return element.getAsJsonObject();
    }

    // ------------------------------------------------------------------ typed accessors

    public static Optional<JsonObject> object(JsonObject parent, String key) {
        JsonElement e = parent == null ? null : parent.get(key);
        return e != null && e.isJsonObject() ? Optional.of(e.getAsJsonObject()) : Optional.empty();
    }

    public static Optional<JsonArray> array(JsonObject parent, String key) {
        JsonElement e = parent == null ? null : parent.get(key);
        return e != null && e.isJsonArray() ? Optional.of(e.getAsJsonArray()) : Optional.empty();
    }

    public static String string(JsonObject o, String key, String def) {
        JsonElement e = o == null ? null : o.get(key);
        if (e != null && e.isJsonPrimitive()) {
            return e.getAsString();
        }
        return def;
    }

    public static int integer(JsonObject o, String key, int def) {
        JsonElement e = o == null ? null : o.get(key);
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return e.getAsInt();
        }
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            try {
                return Integer.parseInt(e.getAsString().trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static double number(JsonObject o, String key, double def) {
        JsonElement e = o == null ? null : o.get(key);
        if (e != null && e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) {
                return p.getAsDouble();
            }
            if (p.isString()) {
                try {
                    return Double.parseDouble(p.getAsString().trim());
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
        }
        return def;
    }

    public static boolean bool(JsonObject o, String key, boolean def) {
        JsonElement e = o == null ? null : o.get(key);
        if (e != null && e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return p.getAsBoolean();
            }
            if (p.isString()) {
                return Boolean.parseBoolean(p.getAsString());
            }
        }
        return def;
    }

    /** Reads {@code key} as an array of strings; a single string is accepted as a one element list. */
    public static List<String> stringList(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        JsonElement e = o == null ? null : o.get(key);
        if (e == null) {
            return out;
        }
        if (e.isJsonArray()) {
            for (JsonElement item : e.getAsJsonArray()) {
                if (item.isJsonPrimitive()) {
                    out.add(item.getAsString());
                }
            }
        } else if (e.isJsonPrimitive()) {
            out.add(e.getAsString());
        }
        return out;
    }

    /** Reads {@code key} as a numeric vector of exactly {@code size} entries, otherwise {@code def}. */
    public static double[] vector(JsonObject o, String key, int size, double[] def) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != size) {
            return def;
        }
        double[] out = new double[size];
        for (int i = 0; i < size; i++) {
            JsonElement item = e.getAsJsonArray().get(i);
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) {
                return def;
            }
            out[i] = item.getAsDouble();
        }
        return out;
    }
}
