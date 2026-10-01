package com.operator.mypack.content.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.operator.mypack.content.ContentId;
import com.operator.mypack.content.ParseContext;
import com.operator.mypack.content.item.ItemDefinition.AttributeSpec;
import com.operator.mypack.content.item.ItemDefinition.ItemActions;
import com.operator.mypack.content.item.ItemDefinition.Trigger;
import com.operator.mypack.utils.JsonUtils;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses {@code items/*.json}. The file follows the Bedrock item layout ({@code minecraft:item} →
 * {@code description} + {@code components}); everything Paper specific lives under the {@code mypack:} component
 * namespace.
 */
public final class ItemParser {

    private static final Set<String> KNOWN_COMPONENTS = Set.of(
            "minecraft:display_name", "minecraft:icon", "minecraft:max_stack_size", "minecraft:durability",
            "minecraft:glint", "minecraft:hand_equipped", "mypack:base_material", "mypack:lore", "mypack:attributes",
            "mypack:enchantments", "mypack:flags", "mypack:custom_model_data", "mypack:actions", "mypack:java_model",
            "mypack:tags", "mypack:unbreakable", "mypack:rarity");
    private static final Set<String> RARITIES = Set.of("COMMON", "UNCOMMON", "RARE", "EPIC");
    private static final Set<String> OPERATIONS = Set.of("add_value", "add_multiplied_base", "add_multiplied_total");
    private static final Set<String> SLOTS = Set.of("any", "mainhand", "offhand", "hand", "head", "chest", "legs", "feet",
            "armor", "body");
    private static final Pattern MATERIAL = Pattern.compile("[A-Z0-9_]{1,64}");

    private ItemParser() {
    }

    /** @return the definition, or {@code null} when the file is unusable (the reason was reported to {@code ctx}) */
    public static ItemDefinition parse(JsonObject root, ParseContext ctx) {
        JsonObject item = JsonUtils.object(root, "minecraft:item").orElse(null);
        if (item == null) {
            ctx.error("missing \"minecraft:item\" object");
            return null;
        }
        ContentId id = ctx.identifier(JsonUtils.object(item, "description").orElse(new JsonObject()));
        if (id == null) {
            return null;
        }
        JsonObject components = JsonUtils.object(item, "components").orElse(new JsonObject());
        for (String key : components.keySet()) {
            if (!KNOWN_COMPONENTS.contains(key)) {
                ctx.warn("component '" + key + "' is not supported and was ignored");
            }
        }

        String displayName = text(components, "minecraft:display_name");
        if (displayName == null || displayName.isBlank()) {
            displayName = titleCase(id.path());
        }

        String material = text(components, "mypack:base_material");
        material = material == null ? "PAPER" : material.trim().toUpperCase(Locale.ROOT);
        if (!MATERIAL.matcher(material).matches()) {
            ctx.warn("mypack:base_material '" + material + "' is not a valid material name; using PAPER");
            material = "PAPER";
        }

        int maxStack = clamp(intOf(components, "minecraft:max_stack_size", 0), 0, 99, "minecraft:max_stack_size", ctx);
        int maxDurability = 0;
        JsonObject durability = JsonUtils.object(components, "minecraft:durability").orElse(null);
        if (durability != null) {
            maxDurability = clamp(JsonUtils.integer(durability, "max_durability",
                    JsonUtils.integer(durability, "max_durable", 0)), 0, 100_000, "minecraft:durability", ctx);
        } else if (components.has("minecraft:durability")) {
            maxDurability = clamp(intOf(components, "minecraft:durability", 0), 0, 100_000, "minecraft:durability", ctx);
        }

        Boolean glint = components.has("minecraft:glint") ? boolOf(components, "minecraft:glint", false) : null;
        String rarity = text(components, "mypack:rarity");
        if (rarity != null) {
            rarity = rarity.trim().toUpperCase(Locale.ROOT);
            if (!RARITIES.contains(rarity)) {
                ctx.warn("mypack:rarity '" + rarity + "' must be one of " + RARITIES + "; ignored");
                rarity = null;
            }
        }
        Integer customModelData = components.has("mypack:custom_model_data")
                ? Integer.valueOf(intOf(components, "mypack:custom_model_data", 0)) : null;

        String javaModel = text(components, "mypack:java_model");
        if (javaModel != null && (javaModel.contains("..") || javaModel.startsWith("/") || !javaModel.endsWith(".json"))) {
            ctx.warn("mypack:java_model must be a relative .json path inside the pack; ignored");
            javaModel = null;
        }

        return new ItemDefinition(
                id,
                displayName,
                JsonUtils.stringList(components, "mypack:lore"),
                material,
                icon(components, ctx),
                javaModel,
                boolOf(components, "minecraft:hand_equipped", false),
                maxStack,
                maxDurability,
                boolOf(components, "mypack:unbreakable", false),
                glint,
                rarity,
                customModelData,
                attributes(components, ctx),
                enchantments(components, ctx),
                upperCaseList(components, "mypack:flags"),
                JsonUtils.stringList(components, "mypack:tags"),
                actions(components, ctx));
    }

    // ------------------------------------------------------------------ components

    private static String icon(JsonObject components, ParseContext ctx) {
        JsonElement e = components.get("minecraft:icon");
        if (e == null) {
            return null;
        }
        String name = null;
        if (e.isJsonPrimitive()) {
            name = e.getAsString();
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            name = JsonUtils.string(o, "texture", null);
            if (name == null) {
                name = JsonUtils.object(o, "textures").map(t -> JsonUtils.string(t, "default", null)).orElse(null);
            }
        }
        if (name == null || name.isBlank()) {
            ctx.warn("minecraft:icon has no texture name; the item will use the missing texture");
            return null;
        }
        String cleaned = name.trim();
        if (cleaned.startsWith("textures/items/")) {
            cleaned = cleaned.substring("textures/items/".length());
        } else if (cleaned.startsWith("textures/item/")) {
            cleaned = cleaned.substring("textures/item/".length());
        }
        if (cleaned.endsWith(".png")) {
            cleaned = cleaned.substring(0, cleaned.length() - 4);
        }
        if (cleaned.contains("..") || cleaned.startsWith("/")) {
            ctx.warn("minecraft:icon '" + name + "' is not a valid texture name");
            return null;
        }
        return cleaned;
    }

    private static List<AttributeSpec> attributes(JsonObject components, ParseContext ctx) {
        List<AttributeSpec> out = new ArrayList<>();
        for (JsonElement element : JsonUtils.array(components, "mypack:attributes").orElse(new JsonArray())) {
            if (!element.isJsonObject()) {
                ctx.warn("mypack:attributes entries must be objects");
                continue;
            }
            JsonObject o = element.getAsJsonObject();
            String attribute = JsonUtils.string(o, "attribute", "").trim().toLowerCase(Locale.ROOT);
            attribute = attribute.replaceFirst("^minecraft:", "").replaceFirst("^(generic|player|zombie)[.]", "");
            if (attribute.isEmpty()) {
                ctx.warn("an attribute modifier has no \"attribute\"; skipped");
                continue;
            }
            String operation = JsonUtils.string(o, "operation", "add_value").toLowerCase(Locale.ROOT);
            if (!OPERATIONS.contains(operation)) {
                ctx.warn("attribute operation '" + operation + "' must be one of " + OPERATIONS + "; using add_value");
                operation = "add_value";
            }
            String slot = JsonUtils.string(o, "slot", "any").toLowerCase(Locale.ROOT);
            if (!SLOTS.contains(slot)) {
                ctx.warn("attribute slot '" + slot + "' must be one of " + SLOTS + "; using any");
                slot = "any";
            }
            double amount = JsonUtils.number(o, "amount", 0.0D);
            if (Double.isNaN(amount) || Double.isInfinite(amount)) {
                ctx.warn("attribute amount is not a finite number; skipped");
                continue;
            }
            out.add(new AttributeSpec(attribute, amount, operation, slot));
        }
        return List.copyOf(out);
    }

    private static Map<String, Integer> enchantments(JsonObject components, ParseContext ctx) {
        Map<String, Integer> out = new LinkedHashMap<>();
        JsonObject o = JsonUtils.object(components, "mypack:enchantments").orElse(null);
        if (o == null) {
            return out;
        }
        for (String key : o.keySet()) {
            int level = clamp(JsonUtils.integer(o, key, 1), 1, 255, "enchantment " + key, ctx);
            out.put(key.trim().toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", ""), level);
        }
        return out;
    }

    private static ItemActions actions(JsonObject components, ParseContext ctx) {
        JsonObject o = JsonUtils.object(components, "mypack:actions").orElse(null);
        if (o == null) {
            return ItemActions.NONE;
        }
        int cooldown = clamp(JsonUtils.integer(o, "cooldown_ticks", 0), 0, 72_000, "mypack:actions.cooldown_ticks", ctx);
        Map<Trigger, List<ItemAction>> scripts = new EnumMap<>(Trigger.class);
        for (Trigger trigger : Trigger.values()) {
            JsonArray array = JsonUtils.array(o, trigger.jsonKey()).orElse(null);
            if (array == null) {
                continue;
            }
            List<ItemAction> list = new ArrayList<>();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    ctx.warn(trigger.jsonKey() + " entries must be objects");
                    continue;
                }
                ItemAction action = action(element.getAsJsonObject(), trigger, ctx);
                if (action != null) {
                    list.add(action);
                }
            }
            if (!list.isEmpty()) {
                scripts.put(trigger, List.copyOf(list));
            }
        }
        return new ItemActions(cooldown, scripts);
    }

    private static ItemAction action(JsonObject o, Trigger trigger, ParseContext ctx) {
        String type = JsonUtils.string(o, "type", "").toLowerCase(Locale.ROOT);
        String where = trigger.jsonKey() + "." + type;
        switch (type) {
            case "sound": {
                String sound = JsonUtils.string(o, "sound", "");
                if (sound.isBlank()) {
                    ctx.warn(where + " needs a \"sound\"; skipped");
                    return null;
                }
                return new ItemAction.Sound(sound.trim(),
                        (float) clampD(JsonUtils.number(o, "volume", 1.0D), 0.0D, 10.0D),
                        (float) clampD(JsonUtils.number(o, "pitch", 1.0D), 0.5D, 2.0D));
            }
            case "particle": {
                String particle = JsonUtils.string(o, "particle", "");
                if (particle.isBlank()) {
                    ctx.warn(where + " needs a \"particle\"; skipped");
                    return null;
                }
                double[] offset = JsonUtils.vector(o, "offset", 3, new double[]{0, 0, 0});
                return new ItemAction.Particle(particle.trim(), clamp(JsonUtils.integer(o, "count", 1), 1, 1000, where + ".count", ctx),
                        offset[0], offset[1], offset[2], clampD(JsonUtils.number(o, "speed", 0.0D), 0.0D, 10.0D));
            }
            case "message": {
                String text = JsonUtils.string(o, "text", "");
                if (text.isBlank()) {
                    ctx.warn(where + " needs \"text\"; skipped");
                    return null;
                }
                return new ItemAction.Message(text);
            }
            case "command": {
                String command = JsonUtils.string(o, "command", "").trim();
                if (command.startsWith("/")) {
                    command = command.substring(1);
                }
                if (command.isEmpty()) {
                    ctx.warn(where + " needs a \"command\"; skipped");
                    return null;
                }
                String executor = JsonUtils.string(o, "executor", ItemAction.Command.PLAYER).toLowerCase(Locale.ROOT);
                if (!executor.equals(ItemAction.Command.CONSOLE) && !executor.equals(ItemAction.Command.PLAYER)) {
                    ctx.warn(where + ": executor must be 'console' or 'player'; using 'player'");
                    executor = ItemAction.Command.PLAYER;
                }
                return new ItemAction.Command(executor, command);
            }
            case "potion_effect": {
                String effect = JsonUtils.string(o, "effect", "");
                if (effect.isBlank()) {
                    ctx.warn(where + " needs an \"effect\"; skipped");
                    return null;
                }
                return new ItemAction.PotionEffect(effect.trim(),
                        clamp(JsonUtils.integer(o, "duration_ticks", 100), 1, 1_000_000, where + ".duration_ticks", ctx),
                        clamp(JsonUtils.integer(o, "amplifier", 0), 0, 255, where + ".amplifier", ctx));
            }
            case "damage_ray": {
                String trail = JsonUtils.string(o, "trail_particle", "");
                return new ItemAction.DamageRay(
                        clampD(JsonUtils.number(o, "range", 20.0D), 1.0D, 200.0D),
                        clampD(JsonUtils.number(o, "damage", 5.0D), 0.0D, 1000.0D),
                        clampD(JsonUtils.number(o, "ray_size", 0.3D), 0.0D, 5.0D),
                        clampD(JsonUtils.number(o, "knockback", 0.0D), 0.0D, 10.0D),
                        trail.isBlank() ? null : trail.trim());
            }
            case "consume":
                return new ItemAction.Consume(clamp(JsonUtils.integer(o, "amount", 1), 1, 64, where + ".amount", ctx));
            case "durability":
                return new ItemAction.Durability(clamp(JsonUtils.integer(o, "amount", 1), 1, 1000, where + ".amount", ctx));
            default:
                ctx.warn(trigger.jsonKey() + ": unknown action type '" + type + "' skipped");
                return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Reads a component that is either a bare value or an object with a {@code "value"} member. */
    private static JsonElement unwrap(JsonObject components, String key) {
        JsonElement e = components.get(key);
        if (e != null && e.isJsonObject() && e.getAsJsonObject().has("value")) {
            return e.getAsJsonObject().get("value");
        }
        return e;
    }

    private static String text(JsonObject components, String key) {
        JsonElement e = unwrap(components, key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static int intOf(JsonObject components, String key, int def) {
        JsonElement e = unwrap(components, key);
        if (e != null && e.isJsonPrimitive()) {
            try {
                return (int) Math.round(e.getAsDouble());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static boolean boolOf(JsonObject components, String key, boolean def) {
        JsonElement e = unwrap(components, key);
        if (e != null && e.isJsonPrimitive()) {
            return e.getAsBoolean();
        }
        return def;
    }

    private static List<String> upperCaseList(JsonObject components, String key) {
        List<String> out = new ArrayList<>();
        for (String s : JsonUtils.stringList(components, key)) {
            out.add(s.trim().toUpperCase(Locale.ROOT));
        }
        return List.copyOf(out);
    }

    private static int clamp(int value, int min, int max, String what, ParseContext ctx) {
        if (value < min || value > max) {
            int fixed = Math.max(min, Math.min(max, value));
            ctx.warn(what + " = " + value + " is outside " + min + ".." + max + "; using " + fixed);
            return fixed;
        }
        return value;
    }

    private static double clampD(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    /** {@code aether_blaster} → {@code Aether Blaster}. */
    static String titleCase(String path) {
        String last = path.substring(path.lastIndexOf('/') + 1);
        StringBuilder sb = new StringBuilder();
        for (String word : last.split("[_\\-.]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.length() == 0 ? last : sb.toString();
    }
}
