package com.operator.mypack.model;

import com.operator.mypack.content.ParseContext;
import com.operator.mypack.model.geo.GeometryModel;
import com.operator.mypack.model.geo.GeometryParser;
import com.operator.mypack.pack.Issues;
import com.operator.mypack.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.operator.mypack.model.ModelTestSupport.geometry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryParserTest {

    private static final String MODERN = """
            {"format_version": "1.12.0", "minecraft:geometry": [{
              "description": {"identifier": "geometry.Wyvern", "texture_width": 128, "texture_height": 64},
              "bones": [
                {"name": "body", "pivot": [3, 12, -4], "rotation": [10, 20, 30],
                 "cubes": [{"origin": [-4, 12, -2], "size": [8, 12, 4], "uv": [16, 16], "inflate": 0.5}]},
                {"name": "head", "parent": "body", "pivot": [0, 24, 0], "mirror": true,
                 "cubes": [{"origin": [-3, 24, -3], "size": [6, 6, 6], "uv": [0, 0]},
                           {"origin": [1, 25, 2], "size": [1, 2, 1], "pivot": [1.5, 26, 2.5], "rotation": [0, 0, 45], "uv": [40, 0]}]}
              ]}]}
            """;

    @Test
    @DisplayName("modern format: id, texture size, hierarchy and the Bedrock -> Blockbench axis conversion")
    void parsesModernFormat() {
        GeometryModel m = geometry(MODERN);
        assertEquals("aether:geometry.wyvern", m.id().full(), "identifiers are lower-cased and namespaced");
        assertEquals(128, m.textureWidth());
        assertEquals(64, m.textureHeight());
        assertEquals(2, m.bones().size());
        assertEquals(3, m.cubeCount());

        GeometryModel.Bone body = m.bones().get(0);
        assertEquals(-1, body.parent());
        assertEquals(-3.0, body.pivot().x(), 0, "pivot X is mirrored");
        assertEquals(12.0, body.pivot().y(), 0);
        assertEquals(-4.0, body.pivot().z(), 0);
        assertEquals(-10.0, body.rotation().x(), 0, "rotation X is negated");
        assertEquals(-20.0, body.rotation().y(), 0, "rotation Y is negated");
        assertEquals(30.0, body.rotation().z(), 0, "rotation Z keeps its sign");

        GeometryModel.Cube first = body.cubes().get(0);
        assertEquals("c0", first.name());
        assertEquals(-4.0, first.from().x(), 0, "from.x = -(origin.x + size.x) = -(-4 + 8)");
        assertEquals(12.0, first.from().y(), 0);
        assertEquals(-2.0, first.from().z(), 0);
        assertEquals(0.5, first.inflate(), 0);
        assertFalse(first.isRotated());

        GeometryModel.Bone head = m.bones().get(1);
        assertEquals(0, head.parent());
        assertTrue(head.cubes().get(0).mirror(), "bone mirror is inherited");
        GeometryModel.Cube rotated = head.cubes().get(1);
        assertTrue(rotated.isRotated());
        assertEquals(-1.5, rotated.pivot().x(), 0);
        assertEquals(45.0, rotated.rotation().z(), 0);
        assertEquals(-2.0, rotated.from().x(), 0, "-(1 + 1)");
    }

    @Test
    @DisplayName("legacy 1.8 format (geometry.<name> root keys, texturewidth/textureheight) is supported")
    void parsesLegacyFormat() {
        GeometryModel m = geometry("""
                {"format_version": "1.8.0", "geometry.golem:geometry.base": {"texturewidth": 32, "textureheight": 32,
                  "bones": [{"name": "root", "pivot": [0, 0, 0], "cubes": [{"origin": [-1, 0, -1], "size": [2, 2, 2], "uv": [0, 0]}]}]}}
                """);
        assertEquals("aether:geometry.golem", m.id().full(), "inheritance suffix is dropped");
        assertEquals(32, m.textureWidth());
    }

    @Test
    @DisplayName("bones are sorted parent-first even when the file lists children first")
    void sortsParentFirst() {
        GeometryModel m = geometry("""
                {"minecraft:geometry": [{"description": {"identifier": "geometry.x"}, "bones": [
                  {"name": "leaf", "parent": "mid"},
                  {"name": "mid", "parent": "root"},
                  {"name": "root"}]}]}
                """);
        assertEquals("root", m.bones().get(0).name());
        assertEquals("mid", m.bones().get(1).name());
        assertEquals("leaf", m.bones().get(2).name());
        assertEquals(0, m.bones().get(1).parent());
        assertEquals(1, m.bones().get(2).parent());
    }

    @Test
    @DisplayName("problems are tolerated with warnings: unknown parent, cycle, duplicate names, bad cubes, flat cubes")
    void toleratesProblems() {
        Issues issues = new Issues();
        GeometryModel m = geometry("""
                {"minecraft:geometry": [{"description": {"identifier": "geometry.x"}, "bones": [
                  {"name": "a", "parent": "ghost"},
                  {"name": "b", "parent": "c"},
                  {"name": "c", "parent": "b"},
                  {"name": "a"},
                  {"name": "flat", "cubes": [
                      {"origin": [0,0,0], "size": [4, 0, 4], "uv": [0, 0]},
                      {"origin": [0,0,0], "size": [-1, 1, 1], "uv": [0, 0]},
                      {"origin": [0,0,0], "size": [1, 1, 1]},
                      {"size": [1, 1, 1], "uv": [0, 0]}]}
                ]}]}
                """, issues);
        assertEquals(5, m.bones().size());
        assertTrue(m.bones().stream().anyMatch(b -> b.name().equals("a_2")), "duplicate name renamed");
        assertTrue(m.bones().stream().allMatch(b -> b.parent() == -1 || b.parent() < m.bones().size()));
        GeometryModel.Bone flat = m.bones().stream().filter(b -> b.name().equals("flat")).findFirst().orElseThrow();
        assertEquals(1, flat.cubes().size(), "negative size, missing uv and missing origin are skipped");
        assertTrue(flat.cubes().get(0).size().y() > 0, "a flat cube is thickened so its matrix stays invertible");
        assertTrue(issues.count(Issues.Level.WARN) >= 6, issues.all().toString());
        assertFalse(issues.hasErrors());
    }

    @Test
    @DisplayName("neverRender bones are kept in the hierarchy but flagged")
    void neverRender() {
        GeometryModel m = geometry("""
                {"minecraft:geometry": [{"description": {"identifier": "geometry.x"}, "bones": [
                  {"name": "hidden", "neverRender": true, "cubes": [{"origin": [0,0,0], "size": [1,1,1], "uv": [0,0]}]}]}]}
                """);
        assertTrue(m.bones().get(0).neverRender());
    }

    @Test
    @DisplayName("models with too many cubes are rejected (every cube is an entity)")
    void cubeLimit() {
        StringBuilder cubes = new StringBuilder();
        for (int i = 0; i < GeometryModel.MAX_CUBES + 1; i++) {
            if (i > 0) {
                cubes.append(',');
            }
            cubes.append("{\"origin\":[0,0,0],\"size\":[1,1,1],\"uv\":[0,0]}");
        }
        String json = "{\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.big\"},\"bones\":[{\"name\":\"b\",\"cubes\":["
                + cubes + "]}]}]}";
        Issues issues = new Issues();
        List<GeometryModel> models = GeometryParser.parse(JsonUtils.parseObject(json.getBytes(StandardCharsets.UTF_8)),
                new ParseContext("aether", "big.json", issues));
        assertTrue(models.isEmpty());
        assertTrue(issues.hasErrors());
    }

    @Test
    @DisplayName("files without geometry produce an error, not an exception")
    void noGeometry() {
        Issues issues = new Issues();
        assertTrue(GeometryParser.parse(JsonUtils.parseObject("{\"format_version\":\"1.12.0\"}".getBytes(StandardCharsets.UTF_8)),
                new ParseContext("aether", "x.json", issues)).isEmpty());
        assertTrue(issues.hasErrors());
    }
}
