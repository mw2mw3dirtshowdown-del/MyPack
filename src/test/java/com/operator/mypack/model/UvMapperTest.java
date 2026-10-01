package com.operator.mypack.model;

import com.operator.mypack.model.geo.Face;
import com.operator.mypack.model.geo.GeometryModel;
import com.operator.mypack.model.geo.UvMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.operator.mypack.model.ModelTestSupport.geometry;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Box-UV layout verified against the layout table of Blockbench's Cube preview code. */
class UvMapperTest {

    private static GeometryModel.Cube cube(String size, String uv, String extra) {
        GeometryModel geo = geometry("{\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.t\",\"texture_width\":64,\"texture_height\":64},"
                + "\"bones\":[{\"name\":\"b\",\"cubes\":[{\"origin\":[0,0,0],\"size\":" + size + ",\"uv\":" + uv + extra + "}]}]}]}");
        return geo.bones().get(0).cubes().get(0);
    }

    private static void assertRect(Map<Face, UvMapper.FaceRect> faces, Face face, double u1, double v1, double u2, double v2) {
        UvMapper.FaceRect r = faces.get(face);
        assertArrayEquals(new double[]{u1, v1, u2, v2}, new double[]{r.u1(), r.v1(), r.u2(), r.v2()}, 1e-9, face.name());
    }

    @Test
    @DisplayName("box UV of a 4x8x4 cube at (0,0) follows the east|north|west|south strip with flipped up/down")
    void boxLayout() {
        Map<Face, UvMapper.FaceRect> f = UvMapper.faces(cube("[4,8,4]", "[0,0]", ""));
        assertRect(f, Face.EAST, 0, 4, 4, 12);
        assertRect(f, Face.NORTH, 4, 4, 8, 12);
        assertRect(f, Face.WEST, 8, 4, 12, 12);
        assertRect(f, Face.SOUTH, 12, 4, 16, 12);
        assertRect(f, Face.UP, 8, 4, 4, 0);      // flipped on both axes
        assertRect(f, Face.DOWN, 12, 0, 8, 4);   // flipped horizontally
        assertEquals(6, f.size());
    }

    @Test
    @DisplayName("the UV offset shifts every face")
    void boxOffset() {
        Map<Face, UvMapper.FaceRect> f = UvMapper.faces(cube("[4,8,4]", "[16,32]", ""));
        assertRect(f, Face.NORTH, 20, 36, 24, 44);
        assertRect(f, Face.DOWN, 28, 32, 24, 36);
    }

    @Test
    @DisplayName("non-cubic sizes use width/height/depth in the right places")
    void nonCubic() {
        Map<Face, UvMapper.FaceRect> f = UvMapper.faces(cube("[6,2,10]", "[0,0]", ""));
        // sx=6 sy=2 sz=10
        assertRect(f, Face.EAST, 0, 10, 10, 12);
        assertRect(f, Face.NORTH, 10, 10, 16, 12);
        assertRect(f, Face.WEST, 16, 10, 26, 12);
        assertRect(f, Face.SOUTH, 26, 10, 32, 12);
        assertRect(f, Face.UP, 16, 10, 10, 0);
        assertRect(f, Face.DOWN, 22, 0, 16, 10);
    }

    @Test
    @DisplayName("mirror flips every face horizontally and swaps east and west")
    void mirrored() {
        Map<Face, UvMapper.FaceRect> f = UvMapper.faces(cube("[4,8,4]", "[0,0]", ",\"mirror\":true"));
        assertRect(f, Face.EAST, 12, 4, 8, 12);
        assertRect(f, Face.WEST, 4, 4, 0, 12);
        assertRect(f, Face.NORTH, 8, 4, 4, 12);
        assertRect(f, Face.SOUTH, 16, 4, 12, 12);
    }

    @Test
    @DisplayName("fractional sizes are floored like Blockbench does for box UV")
    void flooredSizes() {
        Map<Face, UvMapper.FaceRect> f = UvMapper.faces(cube("[4.9,8.5,4.2]", "[0,0]", ""));
        assertRect(f, Face.NORTH, 4, 4, 8, 12);
    }

    @Test
    @DisplayName("Java UV values are scaled into the 0..16 space of the texture size")
    void javaScaling() {
        UvMapper.FaceRect r = new UvMapper.FaceRect(4, 4, 8, 12, 0);
        assertArrayEquals(new double[]{1, 1, 2, 3}, UvMapper.toJavaUv(r, 64, 64), 1e-9);
        assertArrayEquals(new double[]{2, 4, 4, 12}, UvMapper.toJavaUv(r, 32, 16), 1e-9);
    }

    @Test
    @DisplayName("per-face UV: explicit rectangles, default sizes, flipped up/down and omitted faces")
    void perFace() {
        Map<Face, UvMapper.FaceRect> f = UvMapper.faces(cube("[4,8,4]",
                "{\"north\":{\"uv\":[1,2],\"uv_size\":[4,8]},\"up\":{\"uv\":[10,10]},\"east\":{\"uv\":[0,0],\"uv_rotation\":90}}", ""));
        assertRect(f, Face.NORTH, 1, 2, 5, 10);
        assertRect(f, Face.UP, 14, 14, 10, 10);   // default size 4x4, corners swapped
        assertEquals(90, f.get(Face.EAST).rotation());
        assertFalse(f.containsKey(Face.SOUTH));
        assertTrue(f.size() == 3);
    }
}
