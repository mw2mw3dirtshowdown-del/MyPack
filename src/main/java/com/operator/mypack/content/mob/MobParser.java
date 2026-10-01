package com.operator.mypack.content.mob;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.operator.mypack.content.ContentId;
import com.operator.mypack.content.ParseContext;
import com.operator.mypack.utils.JsonUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Parses {@code entities/*.json} (mobs) and {@code furniture/*.json} (decoration). */
public final class MobParser {

    private static final Set<String> MOB_COMPONENTS = Set.of(
            "minecraft:type_family", "minecraft:health", "minecraft:movement", "minecraft:attack",
            "minecraft:follow_range", "minecraft:knockback_resistance", "minecraft:scale", "minecraft:loot",
            "mypack:base_entity", "mypack:display_name", "mypack:name_visible", "mypack:silent", "mypack:persistent",
            "mypack:burns_in_daylight", "mypack:model", "mypack:hitbox");
    private static final Set<String> FURNITURE_COMPONENTS = Set.of(
            "minecraft:display_name", "minecraft:icon", "mypack:lore", "mypack:model", "mypack:hitbox", "mypack:seats",
            "mypack:rotation_step");
    private static final Set<String> ANIMATION_STATES = Set.of("idle", "walk", "attack", "hurt", "death");
    private static final Pattern ENTITY_TYPE = Pattern.compile("[A-Z0-9_]{1,64}");

    private MobParser() {
    }

    // ------------------------------------------------------------------ mobs

    public static MobDefinition parseMob(JsonObject root, ParseContext ctx) {
        JsonObject entity = JsonUtils.object(root, "minecraft:entity").orElse(null);
        if (entity == null) {
            ctx.error("missing \"minecraft:entity\" object");
            return null;
        }
        ContentId id = ctx.identifier(JsonUtils.object(entity, "description").orElse(new JsonObject()));
        if (id == null) {
            return null;
        }
        JsonObject c = JsonUtils.object(entity, "components").orElse(new JsonObject());
        for (String key : c.keySet()) {
            if (!MOB_COMPONENTS.contains(key)) {
                ctx.warn("component '" + key + "' is not supported and was ignored");
            }
        }

        String base = JsonUtils.string(c, "mypack:base_entity", "ZOMBIE").trim().toUpperCase(Locale.ROOT);
        if (!ENTITY_TYPE.matcher(base).matches()) {
            ctx.warn("mypack:base_entity '" + base + "' is not a valid entity type; using ZOMBIE");
            base = "ZOMBIE";
        }
        double health = clamp(valueOf(c, "minecraft:health", "value", 20.0D), 1.0D, 2048.0D, "minecraft:health", ctx);
        double movement = valueOf(c, "minecraft:movement", "value", -1.0D);
        double attack = valueOf(c, "minecraft:attack", "damage", -1.0D);
        double follow = valueOf(c, "minecraft:follow_range", "value", -1.0D);
        double knockback = valueOf(c, "minecraft:knockback_resistance", "value", -1.0D);
        double scale = clamp(valueOf(c, "minecraft:scale", "value", 1.0D), 0.0625D, 16.0D, "minecraft:scale", ctx);

        String lootTable = null;
        JsonElement loot = c.get("minecraft:loot");
        String lootRaw = loot == null ? null : loot.isJsonObject() ? JsonUtils.string(loot.getAsJsonObject(), "table", null)
                : loot.isJsonPrimitive() ? loot.getAsString() : null;
        if (lootRaw != null && !lootRaw.isBlank()) {
            lootTable = normalizeLoot(lootRaw, ctx);
        }

        MobDefinition.Hitbox hitbox = null;
        JsonObject hb = JsonUtils.object(c, "mypack:hitbox").orElse(null);
        if (hb != null) {
            double w = JsonUtils.number(hb, "width", 0.0D);
            double h = JsonUtils.number(hb, "height", 0.0D);
            if (w > 0.0D && h > 0.0D && w <= 64.0D && h <= 64.0D) {
                hitbox = new MobDefinition.Hitbox(w, h);
            } else {
                ctx.warn("mypack:hitbox needs width and height between 0 and 64; deriving the hitbox from the model");
            }
        }

        String name = textOf(c, "mypack:display_name");
        JsonObject family = JsonUtils.object(c, "minecraft:type_family").orElse(new JsonObject());
        return new MobDefinition(
                id,
                name,
                bool(c, "mypack:name_visible", false),
                base,
                health,
                movement,
                attack,
                follow,
                knockback,
                scale,
                bool(c, "mypack:silent", false),
                bool(c, "mypack:persistent", true),
                bool(c, "mypack:burns_in_daylight", false),
                lootTable,
                model(c.get("mypack:model"), ctx, scale),
                hitbox,
                List.copyOf(JsonUtils.stringList(family, "family")));
    }

    // ------------------------------------------------------------------ furniture

    public static FurnitureDefinition parseFurniture(JsonObject root, ParseContext ctx) {
        JsonObject furniture = JsonUtils.object(root, "mypack:furniture").orElse(null);
        if (furniture == null) {
            ctx.error("missing \"mypack:furniture\" object");
            return null;
        }
        ContentId id = ctx.identifier(JsonUtils.object(furniture, "description").orElse(new JsonObject()));
        if (id == null) {
            return null;
        }
        JsonObject c = JsonUtils.object(furniture, "components").orElse(new JsonObject());
        for (String key : c.keySet()) {
            if (!FURNITURE_COMPONENTS.contains(key)) {
                ctx.warn("component '" + key + "' is not supported and was ignored");
            }
        }
        String name = textOf(c, "minecraft:display_name");
        String icon = icon(c.get("minecraft:icon"));
        ModelBinding model = model(c.get("mypack:model"), ctx, 1.0D);
        if (model == null && icon == null) {
            ctx.warn("furniture has neither mypack:model nor minecraft:icon; it will be invisible");
        }
        double width = 0.0D;
        double height = 0.0D;
        JsonObject hb = JsonUtils.object(c, "mypack:hitbox").orElse(null);
        if (hb != null) {
            width = clamp(JsonUtils.number(hb, "width", 0.0D), 0.0D, 64.0D, "mypack:hitbox.width", ctx);
            height = clamp(JsonUtils.number(hb, "height", 0.0D), 0.0D, 64.0D, "mypack:hitbox.height", ctx);
        }
        List<FurnitureDefinition.Seat> seats = new ArrayList<>();
        for (JsonElement element : JsonUtils.array(c, "mypack:seats").orElse(new JsonArray())) {
            if (!element.isJsonObject()) {
                continue;
            }
            double[] offset = JsonUtils.vector(element.getAsJsonObject(), "offset", 3, new double[]{0.0D, 0.0D, 0.0D});
            seats.add(new FurnitureDefinition.Seat(offset[0], offset[1], offset[2]));
        }
        if (seats.size() > 8) {
            ctx.warn("more than 8 seats; only the first 8 are used");
            seats = new ArrayList<>(seats.subList(0, 8));
        }
        int step = (int) Math.round(c.has("mypack:rotation_step") ? valueOf(c, "mypack:rotation_step", "value", 0.0D) : 45.0D);
        if (step < 0 || step > 180 || (step != 0 && 360 % step != 0)) {
            ctx.warn("mypack:rotation_step must be 0 or a divisor of 360 up to 180; using 45");
            step = 45;
        }
        return new FurnitureDefinition(id, name, JsonUtils.stringList(c, "mypack:lore"), icon, model, width, height,
                List.copyOf(seats), step);
    }

    // ------------------------------------------------------------------ shared

    /** Parses the {@code mypack:model} component; returns {@code null} when it is absent or unusable. */
    private static ModelBinding model(JsonElement element, ParseContext ctx, double inheritedScale) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject m = element.getAsJsonObject();
        String geometry = JsonUtils.string(m, "geometry", "").trim();
        if (geometry.isEmpty()) {
            ctx.warn("mypack:model needs a \"geometry\"; the model is ignored");
            return null;
        }
        String geometryId = qualify(geometry, ctx);
        if (geometryId == null) {
            return null;
        }
        String texture = JsonUtils.string(m, "texture", "").trim();
        if (texture.endsWith(".png")) {
            texture = texture.substring(0, texture.length() - 4);
        }
        if (texture.startsWith("textures/entity/")) {
            texture = texture.substring("textures/entity/".length());
        }
        if (texture.isEmpty() || texture.contains("..") || texture.startsWith("/")) {
            ctx.warn("mypack:model needs a valid \"texture\" name below textures/entity/; the model is ignored");
            return null;
        }
        Map<String, String> animations = new LinkedHashMap<>();
        JsonObject anims = JsonUtils.object(m, "animations").orElse(new JsonObject());
        for (String state : anims.keySet()) {
            String key = state.toLowerCase(Locale.ROOT);
            if (!ANIMATION_STATES.contains(key)) {
                ctx.warn("animation state '" + state + "' is unknown (idle, walk, attack, hurt, death); ignored");
                continue;
            }
            String raw = JsonUtils.string(anims, state, "").trim();
            String animationId = raw.isEmpty() ? null : qualify(raw, ctx);
            if (animationId != null) {
                animations.put(key, animationId);
            }
        }
        double scale = clamp(JsonUtils.number(m, "scale", 1.0D), 0.0625D, 16.0D, "mypack:model.scale", ctx) * inheritedScale;
        double yOffset = clamp(JsonUtils.number(m, "y_offset", 0.0D), -64.0D, 64.0D, "mypack:model.y_offset", ctx);
        return new ModelBinding(geometryId, texture, scale, yOffset, Map.copyOf(animations));
    }

    /** {@code geometry.x} → {@code <pack ns>:geometry.x}; {@code other:geometry.x} is kept (cross-pack). Lower-cased. */
    private static String qualify(String raw, ParseContext ctx) {
        try {
            return ContentId.parse(raw.toLowerCase(Locale.ROOT), ctx.namespace()).full();
        } catch (IllegalArgumentException e) {
            ctx.warn("reference '" + raw + "' is not a valid identifier (" + e.getMessage() + "); ignored");
            return null;
        }
    }

    private static String normalizeLoot(String raw, ParseContext ctx) {
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("loot_tables/")) {
            text = text.substring("loot_tables/".length());
        }
        if (text.endsWith(".json")) {
            text = text.substring(0, text.length() - ".json".length());
        }
        try {
            return ContentId.parse(text, ctx.namespace()).full();
        } catch (IllegalArgumentException e) {
            ctx.warn("minecraft:loot table '" + raw + "' is not a valid identifier; the mob drops nothing");
            return null;
        }
    }

    private static String icon(JsonElement e) {
        if (e == null) {
            return null;
        }
        String name = null;
        if (e.isJsonPrimitive()) {
            name = e.getAsString();
        } else if (e.isJsonObject()) {
            name = JsonUtils.string(e.getAsJsonObject(), "texture", null);
        }
        if (name == null || name.isBlank() || name.contains("..") || name.startsWith("/")) {
            return null;
        }
        String cleaned = name.trim();
        if (cleaned.startsWith("textures/items/")) {
            cleaned = cleaned.substring("textures/items/".length());
        }
        return cleaned.endsWith(".png") ? cleaned.substring(0, cleaned.length() - 4) : cleaned;
    }

    private static String textOf(JsonObject components, String key) {
        JsonElement e = components.get(key);
        if (e == null) {
            return null;
        }
        if (e.isJsonObject() && e.getAsJsonObject().has("value")) {
            e = e.getAsJsonObject().get("value");
        }
        return e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** Reads a numeric component either as bare number or as object member {@code member}. */
    private static double valueOf(JsonObject components, String key, String member, double def) {
        JsonElement e = components.get(key);
        if (e == null) {
            return def;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return e.getAsDouble();
        }
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            return JsonUtils.number(o, member, JsonUtils.number(o, "value", def));
        }
        return def;
    }

    private static boolean bool(JsonObject components, String key, boolean def) {
        JsonElement e = components.get(key);
        if (e == null) {
            return def;
        }
        if (e.isJsonObject() && e.getAsJsonObject().has("value")) {
            e = e.getAsJsonObject().get("value");
        }
        return e.isJsonPrimitive() ? e.getAsBoolean() : def;
    }

    private static double clamp(double value, double min, double max, String what, ParseContext ctx) {
        if (Double.isNaN(value) || value < min || value > max) {
            double fixed = Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
            ctx.warn(what + " = " + value + " is outside " + min + ".." + max + "; using " + fixed);
            return fixed;
        }
        return value;
    }
}
