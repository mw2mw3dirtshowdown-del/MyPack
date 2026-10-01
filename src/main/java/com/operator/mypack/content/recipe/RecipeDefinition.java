package com.operator.mypack.content.recipe;

import com.operator.mypack.content.ContentId;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** A crafting or cooking recipe declared by a pack. Ingredients and results reference items by full identifier. */
public sealed interface RecipeDefinition {

    ContentId id();

    Result result();

    /** Either an item ({@code minecraft:diamond}, {@code aether:core_shard}) or a tag ({@code minecraft:planks}). */
    record Ingredient(String item, String tag) {
        public boolean isTag() {
            return tag != null;
        }

        public static Ingredient ofItem(String item) {
            return new Ingredient(item, null);
        }

        public static Ingredient ofTag(String tag) {
            return new Ingredient(null, tag);
        }
    }

    /** Result item and stack size. */
    record Result(String item, int count) {
    }

    /** Pattern recipe for the crafting table (up to 3x3). Spaces in a pattern row are empty slots. */
    record Shaped(ContentId id, List<String> pattern, Map<Character, Ingredient> key, Result result, String group)
            implements RecipeDefinition {
    }

    /** Order independent recipe for the crafting table (up to 9 ingredients). */
    record Shapeless(ContentId id, List<Ingredient> ingredients, Result result, String group)
            implements RecipeDefinition {
    }

    /** Which block cooks the recipe. */
    enum CookingType {
        FURNACE(200), BLASTING(100), SMOKING(100), CAMPFIRE(600);

        private final int defaultCookTime;

        CookingType(int defaultCookTime) {
            this.defaultCookTime = defaultCookTime;
        }

        public int defaultCookTime() {
            return defaultCookTime;
        }
    }

    /** Smelting-style recipe; one Bukkit recipe is registered per entry of {@code types}. */
    record Cooking(ContentId id, Set<CookingType> types, Ingredient input, Result result, float experience,
                   int cookTimeTicks) implements RecipeDefinition {
    }
}
