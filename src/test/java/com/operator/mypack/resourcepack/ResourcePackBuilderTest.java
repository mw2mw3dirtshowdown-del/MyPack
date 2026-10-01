package com.operator.mypack.resourcepack;

import com.google.gson.JsonObject;
import com.operator.mypack.model.runtime.ModelBlueprint;
import com.operator.mypack.pack.ContentRegistry;
import com.operator.mypack.pack.InstalledPack;
import com.operator.mypack.testsupport.PackFixture;
import com.operator.mypack.utils.HashUtils;
import com.operator.mypack.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackBuilderTest {

    @TempDir
    Path tmp;

    private static final ResourcePackBuilder.Options OPTIONS =
            new ResourcePackBuilder.Options(46, 34, 46, "MyPack content", null);

    private ResourcePackBuilder.Result build(Path packDir, ResourcePackBuilder.Options options) throws Exception {
        InstalledPack pack = PackFixture.load(packDir);
        ContentRegistry registry = PackFixture.registryOf(pack);
        return ResourcePackBuilder.build(List.of(pack), registry.snapshot(), options);
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                out.put(e.getName(), in.readAllBytes());
            }
        }
        return out;
    }

    private static JsonObject json(Map<String, byte[]> files, String path) {
        byte[] bytes = files.get(path);
        assertNotNull(bytes, "missing " + path + " in " + files.keySet());
        return JsonUtils.parseObject(bytes);
    }

    @Test
    @DisplayName("pack.mcmeta uses pack_format 46 with supported_formats 34-46 and no invented fields")
    void packMeta() throws Exception {
        PackFixture.create(tmp);
        Map<String, byte[]> files = unzip(build(tmp, OPTIONS).zip());
        assertEquals("pack.mcmeta", files.keySet().iterator().next(), "pack.mcmeta is the first entry");
        JsonObject root = json(files, "pack.mcmeta");
        assertEquals(java.util.Set.of("pack"), root.keySet());
        JsonObject pack = root.getAsJsonObject("pack");
        assertEquals(java.util.Set.of("pack_format", "supported_formats", "description"), pack.keySet());
        assertEquals(46, pack.get("pack_format").getAsInt());
        assertEquals(34, pack.getAsJsonObject("supported_formats").get("min_inclusive").getAsInt());
        assertEquals(46, pack.getAsJsonObject("supported_formats").get("max_inclusive").getAsInt());
        assertEquals("MyPack content", pack.get("description").getAsString());
    }

    @Test
    @DisplayName("textures are mapped from Bedrock folders to Java ones; animated textures keep their .png.mcmeta")
    void textures() throws Exception {
        PackFixture.create(tmp);
        Map<String, byte[]> files = unzip(build(tmp, OPTIONS).zip());
        assertTrue(files.containsKey("assets/aether/textures/item/aether_blaster.png"), files.keySet().toString());
        assertTrue(files.containsKey("assets/aether/textures/entity/wyvern.png"));
        assertTrue(files.containsKey("assets/aether/textures/item/core_shard.png"));
        assertTrue(files.containsKey("assets/aether/textures/item/core_shard.png.mcmeta"), "animation metadata is shipped");
        assertEquals(4, json(files, "assets/aether/textures/item/core_shard.png.mcmeta").getAsJsonObject("animation").get("frametime").getAsInt());
        assertArrayEquals(Files.readAllBytes(tmp.resolve("textures/entity/wyvern.png")), files.get("assets/aether/textures/entity/wyvern.png"));
    }

    @Test
    @DisplayName("custom items get an item definition and a flat model; hand-equipped items use the handheld parent")
    void itemModels() throws Exception {
        PackFixture.create(tmp);
        Map<String, byte[]> files = unzip(build(tmp, OPTIONS).zip());
        JsonObject def = json(files, "assets/aether/items/aether_blaster.json");
        assertEquals("minecraft:model", def.getAsJsonObject("model").get("type").getAsString());
        assertEquals("aether:item/aether_blaster", def.getAsJsonObject("model").get("model").getAsString());
        JsonObject model = json(files, "assets/aether/models/item/aether_blaster.json");
        assertEquals("minecraft:item/handheld", model.get("parent").getAsString());
        assertEquals("aether:item/aether_blaster", model.getAsJsonObject("textures").get("layer0").getAsString());
        assertEquals("minecraft:item/generated", json(files, "assets/aether/models/item/core_shard.json").get("parent").getAsString());
        assertFalse(files.containsKey("assets/aether/items/plain_stick.json"), "items without an icon keep their vanilla look");
        assertTrue(files.containsKey("assets/aether/items/lantern.json"), "flat furniture uses its icon");
    }

    @Test
    @DisplayName("every cube of a mob/furniture model gets a baked model + item definition with the documented key format")
    void cubeModels() throws Exception {
        PackFixture.create(tmp);
        InstalledPack pack = PackFixture.load(tmp);
        ContentRegistry registry = PackFixture.registryOf(pack);
        Map<String, byte[]> files = unzip(ResourcePackBuilder.build(List.of(pack), registry.snapshot(), OPTIONS).zip());

        ModelBlueprint wyvern = ModelBlueprint.of(registry.geometry("aether:geometry.wyvern"), "wyvern");
        assertEquals(4, wyvern.cubes().size());
        for (ModelBlueprint.CubeNode node : wyvern.cubes()) {
            JsonObject model = json(files, "assets/aether/models/entity/" + node.path() + ".json");
            assertEquals("aether:entity/wyvern", model.getAsJsonObject("textures").get("0").getAsString());
            assertFalse(model.has("parent"));
            assertEquals(6, model.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces").size());
            JsonObject def = json(files, "assets/aether/items/" + node.path() + ".json");
            assertEquals("aether:entity/" + node.path(), def.getAsJsonObject("model").get("model").getAsString());
        }
        assertEquals("wyvern_body_c0", wyvern.cubes().get(0).path());
        assertTrue(files.containsKey("assets/aether/models/entity/throne_seat_c0.json"));
        // no stray rotation fields in any generated cube model
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            if (e.getKey().startsWith("assets/aether/models/entity/")) {
                assertFalse(new String(e.getValue(), StandardCharsets.UTF_8).contains("\"rotation\""), e.getKey());
            }
        }
    }

    @Test
    @DisplayName("sounds.json and the .ogg files live in the SAME namespace; missing files are dropped with a warning")
    void sounds() throws Exception {
        PackFixture.create(tmp);
        ResourcePackBuilder.Result result = build(tmp, OPTIONS);
        Map<String, byte[]> files = unzip(result.zip());
        assertTrue(files.containsKey("assets/aether/sounds/fire.ogg"));
        JsonObject sounds = json(files, "assets/aether/sounds.json");
        JsonObject fire = sounds.getAsJsonObject("blaster.fire");
        assertEquals(1, fire.getAsJsonArray("sounds").size(), "the entry whose file is missing is dropped");
        assertEquals("aether:fire", fire.getAsJsonArray("sounds").get(0).getAsJsonObject().get("name").getAsString(),
                "<namespace>:<path under sounds/> - the namespace matches assets/<namespace>/sounds/");
        assertEquals("subtitles.aether.blaster.fire", fire.get("subtitle").getAsString());
        assertEquals("Blaster fires", json(files, "assets/aether/lang/en_us.json").get("subtitles.aether.blaster.fire").getAsString());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("sounds/gone.ogg")), result.warnings().toString());
    }

    @Test
    @DisplayName("the block atlas is never shipped and the minecraft namespace cannot be overridden")
    void forbiddenFiles() throws Exception {
        PackFixture.create(tmp);
        PackFixture.write(tmp, "assets/minecraft/atlases/blocks.json", "{\"sources\": []}");
        PackFixture.writeBytes(tmp, "assets/minecraft/textures/block/stone.png", PackFixture.png(16, 16));
        PackFixture.write(tmp, "assets/aether/atlases/blocks.json", "{\"sources\": []}");
        PackFixture.write(tmp, "assets/aether/extra/info.txt", "hello");
        ResourcePackBuilder.Result result = build(tmp, OPTIONS);
        Map<String, byte[]> files = unzip(result.zip());
        assertFalse(files.containsKey("assets/minecraft/atlases/blocks.json"));
        assertTrue(files.keySet().stream().noneMatch(p -> p.startsWith("assets/minecraft/")), "nothing below assets/minecraft");
        assertTrue(files.containsKey("assets/aether/extra/info.txt"), "raw assets of the pack's own namespace pass through");
        assertEquals(2, result.warnings().stream().filter(w -> w.contains("atlas") || w.contains("minecraft namespace")).count(),
                result.warnings().toString());
    }

    @Test
    @DisplayName("invalid files are skipped with warnings: fake PNG/OGG, unsupported formats, bad mcmeta")
    void invalidFiles() throws Exception {
        PackFixture.create(tmp);
        PackFixture.write(tmp, "textures/items/fake.png", "this is not a png");
        PackFixture.write(tmp, "textures/items/legacy.tga", "tga");
        PackFixture.write(tmp, "textures/items/bad.png.mcmeta", "{ nope");
        PackFixture.write(tmp, "sounds/fake.ogg", "not ogg at all");
        PackFixture.write(tmp, "sounds/music.wav", "RIFF");
        ResourcePackBuilder.Result result = build(tmp, OPTIONS);
        Map<String, byte[]> files = unzip(result.zip());
        assertFalse(files.containsKey("assets/aether/textures/item/fake.png"));
        assertFalse(files.containsKey("assets/aether/textures/item/legacy.tga"));
        assertFalse(files.containsKey("assets/aether/textures/item/bad.png.mcmeta"));
        assertFalse(files.containsKey("assets/aether/sounds/fake.ogg"));
        assertFalse(files.containsKey("assets/aether/sounds/music.wav"));
        assertTrue(result.warnings().size() >= 6, result.warnings().toString());
    }

    @Test
    @DisplayName("names are normalised to legal resource locations (lower-case, no spaces)")
    void normalisation() throws Exception {
        PackFixture.create(tmp);
        PackFixture.writeBytes(tmp, "textures/items/Blaster Fire.PNG", PackFixture.png(16, 16));
        Map<String, byte[]> files = unzip(build(tmp, OPTIONS).zip());
        assertTrue(files.containsKey("assets/aether/textures/item/blaster_fire.png"), files.keySet().toString());
        assertTrue(files.keySet().stream().allMatch(p -> p.equals(p.toLowerCase())), "no upper-case paths in the archive");
    }

    @Test
    @DisplayName("the archive is deterministic: same input = same bytes = same SHA-1, even when file times change")
    void deterministic() throws Exception {
        PackFixture.create(tmp);
        ResourcePackBuilder.Result first = build(tmp, OPTIONS);
        try (var stream = Files.walk(tmp)) {
            for (Path p : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                Files.setLastModifiedTime(p, FileTime.fromMillis(1_000_000_000_000L));
            }
        }
        ResourcePackBuilder.Result second = build(tmp, OPTIONS);
        assertArrayEquals(first.zip(), second.zip());
        assertEquals(first.sha1Hex(), second.sha1Hex());
        assertEquals(HashUtils.sha1Hex(first.zip()), first.sha1Hex());
        assertEquals(40, first.sha1Hex().length());
        assertEquals(unzip(first.zip()).size(), first.fileCount());
    }

    @Test
    @DisplayName("changing any input changes the hash")
    void hashChanges() throws Exception {
        PackFixture.create(tmp);
        String before = build(tmp, OPTIONS).sha1Hex();
        PackFixture.writeBytes(tmp, "textures/entity/wyvern.png", PackFixture.png(64, 32));
        assertFalse(before.equals(build(tmp, OPTIONS).sha1Hex()));
        String optionsChanged = build(tmp, new ResourcePackBuilder.Options(55, 34, 55, "other", null)).sha1Hex();
        assertFalse(before.equals(optionsChanged));
    }

    @Test
    @DisplayName("a missing geometry is a warning: no models are baked but the pack still builds")
    void missingGeometry() throws Exception {
        PackFixture.create(tmp);
        Files.delete(tmp.resolve("models/entity/wyvern.geo.json"));
        ResourcePackBuilder.Result result = build(tmp, OPTIONS);
        Map<String, byte[]> files = unzip(result.zip());
        assertTrue(files.keySet().stream().noneMatch(p -> p.contains("wyvern_")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("geometry 'aether:geometry.wyvern' is not defined")));
    }

    @Test
    @DisplayName("an optional pack.png is validated before it is included")
    void packIcon() throws Exception {
        PackFixture.create(tmp);
        byte[] png = PackFixture.png(64, 64);
        Map<String, byte[]> withIcon = unzip(build(tmp, new ResourcePackBuilder.Options(46, 34, 46, "d", png)).zip());
        assertArrayEquals(png, withIcon.get("pack.png"));
        ResourcePackBuilder.Result bad = build(tmp, new ResourcePackBuilder.Options(46, 34, 46, "d", "nope".getBytes(StandardCharsets.UTF_8)));
        assertFalse(unzip(bad.zip()).containsKey("pack.png"));
        assertTrue(bad.warnings().stream().anyMatch(w -> w.contains("pack.png")));
    }
}
