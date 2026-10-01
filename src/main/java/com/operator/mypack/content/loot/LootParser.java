package com.operator.mypack.content.loot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.operator.mypack.content.ContentId;
import com.operator.mypack.content.ParseContext;
import com.operator.mypack.content.loot.LootTable.Condition;
import com.operator.mypack.content.loot.LootTable.Entry;
import com.operator.mypack.content.loot.LootTable.EntryType;
import com.operator.mypack.content.loot.LootTable.LootFunction;
import com.operator.mypack.content.loot.LootTable.Pool;
import com.operator.mypack.content.loot.LootTable.Range;
import com.operator.mypack.utils.JsonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses {@code loot_tables/**.json}; the table id is derived from the file path by the loader. */
public final class LootParser {

    private LootParser() {
    }

    public static LootTable parse(JsonObject root, ContentId id, ParseContext ctx) {
        JsonArray poolsJson = JsonUtils.array(root, "pools").orElse(null);
        if (poolsJson == null || poolsJson.isEmpty()) {
            ctx.error("loot table needs a non-empty \"pools\" array");
            return null;
        }
        List<Pool> pools = new ArrayList<>();
        for (JsonElement element : poolsJson) {
            if (!element.isJsonObject()) {
                ctx.warn("pool is not an object; skipped");
                continue;
            }
            Pool pool = pool(element.getAsJsonObject(), ctx);
            if (pool != null) {
                pools.add(pool);
            }
        }
        if (pools.isEmpty()) {
            ctx.error("loot table has no usable pool");
            return null;
        }
        return new LootTable(id, List.copyOf(pools));
    }

    private static Pool pool(JsonObject o, ParseContext ctx) {
        Range rolls = range(o.get("rolls"), Range.of(1));
        Range bonus = range(o.get("bonus_rolls"), Range.of(0));
        List<Entry> entries = new ArrayList<>();
        for (JsonElement element : JsonUtils.array(o, "entries").orElse(new JsonArray())) {
            if (!element.isJsonObject()) {
                ctx.warn("entry is not an object; skipped");
                continue;
            }
            Entry entry = entry(element.getAsJsonObject(), ctx);
            if (entry != null) {
                entries.add(entry);
            }
        }
        if (entries.isEmpty()) {
            ctx.warn("pool has no usable entries; skipped");
            return null;
        }
        return new Pool(rolls, bonus, conditions(o, ctx), List.copyOf(entries));
    }

    private static Entry entry(JsonObject o, ParseContext ctx) {
        String typeText = JsonUtils.string(o, "type", "item").toLowerCase(Locale.ROOT);
        EntryType type;
        switch (typeText) {
            case "item" -> type = EntryType.ITEM;
            case "loot_table" -> type = EntryType.TABLE;
            case "empty" -> type = EntryType.EMPTY;
            default -> {
                ctx.warn("entry type '" + typeText + "' is not supported; skipped");
                return null;
            }
        }
        int weight = Math.max(1, JsonUtils.integer(o, "weight", 1));
        String name = JsonUtils.string(o, "name", "");
        if (type != EntryType.EMPTY && name.isBlank()) {
            ctx.warn("entry of type '" + typeText + "' needs a \"name\"; skipped");
            return null;
        }
        if (type == EntryType.ITEM) {
            name = normalizeItem(name, ctx);
        } else if (type == EntryType.TABLE) {
            name = normalizeTable(name, ctx);
        }
        if (type != EntryType.EMPTY && name == null) {
            return null;
        }
        return new Entry(type, name == null ? "" : name, weight, functions(o, ctx), conditions(o, ctx));
    }

    private static List<LootFunction> functions(JsonObject o, ParseContext ctx) {
        List<LootFunction> out = new ArrayList<>();
        for (JsonElement element : JsonUtils.array(o, "functions").orElse(new JsonArray())) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject f = element.getAsJsonObject();
            String name = JsonUtils.string(f, "function", "").toLowerCase(Locale.ROOT);
            switch (name) {
                case "set_count" -> out.add(new LootFunction.SetCount(range(f.get("count"), Range.of(1))));
                case "looting_enchant" -> out.add(new LootFunction.LootingEnchant(range(f.get("count"), Range.of(1)),
                        Math.max(0, JsonUtils.integer(f, "limit", 0))));
                case "set_name" -> {
                    String text = JsonUtils.string(f, "name", "");
                    if (!text.isBlank()) {
                        out.add(new LootFunction.SetName(text));
                    }
                }
                case "set_lore" -> {
                    List<String> lore = JsonUtils.stringList(f, "lore");
                    if (!lore.isEmpty()) {
                        out.add(new LootFunction.SetLore(List.copyOf(lore)));
                    }
                }
                default -> ctx.warn("loot function '" + name + "' is not supported and was ignored");
            }
        }
        return List.copyOf(out);
    }

    private static List<Condition> conditions(JsonObject o, ParseContext ctx) {
        List<Condition> out = new ArrayList<>();
        for (JsonElement element : JsonUtils.array(o, "conditions").orElse(new JsonArray())) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject c = element.getAsJsonObject();
            String name = JsonUtils.string(c, "condition", "").toLowerCase(Locale.ROOT);
            switch (name) {
                case "killed_by_player" -> out.add(new Condition.KilledByPlayer());
                case "random_chance" -> out.add(new Condition.RandomChance(clamp01(JsonUtils.number(c, "chance", 1.0D))));
                case "random_chance_with_looting" -> out.add(new Condition.RandomChanceWithLooting(
                        clamp01(JsonUtils.number(c, "chance", 1.0D)), Math.max(0.0D, JsonUtils.number(c, "looting_multiplier", 0.0D))));
                default -> {
                    ctx.warn("loot condition '" + name + "' is not supported; the guarded entry will never drop");
                    out.add(new Condition.Never());
                }
            }
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ helpers

    /** Accepts {@code 3}, {@code {"min":1,"max":3}} and {@code [1,3]}. */
    static Range range(JsonElement e, Range def) {
        if (e == null || e.isJsonNull()) {
            return def;
        }
        try {
            if (e.isJsonPrimitive()) {
                return Range.of(e.getAsDouble());
            }
            if (e.isJsonArray() && e.getAsJsonArray().size() == 2) {
                return new Range(e.getAsJsonArray().get(0).getAsDouble(), e.getAsJsonArray().get(1).getAsDouble());
            }
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                double min = JsonUtils.number(o, "min", Double.NaN);
                double max = JsonUtils.number(o, "max", Double.NaN);
                if (Double.isNaN(min) && Double.isNaN(max)) {
                    return def;
                }
                return new Range(Double.isNaN(min) ? max : min, Double.isNaN(max) ? min : max);
            }
        } catch (RuntimeException ignored) {
            // falls through to the default
        }
        return def;
    }

    private static String normalizeItem(String raw, ParseContext ctx) {
        try {
            return ContentId.parse(raw.trim().toLowerCase(Locale.ROOT), "minecraft").full();
        } catch (IllegalArgumentException ex) {
            ctx.warn("item '" + raw + "' is not a valid identifier; entry skipped");
            return null;
        }
    }

    /** Accepts {@code ns:path} and the Bedrock file form {@code loot_tables/entities/x.json}. */
    private static String normalizeTable(String raw, ParseContext ctx) {
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("loot_tables/")) {
            text = text.substring("loot_tables/".length());
        }
        if (text.endsWith(".json")) {
            text = text.substring(0, text.length() - ".json".length());
        }
        try {
            return ContentId.parse(text, ctx.namespace()).full();
        } catch (IllegalArgumentException ex) {
            ctx.warn("loot table '" + raw + "' is not a valid identifier; entry skipped");
            return null;
        }
    }

    private static double clamp01(double v) {
        return Double.isNaN(v) ? 0.0D : Math.max(0.0D, Math.min(1.0D, v));
    }
}
