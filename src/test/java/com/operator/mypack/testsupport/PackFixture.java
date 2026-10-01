package com.operator.mypack.testsupport;

import com.operator.mypack.pack.ContentRegistry;
import com.operator.mypack.pack.InstalledPack;
import com.operator.mypack.pack.Issues;
import com.operator.mypack.pack.ManifestParser;
import com.operator.mypack.pack.PackContent;
import com.operator.mypack.pack.PackFiles;
import com.operator.mypack.pack.PackLoader;
import com.operator.mypack.pack.PackManifest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes a complete, valid sample pack to disk and loads it; shared by the loader and resource pack tests. */
public final class PackFixture {

    public static final String NAMESPACE = "aether";

    private PackFixture() {
    }

    public static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    public static void writeBytes(Path root, String relative, byte[] content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }

    public static byte[] png(int w, int h) throws IOException {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFFFF0000);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** Fake but structurally recognisable Ogg container (the builder only checks the capture pattern). */
    public static byte[] ogg() {
        byte[] data = new byte[64];
        data[0] = 'O';
        data[1] = 'g';
        data[2] = 'g';
        data[3] = 'S';
        return data;
    }

    /** Creates the sample pack below {@code root}. */
    public static void create(Path root) throws IOException {
        write(root, "manifest.json", """
                {"format_version": 2,
                 "header": {"name": "Aether Arsenal", "description": "Fixture pack", "uuid": "2f0b0c83-8a6b-4e55-9f0a-6a1d6c1f2b11",
                            "version": [1, 0, 0], "min_engine_version": [1, 21, 4]},
                 "modules": [{"type": "data", "uuid": "11111111-1111-1111-1111-111111111111", "version": [1, 0, 0]},
                             {"type": "resources", "uuid": "22222222-2222-2222-2222-222222222222", "version": [1, 0, 0]}],
                 "metadata": {"namespace": "aether", "authors": ["Tester"]}}
                """);

        write(root, "items/aether_blaster.json", """
                {"minecraft:item": {"description": {"identifier": "aether:aether_blaster"}, "components": {
                  "minecraft:display_name": "<gradient:aqua:blue>Aether Blaster",
                  "minecraft:icon": "aether_blaster", "minecraft:hand_equipped": true, "mypack:base_material": "DIAMOND_SWORD",
                  "mypack:actions": {"cooldown_ticks": 20, "on_right_click": [
                      {"type": "sound", "sound": "aether:blaster.fire"}, {"type": "damage_ray", "range": 24, "damage": 6}]}}}}
                """);
        write(root, "items/core_shard.json", """
                {"minecraft:item": {"description": {"identifier": "aether:core_shard"}, "components": {"minecraft:icon": "core_shard"}}}
                """);
        write(root, "items/plain_stick.json", """
                {"minecraft:item": {"description": {"identifier": "aether:plain_stick"}, "components": {"mypack:base_material": "STICK"}}}
                """);
        write(root, "recipes/blaster.json", """
                {"minecraft:recipe_shaped": {"description": {"identifier": "aether:blaster_recipe"}, "pattern": [" G ", " R ", " S "],
                 "key": {"G": {"item": "minecraft:gold_ingot"}, "R": {"item": "aether:core_shard"}, "S": {"item": "minecraft:stick"}},
                 "result": {"item": "aether:aether_blaster"}}}
                """);
        write(root, "loot_tables/entities/wyvern.json", """
                {"pools": [{"rolls": {"min": 1, "max": 2}, "entries": [
                   {"type": "item", "name": "aether:core_shard", "weight": 3}, {"type": "item", "name": "minecraft:bone", "weight": 5}]}]}
                """);
        write(root, "entities/wyvern.json", """
                {"minecraft:entity": {"description": {"identifier": "aether:wyvern"}, "components": {
                  "minecraft:health": {"value": 80}, "minecraft:movement": {"value": 0.28}, "minecraft:attack": {"damage": 9},
                  "minecraft:loot": {"table": "loot_tables/entities/wyvern.json"}, "mypack:base_entity": "ZOMBIE",
                  "mypack:model": {"geometry": "geometry.wyvern", "texture": "wyvern",
                                   "animations": {"idle": "animation.wyvern.idle", "walk": "animation.wyvern.walk"}}}}}
                """);
        write(root, "furniture/throne.json", """
                {"mypack:furniture": {"description": {"identifier": "aether:throne"}, "components": {
                  "minecraft:display_name": "<gold>Throne", "minecraft:icon": "throne",
                  "mypack:model": {"geometry": "geometry.throne", "texture": "throne"},
                  "mypack:seats": [{"offset": [0, 0.5, 0]}]}}}
                """);
        write(root, "furniture/lantern.json", """
                {"mypack:furniture": {"description": {"identifier": "aether:lantern"}, "components": {
                  "minecraft:icon": "lantern_post", "mypack:hitbox": {"width": 0.5, "height": 2.0}}}}
                """);
        write(root, "models/entity/wyvern.geo.json", """
                {"format_version": "1.12.0", "minecraft:geometry": [{
                  "description": {"identifier": "geometry.wyvern", "texture_width": 64, "texture_height": 64},
                  "bones": [
                    {"name": "body", "pivot": [0, 12, 0], "cubes": [{"origin": [-4, 12, -2], "size": [8, 12, 4], "uv": [16, 16]}]},
                    {"name": "head", "parent": "body", "pivot": [0, 24, 0], "cubes": [{"origin": [-3, 24, -3], "size": [6, 6, 6], "uv": [0, 0]}]},
                    {"name": "wing_l", "parent": "body", "pivot": [4, 22, 0],
                     "cubes": [{"origin": [4, 14, 0], "size": [10, 8, 1], "uv": [32, 0]}, {"origin": [4, 12, 0], "size": [6, 2, 1], "uv": [32, 10], "mirror": true}]}
                  ]}]}
                """);
        write(root, "models/entity/throne.geo.json", """
                {"format_version": "1.12.0", "minecraft:geometry": [{
                  "description": {"identifier": "geometry.throne", "texture_width": 32, "texture_height": 32},
                  "bones": [{"name": "seat", "pivot": [0, 0, 0], "cubes": [{"origin": [-6, 0, -6], "size": [12, 8, 12], "uv": [0, 0]}]}]}]}
                """);
        write(root, "animations/wyvern.animation.json", """
                {"format_version": "1.8.0", "animations": {
                  "animation.wyvern.idle": {"loop": true, "animation_length": 2.0, "bones": {"wing_l": {"rotation": {"0.0": [0,0,0], "1.0": [0,0,15], "2.0": [0,0,0]}}}},
                  "animation.wyvern.walk": {"loop": true, "animation_length": 1.0, "bones": {"body": {"position": {"0.0": [0,0,0], "0.5": [0,1,0], "1.0": [0,0,0]}}}}}}
                """);
        write(root, "sounds/sound_definitions.json", """
                {"format_version": "1.14.0", "sound_definitions": {
                  "blaster.fire": {"category": "player", "subtitle": "Blaster fires", "sounds": ["sounds/fire", {"name": "sounds/gone", "volume": 0.5}]}}}
                """);
        writeBytes(root, "sounds/fire.ogg", ogg());
        writeBytes(root, "textures/items/aether_blaster.png", png(16, 16));
        writeBytes(root, "textures/items/core_shard.png", png(16, 64));
        write(root, "textures/items/core_shard.png.mcmeta", "{\"animation\": {\"frametime\": 4}}");
        writeBytes(root, "textures/items/throne.png", png(16, 16));
        writeBytes(root, "textures/items/lantern_post.png", png(16, 16));
        writeBytes(root, "textures/entity/wyvern.png", png(64, 64));
        writeBytes(root, "textures/entity/throne.png", png(32, 32));
    }

    /** Loads a pack directory into an {@link InstalledPack}. */
    public static InstalledPack load(Path root) throws Exception {
        Issues issues = new Issues();
        PackManifest manifest = ManifestParser.parse(Files.readAllBytes(root.resolve("manifest.json")), "fixture", issues);
        PackFiles files = new PackFiles(root);
        PackContent content = PackLoader.load(manifest, files, issues);
        return new InstalledPack(manifest, root.getFileName().toString(), files, content, "fp", List.copyOf(issues.all()));
    }

    public static ContentRegistry registryOf(InstalledPack... packs) {
        ContentRegistry registry = new ContentRegistry();
        registry.replaceAll(List.of(packs));
        return registry;
    }
}
