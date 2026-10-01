package com.operator.mypack.content;

import com.operator.mypack.content.loot.LootEvaluator;
import com.operator.mypack.content.loot.LootEvaluator.Context;
import com.operator.mypack.content.loot.LootEvaluator.Drop;
import com.operator.mypack.content.loot.LootParser;
import com.operator.mypack.content.loot.LootTable;
import com.operator.mypack.pack.Issues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.operator.mypack.content.TestJson.ctx;
import static com.operator.mypack.content.TestJson.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LootTest {

    private static final ContentId ID = new ContentId("aether", "entities/wyvern");

    private static LootTable parse(String json) {
        Issues issues = new Issues();
        LootTable table = LootParser.parse(obj(json), ID, ctx(issues));
        assertNotNull(table, issues.all().toString());
        return table;
    }

    @Test
    @DisplayName("entries are chosen by weight: a 1:3:6 table converges to 10% / 30% / 60%")
    void weightsAreRelative() {
        LootTable table = parse("""
                {"pools": [{"rolls": 1, "entries": [
                   {"type": "item", "name": "minecraft:diamond", "weight": 1},
                   {"type": "item", "name": "minecraft:gold_ingot", "weight": 3},
                   {"type": "item", "name": "minecraft:bone", "weight": 6}]}]}
                """);
        Map<String, Integer> counts = new HashMap<>();
        Random rng = new Random(1234);
        int trials = 20_000;
        for (int i = 0; i < trials; i++) {
            for (Drop d : LootEvaluator.roll(table, Context.NONE, rng, null)) {
                counts.merge(d.item(), 1, Integer::sum);
            }
        }
        assertEquals(0.10, counts.get("minecraft:diamond") / (double) trials, 0.015);
        assertEquals(0.30, counts.get("minecraft:gold_ingot") / (double) trials, 0.015);
        assertEquals(0.60, counts.get("minecraft:bone") / (double) trials, 0.015);
    }

    @Test
    @DisplayName("set_count stays inside its range and covers both ends")
    void setCountRange() {
        LootTable table = parse("""
                {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "aether:scale",
                   "functions": [{"function": "set_count", "count": {"min": 2, "max": 4}}]}]}]}
                """);
        Random rng = new Random(7);
        int min = 99;
        int max = 0;
        for (int i = 0; i < 2000; i++) {
            Drop d = LootEvaluator.roll(table, Context.NONE, rng, null).get(0);
            min = Math.min(min, d.amount());
            max = Math.max(max, d.amount());
        }
        assertEquals(2, min);
        assertEquals(4, max);
    }

    @Test
    @DisplayName("killed_by_player gates a pool; looting adds items per level")
    void conditionsAndLooting() {
        LootTable table = parse("""
                {"pools": [{"rolls": 1, "conditions": [{"condition": "killed_by_player"}], "entries": [
                   {"type": "item", "name": "aether:heart",
                    "functions": [{"function": "set_count", "count": 1},
                                  {"function": "looting_enchant", "count": {"min": 1, "max": 1}}]}]}]}
                """);
        Random rng = new Random(1);
        assertTrue(LootEvaluator.roll(table, new Context(false, 3), rng, null).isEmpty(), "not killed by a player");
        assertEquals(1, LootEvaluator.roll(table, new Context(true, 0), rng, null).get(0).amount());
        assertEquals(4, LootEvaluator.roll(table, new Context(true, 3), rng, null).get(0).amount(), "1 base + 3 levels");
    }

    @Test
    @DisplayName("random_chance is honoured statistically")
    void randomChance() {
        LootTable table = parse("""
                {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "aether:rare",
                   "conditions": [{"condition": "random_chance", "chance": 0.25}]}]}]}
                """);
        Random rng = new Random(99);
        int hits = 0;
        int trials = 20_000;
        for (int i = 0; i < trials; i++) {
            hits += LootEvaluator.roll(table, Context.NONE, rng, null).size();
        }
        assertEquals(0.25, hits / (double) trials, 0.015);
    }

    @Test
    @DisplayName("empty entries produce nothing; unknown conditions never pass")
    void emptyAndUnknown() {
        LootTable empty = parse("{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"empty\",\"weight\":5}]}]}");
        assertTrue(LootEvaluator.roll(empty, Context.NONE, new Random(1), null).isEmpty());

        Issues issues = new Issues();
        LootTable guarded = LootParser.parse(obj("""
                {"pools":[{"rolls":1,"entries":[{"type":"item","name":"stick",
                  "conditions":[{"condition":"entity_properties","properties":{}}]}]}]}
                """), ID, ctx(issues));
        assertNotNull(guarded);
        assertTrue(LootEvaluator.roll(guarded, Context.NONE, new Random(1), null).isEmpty(),
                "an unsupported restriction must not make loot easier");
        assertEquals(1, issues.count(Issues.Level.WARN));
    }

    @Test
    @DisplayName("nested tables resolve through the lookup and are accepted in Bedrock path form")
    void nestedTables() {
        LootTable inner = new LootTable(new ContentId("aether", "common"), List.of(
                new LootTable.Pool(LootTable.Range.of(2), LootTable.Range.of(0), List.of(), List.of(
                        new LootTable.Entry(LootTable.EntryType.ITEM, "minecraft:bone", 1, List.of(), List.of())))));
        LootTable outer = parse("""
                {"pools": [{"rolls": 1, "entries": [{"type": "loot_table", "name": "loot_tables/common.json"}]}]}
                """);
        List<Drop> drops = LootEvaluator.roll(outer, Context.NONE, new Random(1),
                id -> id.equals("aether:common") ? inner : null);
        assertEquals(2, drops.size());
        assertEquals("minecraft:bone", drops.get(0).item());
        assertTrue(LootEvaluator.roll(outer, Context.NONE, new Random(1), id -> null).isEmpty(), "unknown table is ignored");
    }

    @Test
    @DisplayName("a table that references itself terminates")
    void recursionIsBounded() {
        LootTable self = parse("""
                {"pools": [{"rolls": 3, "entries": [
                   {"type": "loot_table", "name": "aether:entities/wyvern"},
                   {"type": "item", "name": "minecraft:bone"}]}]}
                """);
        List<Drop> drops = LootEvaluator.roll(self, Context.NONE, new Random(5), id -> self);
        assertTrue(drops.size() <= LootEvaluator.MAX_DROPS);
    }

    @Test
    @DisplayName("fixed ranges accept number, object and array forms; names and lore overrides pass through")
    void rangeFormsAndOverrides() {
        LootTable table = parse("""
                {"pools": [{"rolls": [2, 2], "entries": [{"type": "item", "name": "aether:blade", "weight": 0,
                   "functions": [{"function": "set_name", "name": "<red>Cursed Blade"}, {"function": "set_lore", "lore": ["<gray>old"]}]}]}]}
                """);
        List<Drop> drops = LootEvaluator.roll(table, Context.NONE, new Random(1), null);
        assertEquals(2, drops.size());
        assertEquals("<red>Cursed Blade", drops.get(0).name());
        assertEquals(List.of("<gray>old"), drops.get(0).lore());
    }

    @Test
    @DisplayName("tables without pools or entries are rejected")
    void rejectsEmpty() {
        Issues issues = new Issues();
        assertNull(LootParser.parse(obj("{\"pools\":[]}"), ID, ctx(issues)));
        assertTrue(issues.hasErrors());
        assertNull(LootParser.parse(obj("{\"pools\":[{\"rolls\":1,\"entries\":[]}]}"), ID, ctx(new Issues())));
    }
}
