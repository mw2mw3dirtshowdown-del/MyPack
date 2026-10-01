package com.operator.mypack.pack;

import com.operator.mypack.testsupport.PackFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryValidatorTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("a consistent pack validates without findings")
    void cleanPack() throws Exception {
        PackFixture.create(tmp);
        ContentRegistry registry = PackFixture.registryOf(PackFixture.load(tmp));
        assertTrue(RegistryValidator.validate(registry.snapshot()).isEmpty());
    }

    @Test
    @DisplayName("dangling references are reported per namespace")
    void danglingReferences() throws Exception {
        PackFixture.create(tmp);
        PackFixture.write(tmp, "entities/ghost.json", """
                {"minecraft:entity": {"description": {"identifier": "aether:ghost"}, "components": {
                  "minecraft:loot": {"table": "loot_tables/nowhere.json"},
                  "mypack:model": {"geometry": "geometry.nothing", "texture": "wyvern", "animations": {"idle": "animation.ghost.float"}}}}}
                """);
        PackFixture.write(tmp, "recipes/orphan.json", """
                {"minecraft:recipe_shapeless": {"description": {"identifier": "aether:orphan"},
                  "ingredients": ["aether:missing_item", "minecraft:stick", {"tag": "minecraft:planks"}], "result": "aether:also_missing"}}
                """);
        PackFixture.write(tmp, "loot_tables/broken.json", """
                {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "aether:phantom"}, {"type": "loot_table", "name": "aether:none"}]}]}
                """);
        Map<String, Issues> findings = RegistryValidator.validate(PackFixture.registryOf(PackFixture.load(tmp)).snapshot());
        assertEquals(1, findings.size());
        String all = findings.get("aether").all().toString();
        assertTrue(all.contains("geometry 'aether:geometry.nothing'"), all);
        assertTrue(all.contains("animation 'aether:animation.ghost.float'"), all);
        assertTrue(all.contains("loot table 'aether:nowhere'"), all);
        assertTrue(all.contains("ingredient 'aether:missing_item'"), all);
        assertTrue(all.contains("result 'aether:also_missing'"), all);
        assertTrue(all.contains("aether:phantom"), all);
        assertTrue(all.contains("nested loot table 'aether:none'"), all);
        assertTrue(!all.contains("minecraft:stick") && !all.contains("planks"), "vanilla ids and tags are not custom items");
    }
}
