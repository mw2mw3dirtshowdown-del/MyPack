package com.operator.mypack.content.sound;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.operator.mypack.content.ContentId;
import com.operator.mypack.content.ParseContext;
import com.operator.mypack.utils.JsonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parses {@code sounds/sound_definitions.json} (Bedrock layout, format 1.14.0 and the older flat layout). */
public final class SoundParser {

    private static final Set<String> CATEGORIES = Set.of("master", "music", "record", "weather", "block", "hostile",
            "neutral", "player", "ambient", "voice");

    private SoundParser() {
    }

    public static List<SoundEvent> parse(JsonObject root, ParseContext ctx) {
        JsonObject definitions = JsonUtils.object(root, "sound_definitions").orElse(root);
        List<SoundEvent> out = new ArrayList<>();
        for (String key : definitions.keySet()) {
            if (key.equals("format_version")) {
                continue;
            }
            JsonElement element = definitions.get(key);
            if (!element.isJsonObject()) {
                ctx.warn("sound '" + key + "' is not an object; skipped");
                continue;
            }
            SoundEvent event = event(key, element.getAsJsonObject(), ctx);
            if (event != null) {
                out.add(event);
            }
        }
        return out;
    }

    private static SoundEvent event(String key, JsonObject o, ParseContext ctx) {
        ContentId id;
        try {
            id = new ContentId(ctx.namespace(), key.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            ctx.warn("sound event '" + key + "' has an invalid name (" + e.getMessage() + "); skipped");
            return null;
        }
        String category = JsonUtils.string(o, "category", "neutral").toLowerCase(Locale.ROOT);
        if (!CATEGORIES.contains(category)) {
            ctx.warn("sound '" + key + "': unknown category '" + category + "'; using neutral");
            category = "neutral";
        }
        List<SoundEvent.SoundFile> files = new ArrayList<>();
        for (JsonElement element : JsonUtils.array(o, "sounds").orElse(new JsonArray())) {
            SoundEvent.SoundFile file = file(element, key, ctx);
            if (file != null) {
                files.add(file);
            }
        }
        if (files.isEmpty()) {
            ctx.warn("sound '" + key + "' has no usable files; skipped");
            return null;
        }
        return new SoundEvent(id, category, List.copyOf(files), JsonUtils.string(o, "subtitle", ""));
    }

    private static SoundEvent.SoundFile file(JsonElement element, String eventKey, ParseContext ctx) {
        String name;
        float volume = 1.0F;
        float pitch = 1.0F;
        int weight = 1;
        boolean stream = false;
        if (element.isJsonPrimitive()) {
            name = element.getAsString();
        } else if (element.isJsonObject()) {
            JsonObject o = element.getAsJsonObject();
            name = JsonUtils.string(o, "name", "");
            volume = (float) Math.max(0.0D, Math.min(10.0D, JsonUtils.number(o, "volume", 1.0D)));
            JsonElement pitchElement = o.get("pitch");
            if (pitchElement != null && pitchElement.isJsonArray() && pitchElement.getAsJsonArray().size() == 2) {
                // Bedrock allows a [min, max] range; Java sounds.json has a single value, so use the midpoint.
                pitch = (float) ((pitchElement.getAsJsonArray().get(0).getAsDouble()
                        + pitchElement.getAsJsonArray().get(1).getAsDouble()) / 2.0D);
            } else {
                pitch = (float) JsonUtils.number(o, "pitch", 1.0D);
            }
            pitch = Math.max(0.1F, Math.min(2.0F, pitch));
            weight = Math.max(1, JsonUtils.integer(o, "weight", 1));
            stream = JsonUtils.bool(o, "stream", false);
        } else {
            return null;
        }
        name = name.trim();
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".ogg", ".wav", ".fsb", ".mp3"}) {
            if (lower.endsWith(ext)) {
                if (!ext.equals(".ogg")) {
                    ctx.warn("sound '" + eventKey + "': " + name + " is not .ogg; Java Edition only plays Ogg Vorbis");
                }
                name = name.substring(0, name.length() - ext.length());
                break;
            }
        }
        if (name.isEmpty() || name.contains("..") || name.startsWith("/") || name.contains("\\")) {
            ctx.warn("sound '" + eventKey + "' has an invalid file reference '" + name + "'; skipped");
            return null;
        }
        if (!name.startsWith("sounds/")) {
            name = "sounds/" + name;
        }
        return new SoundEvent.SoundFile(name, volume, pitch, weight, stream);
    }
}
