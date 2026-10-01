package com.operator.mypack.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.operator.mypack.model.bake.CubeModelBaker;
import com.operator.mypack.model.geo.GeometryModel;
import com.operator.mypack.model.runtime.ModelBlueprint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static com.operator.mypack.model.ModelTestSupport.geometry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueprintAndBakerTest {

    private static final String WYVERN = """
            {"minecraft:geometry": [{"description": {"identifier": "geometry.wyvern", "texture_width": 64, "texture_height": 64},
              "bones": [
                {"name": "Body", "pivot": [0, 12, 0], "cubes": [
                    {"origin": [-4, 12, -2], "size": [8, 12, 4], "uv": [16, 16]},
                    {"origin": [-1, 14, 2], "size": [2, 2, 6], "pivot": [0, 15, 2], "rotation": [20, 0, 0], "uv": [0, 0]}]},
                {"name": "Head Top", "parent": "Body", "pivot": [0, 24, 0], "cubes": [
                    {"origin": [-3, 24, -3], "size": [6, 6, 6], "uv": [0, 32], "inflate": 0.25}]},
                {"name": "ghost", "neverRender": true, "cubes": [{"origin": [0,0,0], "size": [1,1,1], "uv": [0,0]}]}
              ]}]}
            """;

    private static void walk(JsonElement e, Set<String> keys) {
        if (e.isJsonObject()) {
            for (var entry : e.getAsJsonObject().entrySet()) {
                keys.add(entry.getKey());
                walk(entry.getValue(), keys);
            }
        } else if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(x -> walk(x, keys));
        }
    }

    @Test
    @DisplayName("cube keys are <model>_<bone>_<cube>: lower-case, safe characters, unique, hidden bones excluded")
    void cubeKeys() {
        ModelBlueprint bp = ModelBlueprint.of(geometry(WYVERN), "wyvern");
        assertEquals(3, bp.cubes().size(), "the neverRender bone has no displays");
        assertEquals("wyvern_body_c0", bp.cubes().get(0).path());
        assertEquals("wyvern_body_c1", bp.cubes().get(1).path());
        assertEquals("wyvern_head_top_c0", bp.cubes().get(2).path());
        Set<String> unique = new HashSet<>();
        for (ModelBlueprint.CubeNode node : bp.cubes()) {
            assertTrue(node.path().matches("[a-z0-9_\\-]+"), node.path());
            assertTrue(unique.add(node.path()));
        }
        assertEquals(1, bp.boneIndex("HEAD TOP".replace("HEAD TOP", "head top")), "bone lookup is case-insensitive");
        assertEquals(-1, bp.boneIndex("nope"));
    }

    @Test
    @DisplayName("colliding cube paths get a numeric suffix")
    void collisionsAreDisambiguated() {
        GeometryModel geo = geometry("""
                {"minecraft:geometry": [{"description": {"identifier": "geometry.x"}, "bones": [
                  {"name": "A B", "cubes": [{"origin": [0,0,0], "size": [1,1,1], "uv": [0,0]}]},
                  {"name": "a_b", "cubes": [{"origin": [0,0,0], "size": [1,1,1], "uv": [0,0]}]}]}]}
                """);
        ModelBlueprint bp = ModelBlueprint.of(geo, "x");
        assertEquals("x_a_b_c0", bp.cubes().get(0).path());
        assertEquals("x_a_b_c0_2", bp.cubes().get(1).path());
    }

    @Test
    @DisplayName("baked cube model: parentless, one unit element, six faces, uv in 0..16, no rotation field anywhere")
    void bakedModelShape() {
        GeometryModel geo = geometry(WYVERN);
        ModelBlueprint bp = ModelBlueprint.of(geo, "wyvern");
        JsonObject model = CubeModelBaker.cubeModel(bp.cubes().get(1), geo, "aether:entity/wyvern");

        Set<String> keys = new HashSet<>();
        walk(model, keys);
        assertFalse(keys.contains("rotation"), "rotation lives in the display transformation, never in the model");
        assertFalse(model.has("parent"), "a builtin/generated parent would discard the elements");
        assertEquals("aether:entity/wyvern", model.getAsJsonObject("textures").get("0").getAsString());
        assertEquals("aether:entity/wyvern", model.getAsJsonObject("textures").get("particle").getAsString());

        assertEquals(1, model.getAsJsonArray("elements").size());
        JsonObject element = model.getAsJsonArray("elements").get(0).getAsJsonObject();
        assertEquals("[0.0,0.0,0.0]", element.get("from").toString());
        assertEquals("[16.0,16.0,16.0]", element.get("to").toString());
        JsonObject faces = element.getAsJsonObject("faces");
        assertEquals(6, faces.size());
        for (String face : new String[]{"north", "east", "south", "west", "up", "down"}) {
            JsonObject f = faces.getAsJsonObject(face);
            assertEquals("#0", f.get("texture").getAsString(), face);
            assertEquals(4, f.getAsJsonArray("uv").size());
            f.getAsJsonArray("uv").forEach(v -> {
                double d = v.getAsDouble();
                assertTrue(d >= 0.0 && d <= 16.0, face + " uv out of range: " + d);
            });
        }
        // 2x2x6 cube at uv (0,0) on a 64px texture: north face = u 6..8, v 6..8 px -> 1.5..2, 1.5..2
        assertEquals("[1.5,1.5,2.0,2.0]", faces.getAsJsonObject("north").get("uv").toString());
    }

    @Test
    @DisplayName("per-face UV cubes only emit the faces they define")
    void perFaceModelOmitsMissingFaces() {
        GeometryModel geo = geometry("""
                {"minecraft:geometry": [{"description": {"identifier": "geometry.p", "texture_width": 16, "texture_height": 16},
                  "bones": [{"name": "b", "cubes": [{"origin": [0,0,0], "size": [2,2,2],
                    "uv": {"north": {"uv": [0, 0], "uv_size": [2, 2]}, "up": {"uv": [4, 4], "uv_size": [2, 2], "uv_rotation": 90}}}]}]}]}
                """);
        ModelBlueprint bp = ModelBlueprint.of(geo, "p");
        JsonObject faces = CubeModelBaker.cubeModel(bp.cubes().get(0), geo, "aether:entity/p")
                .getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces");
        assertEquals(2, faces.size());
        assertEquals(90, faces.getAsJsonObject("up").get("rotation").getAsInt());
        assertFalse(faces.has("south"));
    }

    @Test
    @DisplayName("item definition points the item_model at the baked model (1.21.4 format)")
    void itemDefinition() {
        JsonObject def = CubeModelBaker.itemDefinition("aether:entity/wyvern_body_c0");
        assertEquals("minecraft:model", def.getAsJsonObject("model").get("type").getAsString());
        assertEquals("aether:entity/wyvern_body_c0", def.getAsJsonObject("model").get("model").getAsString());
    }

    @Test
    @DisplayName("rest bounds and hitbox cover the model; the Interaction sits on the model's lowest point")
    void hitbox() {
        GeometryModel geo = geometry("""
                {"minecraft:geometry": [{"description": {"identifier": "geometry.box"}, "bones": [
                  {"name": "b", "cubes": [{"origin": [-4, 0, -2], "size": [8, 16, 4], "uv": [0, 0]}]}]}]}
                """);
        ModelBlueprint bp = ModelBlueprint.of(geo, "box");
        ModelBlueprint.Bounds b = bp.restBounds();
        assertEquals(-0.25f, b.minX(), 1e-5f);
        assertEquals(0.25f, b.maxX(), 1e-5f);
        assertEquals(0.0f, b.minY(), 1e-5f);
        assertEquals(1.0f, b.maxY(), 1e-5f);
        assertEquals(-0.125f, b.minZ(), 1e-5f);
        ModelBlueprint.Hitbox h = bp.hitbox(1.0);
        assertEquals(0.5f, h.width(), 1e-5f, "square footprint wide enough for any yaw");
        assertEquals(1.0f, h.height(), 1e-5f);
        assertEquals(0.0f, h.offsetY(), 1e-5f);
        ModelBlueprint.Hitbox scaled = bp.hitbox(2.0);
        assertEquals(1.0f, scaled.width(), 1e-5f);
        assertEquals(2.0f, scaled.height(), 1e-5f);
    }

    @Test
    @DisplayName("a model hanging below the feet yields a negative hitbox offset")
    void hitboxBelowFeet() {
        GeometryModel geo = geometry("""
                {"minecraft:geometry": [{"description": {"identifier": "geometry.deep"}, "bones": [
                  {"name": "b", "cubes": [{"origin": [-1, -8, -1], "size": [2, 16, 2], "uv": [0, 0]}]}]}]}
                """);
        ModelBlueprint.Hitbox h = ModelBlueprint.of(geo, "deep").hitbox(1.0);
        assertEquals(-0.5f, h.offsetY(), 1e-5f);
        assertEquals(1.0f, h.height(), 1e-5f);
    }

    @Test
    @DisplayName("hitbox of an empty model falls back to a small default box")
    void emptyHitbox() {
        GeometryModel geo = geometry("""
                {"minecraft:geometry": [{"description": {"identifier": "geometry.none"}, "bones": [{"name": "b"}]}]}
                """);
        ModelBlueprint.Hitbox h = ModelBlueprint.of(geo, "none").hitbox(1.0);
        assertEquals(0.6f, h.width(), 1e-5f);
        assertEquals(1.8f, h.height(), 1e-5f);
    }
}
