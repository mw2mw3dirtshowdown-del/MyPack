package com.operator.mypack.e2e;

import com.google.gson.JsonObject;
import com.operator.mypack.config.Settings;
import com.operator.mypack.content.loot.LootEvaluator;
import com.operator.mypack.content.mob.MobDefinition;
import com.operator.mypack.model.anim.Animation;
import com.operator.mypack.model.anim.AnimationPlayer;
import com.operator.mypack.model.anim.MolangContext;
import com.operator.mypack.model.anim.PoseSet;
import com.operator.mypack.model.geo.GeometryModel;
import com.operator.mypack.model.runtime.ModelBlueprint;
import com.operator.mypack.pack.ContentRegistry;
import com.operator.mypack.pack.DependencyResolver;
import com.operator.mypack.pack.InstalledPack;
import com.operator.mypack.pack.Issues;
import com.operator.mypack.pack.PackContent;
import com.operator.mypack.pack.PackFiles;
import com.operator.mypack.pack.PackLoader;
import com.operator.mypack.pack.PackScanner;
import com.operator.mypack.pack.RegistryValidator;
import com.operator.mypack.resourcepack.ResourcePackBuilder;
import com.operator.mypack.resourcepack.ResourcePackServer;
import com.operator.mypack.utils.HashUtils;
import com.operator.mypack.utils.JsonUtils;
import org.joml.Matrix4f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the whole server-independent pipeline on the example pack that ships in {@code examples/}: it is zipped like a
 * downloaded add-on, scanned, loaded, cross-checked, merged into a resource pack, served over HTTP and downloaded again.
 * If the example (which is also the documentation of the format) ever stops being valid, this test fails.
 */
class ExamplePackEndToEndTest {

    private static final Path EXAMPLE = Path.of("examples/aether_arsenal");
    private static final Settings.Limits LIMITS = new Settings.Limits(50_000_000L, 100_000_000L, 20_000_000L, 5_000);

    @TempDir
    Path tmp;

    private static void zip(Path source, Path target, String topFolder) throws IOException {
        try (OutputStream out = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(out);
             Stream<Path> files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().collect(Collectors.toList())) {
                zip.putNextEntry(new ZipEntry(topFolder + "/" + source.relativize(file).toString().replace('\\', '/')));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
        }
    }

