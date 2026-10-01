package com.operator.mypack.content.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.operator.mypack.content.ContentId;
import com.operator.mypack.content.ParseContext;
import com.operator.mypack.content.recipe.RecipeDefinition.CookingType;
import com.operator.mypack.content.recipe.RecipeDefinition.Ingredient;
import com.operator.mypack.content.recipe.RecipeDefinition.Result;
import com.operator.mypack.utils.JsonUtils;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses {@code recipes/*.json} in the Bedrock layout: {@code minecraft:recipe_shaped},
 * {@code minecraft:recipe_shapeless} and {@code minecraft:recipe_furnace}.
 */
public final class RecipeParser {

    private RecipeParser() {
    }

    public static RecipeDefinition parse(JsonObject root, ParseContext ctx) {
        JsonObject body = JsonUtils.object(root, "minecraft:recipe_shaped").orElse(null);
        if (body != null) {
            return shaped(body, ctx);
        }
        body = JsonUtils.object(root, "minecraft:recipe_shapeless").orElse(null);
        if (body != null) {
            return shapeless(body, ctx);
        }
        body = JsonUtils.object(root, "minecraft:recipe_furnace").orElse(null);
        if (body != null) {
            return cooking(body, ctx);
        }
        ctx.error("no supported recipe type found (minecraft:recipe_shaped, _shapeless or _furnace)");
        return null;
    }

    private static RecipeDefinition shaped(JsonObject body, ParseContext ctx) {
        ContentId id = ctx.identifier(JsonUtils.object(body, "description").orElse(new JsonObject()));
        Result result = result(body.get("result"), ctx);
        if (id == null || result == null) {
            return null;
        }
        List<String> pattern = JsonUtils.stringList(body, "pattern");
        if (pattern.isEmpty() || pattern.size() > 3) {
            ctx.error("pattern must have 1 to 3 rows");
            return null;
        }
        int width = pattern.get(0).length();
        for (String row : pattern) {
            if (row.isEmpty() || row.length() > 3) {
                ctx.error("every pattern row must be 1 to 3 characters wide");
                return null;
            }
            if (row.length() != width) {
                ctx.error("all pattern rows must have the same width (use spaces for empty slots)");
                return null;
            }
        }
        JsonObject keyObject = JsonUtils.object(body, "key").orElse(new JsonObject());
        Map<Character, Ingredient> key = new LinkedHashMap<>();
        for (String k : keyObject.keySet()) {
            if (k.length() != 1) {
                ctx.warn("key '" + k + "' must be a single character; ignored");
                continue;
            }
            Ingredient ingredient = ingredient(keyObject.get(k), ctx);
            if (ingredient != null) {
                key.put(k.charAt(0), ingredient);
            }
        }
        boolean anyIngredient = false;
        for (String row : pattern) {
            for (char c : row.toCharArray()) {
                if (c == ' ') {
                    continue;
                }
                anyIngredient = true;
                if (!key.containsKey(c)) {
                    ctx.error("pattern uses '" + c + "' which has no (valid) entry in \"key\"");
                    return null;
                }
            }
        }
        if (!anyIngredient) {
            ctx.error("pattern has no ingredients");
            return null;
        }
        return new RecipeDefinition.Shaped(id, List.copyOf(pattern), Map.copyOf(key), result,
                JsonUtils.string(body, "group", ""));
    }

    private static RecipeDefinition shapeless(JsonObject body, ParseContext ctx) {
        ContentId id = ctx.identifier(JsonUtils.object(body, "description").orElse(new JsonObject()));
        Result result = result(body.get("result"), ctx);
        if (id == null || result == null) {
            return null;
        }
        List<Ingredient> ingredients = new ArrayList<>();
        for (JsonElement element : JsonUtils.array(body, "ingredients").orElse(new JsonArray())) {
            Ingredient ingredient = ingredient(element, ctx);
            if (ingredient == null) {
                return null;
            }
            // Bedrock allows "count" on shapeless ingredients: repeat the ingredient.
            int count = element.isJsonObject() ? Math.max(1, JsonUtils.integer(element.getAsJsonObject(), "count", 1)) : 1;
            for (int i = 0; i < count; i++) {
                ingredients.add(ingredient);
            }
        }
        if (ingredients.isEmpty() || ingredients.size() > 9) {
            ctx.error("a shapeless recipe needs 1 to 9 ingredients");
            return null;
        }
        return new RecipeDefinition.Shapeless(id, List.copyOf(ingredients), result, JsonUtils.string(body, "group", ""));
    }

    private static RecipeDefinition cooking(JsonObject body, ParseContext ctx) {
        ContentId id = ctx.identifier(JsonUtils.object(body, "description").orElse(new JsonObject()));
        Ingredient input = ingredient(body.get("input"), ctx);
        Result result = result(body.get("output") != null ? body.get("output") : body.get("result"), ctx);
        if (id == null || input == null || result == null) {
            return null;
        }
        Set<CookingType> types = EnumSet.noneOf(CookingType.class);
        for (String tag : JsonUtils.stringList(body, "tags")) {
            switch (tag.toLowerCase(Locale.ROOT)) {
                case "furnace" -> types.add(CookingType.FURNACE);
                case "blast_furnace" -> types.add(CookingType.BLASTING);
                case "smoker" -> types.add(CookingType.SMOKING);
                case "campfire" -> types.add(CookingType.CAMPFIRE);
                default -> ctx.warn("unknown cooking station tag '" + tag + "' ignored");
            }
        }
        if (types.isEmpty()) {
            types.add(CookingType.FURNACE);
        }
        double experience = Math.max(0.0D, Math.min(100.0D, JsonUtils.number(body, "experience", 0.0D)));
        int cookTime = Math.max(0, Math.min(72_000, JsonUtils.integer(body, "cooking_time", 0)));
        return new RecipeDefinition.Cooking(id, Set.copyOf(types), input, result, (float) experience, cookTime);
    }

    // ------------------------------------------------------------------ pieces

    private static Ingredient ingredient(JsonElement element, ParseContext ctx) {
        if (element == null || element.isJsonNull()) {
            ctx.error("missing ingredient");
            return null;
        }
        String item = null;
        String tag = null;
        if (element.isJsonPrimitive()) {
            item = element.getAsString();
        } else if (element.isJsonObject()) {
            JsonObject o = element.getAsJsonObject();
            item = JsonUtils.string(o, "item", null);
            tag = JsonUtils.string(o, "tag", null);
        }
        if (tag != null && !tag.isBlank()) {
            String normalized = normalizeId(tag, ctx);
            return normalized == null ? null : Ingredient.ofTag(normalized);
        }
        if (item == null || item.isBlank()) {
            ctx.error("ingredient needs an \"item\" or a \"tag\"");
            return null;
        }
        String normalized = normalizeId(item, ctx);
        return normalized == null ? null : Ingredient.ofItem(normalized);
    }

    private static Result result(JsonElement element, ParseContext ctx) {
        if (element == null || element.isJsonNull()) {
            ctx.error("missing result");
            return null;
        }
        String item;
        int count = 1;
        if (element.isJsonPrimitive()) {
            item = element.getAsString();
        } else if (element.isJsonObject()) {
            JsonObject o = element.getAsJsonObject();
            item = JsonUtils.string(o, "item", null);
            count = JsonUtils.integer(o, "count", 1);
        } else {
            ctx.error("result must be an item identifier or an object");
            return null;
        }
        if (item == null || item.isBlank()) {
            ctx.error("result has no \"item\"");
            return null;
        }
        if (count < 1 || count > 64) {
            ctx.warn("result count " + count + " is outside 1..64; using " + Math.max(1, Math.min(64, count)));
            count = Math.max(1, Math.min(64, count));
        }
        String normalized = normalizeId(item, ctx);
        return normalized == null ? null : new Result(normalized, count);
    }

    /** Bare names are vanilla ({@code diamond} → {@code minecraft:diamond}); custom items are always namespaced. */
    private static String normalizeId(String raw, ParseContext ctx) {
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("#")) {
            text = text.substring(1);
        }
        try {
            return ContentId.parse(text, "minecraft").full();
        } catch (IllegalArgumentException e) {
            ctx.error("'" + raw + "' is not a valid identifier: " + e.getMessage());
            return null;
        }
    }
}
