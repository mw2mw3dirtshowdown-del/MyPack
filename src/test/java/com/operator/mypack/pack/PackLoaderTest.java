package com.operator.mypack.pack;

import com.operator.mypack.testsupport.PackFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackLoaderTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("loads every content type of the fixture pack")
    void loadsFixture() throws Exception {
        PackFixture.create(tmp);
        InstalledPack pack = PackFixture.load(tmp);
        PackContent c = pack.content();
        assertEquals(3, c.items().size());
        assertEquals(1, c.recipes().size());
        assertEquals(1, c.lootTables().size());
        assertEquals("aether:entities/wyvern", c.lootTables().get(0).id().full(), "loot id comes from the file path");
        assertEquals(1, c.mobs().size());
        assertEquals(2, c.furniture().size());
        assertEquals(1, c.sounds().size());
        assertEquals(2, c.geometries().size());
        assertEquals(2, c.animations().size());
        assertEquals("aether", pack.namespace());
        assertEquals(c.totalCount(), 3 + 1 + 1 + 1 + 2 + 1 + 2 + 2);
        assertTrue(pack.issues().stream().noneMatch(i -> i.level() == Issues.Level.ERROR), pack.issues().toString());
    }

    @Test
    @DisplayName("a broken file is reported and skipped; the rest of the pack still loads")
    void badFilesAreIsolated() throws Exception {
        PackFixture.create(tmp);
        PackFixture.write(tmp, "items/broken.json", "{ this is not json");
        PackFixture.write(tmp, "items/array.json", "[1,2,3]");
        PackFixture.write(tmp, "items/foreign.json", "{\"minecraft:item\":{\"description\":{\"identifier\":\"other:x\"}}}");
        PackFixture.write(tmp, "recipes/bad.json", "{\"minecraft:recipe_shaped\":{}}");
        PackFixture.write(tmp, "models/entity/empty.geo.json", "{}");
        PackFixture.write(tmp, "animations/bad.animation.json", "{\"animations\": 5}");
        InstalledPack pack = PackFixture.load(tmp);
        assertEquals(3, pack.content().items().size(), "good items survive");
        assertEquals(1, pack.content().recipes().size());
        long errors = pack.issues().stream().filter(i -> i.level() == Issues.Level.ERROR).count();
        assertTrue(errors >= 6, "each bad file is reported: " + pack.issues());
        assertTrue(pack.issues().stream().anyMatch(i -> i.file().equals("items/broken.json")));
    }

    @Test
    @DisplayName("duplicate identifiers: the first definition wins and the duplicate is reported")
    void duplicates() throws Exception {
        PackFixture.create(tmp);
        PackFixture.write(tmp, "items/zzz_copy.json",
                "{\"minecraft:item\":{\"description\":{\"identifier\":\"aether:core_shard\"},\"components\":{\"mypack:base_material\":\"STONE\"}}}");
        InstalledPack pack = PackFixture.load(tmp);
        assertEquals(3, pack.content().items().size());
        assertEquals("PAPER", pack.content().items().stream().filter(i -> i.id().path().equals("core_shard"))
                .findFirst().orElseThrow().baseMaterial());
        assertTrue(pack.issues().stream().anyMatch(i -> i.message().contains("duplicate definition")));
    }

    @Test
    @DisplayName("missing textures are warnings, not failures")
    void missingTextures() throws Exception {
        PackFixture.create(tmp);
        Files.delete(tmp.resolve("textures/items/aether_blaster.png"));
        Files.delete(tmp.resolve("textures/entity/wyvern.png"));
        InstalledPack pack = PackFixture.load(tmp);
        assertEquals(3, pack.content().items().size());
        assertTrue(pack.issues().stream().anyMatch(i -> i.level() == Issues.Level.WARN && i.message().contains("aether_blaster.png")));
        assertTrue(pack.issues().stream().anyMatch(i -> i.level() == Issues.Level.WARN && i.message().contains("wyvern.png")));
    }

    @Test
    @DisplayName("PackFiles refuses to read outside the pack")
    void packFilesTraversal() throws Exception {
        PackFixture.create(tmp.resolve("pack"));
        PackFixture.write(tmp, "secret.txt", "top secret");
        PackFiles files = new PackFiles(tmp.resolve("pack"));
        assertTrue(files.exists("manifest.json"));
        assertFalse(files.exists("../secret.txt"));
        assertThrowsIo(() -> files.read("../secret.txt"));
        assertTrue(files.list("../", ".txt").isEmpty() || files.list("../", ".txt").stream().noneMatch(p -> p.contains("secret")));
    }

    private interface IoCall {
        void run() throws Exception;
    }

    private static void assertThrowsIo(IoCall call) {
        try {
            call.run();
        } catch (java.io.IOException expected) {
            return;
        } catch (Exception other) {
            throw new AssertionError("unexpected " + other, other);
        }
        throw new AssertionError("expected an IOException");
    }

    @Test
    @DisplayName("PackLocator finds the manifest at the root or in the single top level folder")
    void locator() throws Exception {
        Path flat = tmp.resolve("flat");
        PackFixture.create(flat);
        assertEquals(flat, PackLocator.findRoot(flat).orElseThrow());

        Path nested = tmp.resolve("nested");
        PackFixture.create(nested.resolve("Aether Arsenal"));
        assertEquals(nested.resolve("Aether Arsenal"), PackLocator.findRoot(nested).orElseThrow());

        Path ambiguous = tmp.resolve("ambiguous");
        PackFixture.create(ambiguous.resolve("a"));
        PackFixture.create(ambiguous.resolve("b"));
        assertTrue(PackLocator.findRoot(ambiguous).isEmpty());
        Files.createDirectories(tmp.resolve("empty"));
        assertTrue(PackLocator.findRoot(tmp.resolve("empty")).isEmpty());
    }

    @Test
    @DisplayName("registry: namespace removal affects only that namespace and old snapshots stay intact")
    void registry() throws Exception {
        PackFixture.create(tmp);
        InstalledPack pack = PackFixture.load(tmp);
        ContentRegistry registry = new ContentRegistry();
        registry.register("aether", pack.content());
        ContentRegistry.Snapshot before = registry.snapshot();
        assertNotNull(registry.item("aether:aether_blaster"));
        assertNotNull(registry.mob("aether:wyvern"));
        assertNotNull(registry.geometry("aether:geometry.wyvern"));
        assertNotNull(registry.animation("aether:animation.wyvern.idle"));
        assertNotNull(registry.lootTable("aether:entities/wyvern"));
        assertNotNull(registry.furniture("aether:throne"));

        // a second, unrelated namespace
        PackContent other = new PackContent(pack.content().items().subList(0, 1), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of());
        registry.register("other", new PackContent(java.util.List.of(new com.operator.mypack.content.item.ItemDefinition(
                new com.operator.mypack.content.ContentId("other", "thing"), "Thing", java.util.List.of(), "PAPER", null, null,
                false, 0, 0, false, null, null, null, java.util.List.of(), Map.of(), java.util.List.of(), java.util.List.of(),
                com.operator.mypack.content.item.ItemDefinition.ItemActions.NONE)),
                other.recipes(), other.lootTables(), other.mobs(), other.furniture(), other.sounds(), other.geometries(), other.animations()));
        assertNotNull(registry.item("other:thing"));

        registry.removeByNamespace("aether");
        assertNull(registry.item("aether:aether_blaster"));
        assertNull(registry.mob("aether:wyvern"));
        assertNull(registry.animation("aether:animation.wyvern.idle"));
        assertNotNull(registry.item("other:thing"), "other namespaces are untouched");
        assertNotNull(before.items().get("aether:aether_blaster"), "a previously taken snapshot never changes");
        assertEquals(0, ContentRegistry.Snapshot.EMPTY.totalCount());
    }

    @Test
    @DisplayName("namespace prefix matching is exact: removing 'a' must not remove 'ab'")
    void registryPrefixIsExact() throws Exception {
        PackFixture.create(tmp);
        InstalledPack pack = PackFixture.load(tmp);
        ContentRegistry registry = new ContentRegistry();
        registry.register("aether", pack.content());
        registry.removeByNamespace("aeth");
        assertNotNull(registry.item("aether:aether_blaster"));
        registry.removeByNamespace("aether");
        assertEquals(0, registry.snapshot().totalCount());
    }
}