    private InstalledPack installExample() throws Exception {
        assumeTrue(Files.isDirectory(EXAMPLE), "run from the project root to find examples/");
        Path packs = Files.createDirectories(tmp.resolve("packs"));
        zip(EXAMPLE, packs.resolve("aether_arsenal.mcpack"), "Aether Arsenal");
        PackScanner scanner = new PackScanner(packs, tmp.resolve("cache"), LIMITS, Logger.getAnonymousLogger());
        PackScanner.Result scan = scanner.scan();
        assertTrue(scan.failures().isEmpty(), scan.failures().toString());
        assertEquals(1, scan.candidates().size());
        PackScanner.Candidate candidate = scan.candidates().get(0);
        DependencyResolver.Result resolved = DependencyResolver.resolve(List.of(candidate.manifest()));
        assertEquals(1, resolved.ordered().size());
        Issues issues = new Issues();
        issues.addAll(candidate.issues().all());
        PackFiles files = new PackFiles(candidate.root());
        PackContent content = PackLoader.load(candidate.manifest(), files, issues);
        assertTrue(issues.isEmpty(), "the shipped example must load without a single warning: " + issues.all());
        return new InstalledPack(candidate.manifest(), candidate.source(), files, content, candidate.fingerprint(), List.copyOf(issues.all()));
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                out.put(e.getName(), in.readAllBytes());
            }
        }
        return out;
    }

    @Test
    @DisplayName("the example pack loads from a .mcpack archive with every definition and no issues")
    void loadsExample() throws Exception {
        InstalledPack pack = installExample();
        PackContent c = pack.content();
        assertEquals("aether", pack.namespace());
        assertEquals(3, c.items().size());
        assertEquals(3, c.recipes().size());
        assertEquals(1, c.lootTables().size());
        assertEquals(1, c.mobs().size());
        assertEquals(2, c.furniture().size());
        assertEquals(2, c.sounds().size());
        assertEquals(2, c.geometries().size());
        assertEquals(5, c.animations().size());
        ContentRegistry registry = new ContentRegistry();
        registry.replaceAll(List.of(pack));
        assertTrue(RegistryValidator.validate(registry.snapshot()).isEmpty(), "all cross references resolve");
    }

    @Test
    @DisplayName("the merged resource pack is complete, deterministic, and downloads over HTTP with a matching SHA-1")
    void buildsServesAndDownloads() throws Exception {
        InstalledPack pack = installExample();
        ContentRegistry registry = new ContentRegistry();
        registry.replaceAll(List.of(pack));
        ResourcePackBuilder.Options options = new ResourcePackBuilder.Options(46, 34, 46, "MyPack content", null);
        ResourcePackBuilder.Result built = ResourcePackBuilder.build(List.of(pack), registry.snapshot(), options);
        assertTrue(built.warnings().isEmpty(), "no build warnings expected: " + built.warnings());
        assertEquals(built.sha1Hex(), ResourcePackBuilder.build(List.of(pack), registry.snapshot(), options).sha1Hex(), "deterministic");

        Map<String, byte[]> files = unzip(built.zip());
        for (String required : List.of(
                "pack.mcmeta",
                "assets/aether/sounds.json", "assets/aether/sounds/blaster/fire.ogg", "assets/aether/sounds/wyvern/roar.ogg",
                "assets/aether/lang/en_us.json",
                "assets/aether/textures/item/aether_blaster.png", "assets/aether/textures/item/core_shard.png",
                "assets/aether/textures/item/core_shard.png.mcmeta", "assets/aether/textures/entity/wyvern.png",
                "assets/aether/textures/entity/throne.png",
                "assets/aether/items/aether_blaster.json", "assets/aether/models/item/aether_blaster.json",
                "assets/aether/items/core_shard.json", "assets/aether/items/wyvern_scale.json", "assets/aether/items/lantern_post.json")) {
            assertTrue(files.containsKey(required), "missing " + required);
        }
        assertFalse(files.containsKey("assets/minecraft/atlases/blocks.json"));

        // every cube of both models has a baked model + item definition under the documented key
        GeometryModel wyvern = registry.geometry("aether:geometry.wyvern");
        ModelBlueprint blueprint = ModelBlueprint.of(wyvern, "wyvern");
        assertEquals(wyvern.cubeCount(), blueprint.cubes().size());
        for (ModelBlueprint.CubeNode node : blueprint.cubes()) {
            JsonObject model = JsonUtils.parseObject(files.get("assets/aether/models/entity/" + node.path() + ".json"));
            assertEquals("aether:entity/wyvern", model.getAsJsonObject("textures").get("0").getAsString());
            assertNotNull(files.get("assets/aether/items/" + node.path() + ".json"), node.path());
        }
        assertEquals(4, ModelBlueprint.of(registry.geometry("aether:geometry.throne"), "throne").cubes().size(), "the throne has 4 cubes");

        // serve it and download it again
        try (ResourcePackServer server = new ResourcePackServer(Logger.getAnonymousLogger())) {
            server.start("127.0.0.1", 0);
            server.publish(built.sha1Hex(), built.zip());
            String url = ResourcePackServer.publicUrl(new Settings.Http(true, "127.0.0.1", server.port(), "127.0.0.1", "http", 0),
                    server.port(), built.sha1Hex());
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertEquals(built.sha1Hex(), HashUtils.sha1Hex(response.body()), "client-side hash equals the hash given to the client");
        }
    }

    @Test
    @DisplayName("the wyvern loot table only produces the declared items in the declared ranges")
    void lootBehaves() throws Exception {
        InstalledPack pack = installExample();
        ContentRegistry registry = new ContentRegistry();
        registry.replaceAll(List.of(pack));
        var table = registry.lootTable("aether:entities/wyvern");
        Random rng = new Random(42);
        Map<String, Integer> totals = new HashMap<>();
        int shardsWithoutPlayer = 0;
        for (int i = 0; i < 5000; i++) {
            for (LootEvaluator.Drop drop : LootEvaluator.roll(table, LootEvaluator.Context.NONE, rng, registry::lootTable)) {
                totals.merge(drop.item(), drop.amount(), Integer::sum);
                if (drop.item().equals("aether:core_shard")) {
                    shardsWithoutPlayer++;
                }
                if (drop.item().equals("aether:wyvern_scale")) {
                    assertTrue(drop.amount() >= 1 && drop.amount() <= 3, "scale count without looting is 1..3");
                }
            }
        }
        assertEquals(java.util.Set.of("aether:wyvern_scale", "minecraft:bone"), totals.keySet());
        assertEquals(0, shardsWithoutPlayer, "core shards need a player kill");
        int shardsWithPlayer = 0;
        for (int i = 0; i < 5000; i++) {
            for (LootEvaluator.Drop drop : LootEvaluator.roll(table, new LootEvaluator.Context(true, 0), rng, registry::lootTable)) {
                if (drop.item().equals("aether:core_shard")) {
                    shardsWithPlayer++;
                }
            }
        }
        assertEquals(0.15, shardsWithPlayer / 5000.0, 0.02, "15% base chance");
    }

    @Test
    @DisplayName("every wyvern animation produces finite matrices for every cube over its whole duration")
    void animationsAreNumericallySane() throws Exception {
        InstalledPack pack = installExample();
        ContentRegistry registry = new ContentRegistry();
        registry.replaceAll(List.of(pack));
        MobDefinition mob = registry.mob("aether:wyvern");
        ModelBlueprint blueprint = ModelBlueprint.of(registry.geometry(mob.model().geometry()), "wyvern");
        Matrix4f[] bones = blueprint.newBoneMatrices();
        Matrix4f[] cubes = blueprint.newCubeMatrices();
        PoseSet poses = new PoseSet(blueprint.bones().size());
        MolangContext molang = new MolangContext(new Random(1)).setQuery("ground_speed", 4.0);
        Matrix4f root = new Matrix4f().scale((float) mob.model().scale());

        List<String> moving = new ArrayList<>();
        for (Map.Entry<String, String> entry : mob.model().animations().entrySet()) {
            Animation animation = registry.animation(entry.getValue());
            assertNotNull(animation, entry.getValue());
            AnimationPlayer player = new AnimationPlayer();
            Matrix4f first = null;
            boolean changed = false;
            for (int tick = 0; tick <= 80; tick++) {
                if (tick == 0) {
                    if (entry.getKey().equals("attack") || entry.getKey().equals("hurt") || entry.getKey().equals("death")) {
                        player.playOneShot(animation, 0);
                    } else {
                        player.setBase(animation, 0);
                    }
                }
                player.evaluate(tick, molang, blueprint::boneIndex, poses);
                blueprint.solve(poses, root, bones, cubes);
                for (Matrix4f m : cubes) {
                    assertTrue(Float.isFinite(m.m00()) && Float.isFinite(m.m11()) && Float.isFinite(m.m22())
                            && Float.isFinite(m.m30()) && Float.isFinite(m.m31()) && Float.isFinite(m.m32()),
                            entry.getKey() + " tick " + tick + ": non-finite matrix");
                    assertTrue(Math.abs(m.determinant()) > 1e-9, entry.getKey() + " tick " + tick + ": singular matrix");
                }
                if (first == null) {
                    first = new Matrix4f(cubes[0]);
                    for (int i = 0; i < cubes.length; i++) {
                        first = new Matrix4f(cubes[i]);
                    }
                }
                changed |= !new Matrix4f(cubes[cubes.length / 2]).equals(first, 1e-5f);
            }
            if (changed) {
                moving.add(entry.getKey());
            }
        }
        assertTrue(moving.containsAll(List.of("idle", "walk", "attack", "hurt", "death")), "every animation moves something: " + moving);
    }

    @Test
    @DisplayName("the wyvern's derived hitbox is a sensible size and sits on the ground")
    void hitboxIsSensible() throws Exception {
        InstalledPack pack = installExample();
        ContentRegistry registry = new ContentRegistry();
        registry.replaceAll(List.of(pack));
        MobDefinition mob = registry.mob("aether:wyvern");
        ModelBlueprint.Hitbox hitbox = ModelBlueprint.of(registry.geometry(mob.model().geometry()), "wyvern").hitbox(mob.model().scale());
        assertTrue(hitbox.width() > 1.5F && hitbox.width() < 4.0F, "width " + hitbox.width() + " (tail and wings must not dominate)");
        assertTrue(hitbox.height() > 0.8F && hitbox.height() < 4.0F, "height " + hitbox.height());
        assertEquals(0.0F, hitbox.offsetY(), 1e-4F, "model stands on the ground");
        assertEquals(1.5, mob.scale(), 1e-9);
        assertEquals(1.5, mob.model().scale(), 1e-9, "minecraft:scale also scales the model");
    }
}
