package com.operator.mypack.content;

import com.operator.mypack.content.recipe.RecipeDefinition;
import com.operator.mypack.content.recipe.RecipeDefinition.CookingType;
import com.operator.mypack.content.recipe.RecipeParser;
import com.operator.mypack.pack.Issues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.operator.mypack.content.TestJson.ctx;
import static com.operator.mypack.content.TestJson.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeParserTest {

    @Test
    @DisplayName("shaped recipe with vanilla and custom ingredients")
    void shaped() {
        Issues issues = new Issues();
        RecipeDefinition r = RecipeParser.parse(obj("""
                {"minecraft:recipe_shaped": {
                  "description": {"identifier": "aether:blaster_recipe"},
                  "tags": ["crafting_table"],
                  "pattern": [" G ", " R ", " S "],
                  "key": {"G": {"item": "minecraft:gold_ingot"}, "R": {"item": "aether:core_shard"}, "S": "stick"},
                  "result": {"item": "aether:aether_blaster", "count": 1}
                }}
                """), ctx(issues));
        RecipeDefinition.Shaped shaped = assertInstanceOf(RecipeDefinition.Shaped.class, r);
        assertEquals("aether:blaster_recipe", shaped.id().full());
        assertEquals(3, shaped.pattern().size());
        assertEquals("aether:core_shard", shaped.key().get('R').item());
        assertEquals("minecraft:stick", shaped.key().get('S').item(), "bare names are vanilla");
        assertEquals("aether:aether_blaster", shaped.result().item());
        assertTrue(issues.isEmpty(), issues.all().toString());
    }

    @Test
    @DisplayName("shaped recipes are validated: width, missing keys, empty patterns")
    void shapedValidation() {
        String[] bad = {
                // rows of different width
                "{\"minecraft:recipe_shaped\":{\"description\":{\"identifier\":\"aether:a\"},\"pattern\":[\"AB\",\"A\"],\"key\":{\"A\":{\"item\":\"stick\"},\"B\":{\"item\":\"stick\"}},\"result\":{\"item\":\"stick\"}}}",
                // pattern char without key
                "{\"minecraft:recipe_shaped\":{\"description\":{\"identifier\":\"aether:a\"},\"pattern\":[\"AB\"],\"key\":{\"A\":{\"item\":\"stick\"}},\"result\":{\"item\":\"stick\"}}}",
                // too many rows
                "{\"minecraft:recipe_shaped\":{\"description\":{\"identifier\":\"aether:a\"},\"pattern\":[\"A\",\"A\",\"A\",\"A\"],\"key\":{\"A\":{\"item\":\"stick\"}},\"result\":{\"item\":\"stick\"}}}",
                // nothing but spaces
                "{\"minecraft:recipe_shaped\":{\"description\":{\"identifier\":\"aether:a\"},\"pattern\":[\"  \"],\"key\":{},\"result\":{\"item\":\"stick\"}}}",
                // no result
                "{\"minecraft:recipe_shaped\":{\"description\":{\"identifier\":\"aether:a\"},\"pattern\":[\"A\"],\"key\":{\"A\":{\"item\":\"stick\"}}}}"
        };
        for (String json : bad) {
            Issues issues = new Issues();
            assertNull(RecipeParser.parse(obj(json), ctx(issues)), json);
            assertTrue(issues.hasErrors(), json);
        }
    }

    @Test
    @DisplayName("shapeless recipes expand ingredient counts and accept tags")
    void shapeless() {
        RecipeDefinition r = RecipeParser.parse(obj("""
                {"minecraft:recipe_shapeless": {
                  "description": {"identifier": "aether:mix"},
                  "ingredients": [{"item": "aether:shard", "count": 3}, {"tag": "minecraft:planks"}],
                  "result": {"item": "aether:dust", "count": 4}
                }}
                """), ctx(new Issues()));
        RecipeDefinition.Shapeless s = assertInstanceOf(RecipeDefinition.Shapeless.class, r);
        assertEquals(4, s.ingredients().size());
        assertTrue(s.ingredients().get(3).isTag());
        assertEquals("minecraft:planks", s.ingredients().get(3).tag());
        assertEquals(4, s.result().count());
    }

    @Test
    @DisplayName("furnace recipes map station tags to cooking types")
    void cooking() {
        RecipeDefinition r = RecipeParser.parse(obj("""
                {"minecraft:recipe_furnace": {
                  "description": {"identifier": "aether:smelt"},
                  "tags": ["furnace", "blast_furnace", "bogus"],
                  "input": "aether:raw_core",
                  "output": "aether:core_ingot"
                }}
                """), ctx(new Issues()));
        RecipeDefinition.Cooking c = assertInstanceOf(RecipeDefinition.Cooking.class, r);
        assertEquals(java.util.Set.of(CookingType.FURNACE, CookingType.BLASTING), c.types());
        assertEquals("aether:raw_core", c.input().item());
        assertEquals("aether:core_ingot", c.result().item());
        assertEquals(0, c.cookTimeTicks(), "0 means 'use the station default'");
        assertEquals(200, CookingType.FURNACE.defaultCookTime());
    }

    @Test
    @DisplayName("unknown recipe types are an error, result counts are clamped")
    void misc() {
        Issues issues = new Issues();
        assertNull(RecipeParser.parse(obj("{\"minecraft:recipe_brewing_mix\":{}}"), ctx(issues)));
        assertTrue(issues.hasErrors());

        Issues clampIssues = new Issues();
        RecipeDefinition r = RecipeParser.parse(obj("""
                {"minecraft:recipe_shapeless": {"description": {"identifier": "aether:big"},
                  "ingredients": ["stick"], "result": {"item": "stick", "count": 500}}}
                """), ctx(clampIssues));
        assertEquals(64, r.result().count());
        assertFalse(clampIssues.hasErrors());
        assertEquals(1, clampIssues.count(Issues.Level.WARN));
    }
}
