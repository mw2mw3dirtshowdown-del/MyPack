package com.operator.mypack.model;

import com.operator.mypack.model.anim.Pose;
import com.operator.mypack.model.anim.PoseSet;
import com.operator.mypack.model.geo.GeometryModel;
import com.operator.mypack.model.runtime.BoneMath;
import com.operator.mypack.model.runtime.ModelBlueprint;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.operator.mypack.model.ModelTestSupport.about;
import static com.operator.mypack.model.ModelTestSupport.geometry;
import static com.operator.mypack.model.ModelTestSupport.rotX;
import static com.operator.mypack.model.ModelTestSupport.rotY;
import static com.operator.mypack.model.ModelTestSupport.rotZ;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MANDATORY hierarchy tests: rotate a parent bone and check that the child cube lands where a hand computation says,
 * within 0.01 blocks. The expected values are derived with plain trigonometry in ModelTestSupport, independently of
 * JOML and of the production matrix code.
 */
class BoneMatrixTest {

    private static final float TOL = 0.01f;

    /** Cube centre in world space = what the display matrix does to the centre of its unit cube (the origin). */
    private static Vector3f centreOf(Matrix4f cubeMatrix) {
        return cubeMatrix.transformPosition(new Vector3f(0, 0, 0));
    }

    private static void assertPoint(double[] expectedPx, Vector3f actualBlocks, String message) {
        assertEquals(expectedPx[0] / 16.0, actualBlocks.x, TOL, message + " (x)");
        assertEquals(expectedPx[1] / 16.0, actualBlocks.y, TOL, message + " (y)");
        assertEquals(expectedPx[2] / 16.0, actualBlocks.z, TOL, message + " (z)");
    }

    private static Vector3f solveCube(ModelBlueprint bp, PoseSet poses, int cubeIndex) {
        Matrix4f[] cubes = bp.newCubeMatrices();
        bp.solve(poses, new Matrix4f(), bp.newBoneMatrices(), cubes);
        return centreOf(cubes[cubeIndex]);
    }

    @Test
    @DisplayName("parent bone rotated 30 deg about Z (Bedrock rotation [0,0,30]) moves the child cube along the hand-computed arc")
    void parentRotatedAboutZ() {
        GeometryModel geo = geometry("""
                {"format_version": "1.12.0", "minecraft:geometry": [{
                  "description": {"identifier": "geometry.arm", "texture_width": 64, "texture_height": 64},
                  "bones": [
                    {"name": "upper", "pivot": [0, 16, 0], "rotation": [0, 0, 30]},
                    {"name": "lower", "parent": "upper", "pivot": [0, 8, 0],
                     "cubes": [{"origin": [-2, 0, -2], "size": [4, 8, 4], "uv": [0, 0]}]}
                  ]}]}
                """);
        ModelBlueprint bp = ModelBlueprint.of(geo, "arm");
        // cube centre at rest: (0, 4, 0) px; rotate +30 deg about Z around the parent pivot (0, 16, 0)
        double[] expected = about(new double[]{0, 16, 0}, new double[]{0, 4, 0}, p -> rotZ(p, 30));
        // sanity of the oracle itself: (6, 5.6077, 0)
        assertEquals(6.0, expected[0], 1e-9);
        assertEquals(5.6077, expected[1], 1e-3);
        assertPoint(expected, solveCube(bp, new PoseSet(bp.bones().size()), 0), "child centre after parent Z rotation");
    }

    @Test
    @DisplayName("Bedrock X is mirrored: parent rotated 30 deg about Y with an off-centre pivot")
    void parentRotatedAboutYWithMirroredX() {
        GeometryModel geo = geometry("""
                {"format_version": "1.12.0", "minecraft:geometry": [{
                  "description": {"identifier": "geometry.turret", "texture_width": 64, "texture_height": 64},
                  "bones": [
                    {"name": "turret", "pivot": [8, 0, 0], "rotation": [0, 30, 0]},
                    {"name": "barrel", "parent": "turret", "pivot": [11, 1, 1],
                     "cubes": [{"origin": [10, 0, 0], "size": [2, 2, 2], "uv": [0, 0]}]}
                  ]}]}
                """);
        ModelBlueprint bp = ModelBlueprint.of(geo, "turret");
        // Bedrock -> Blockbench space: pivot x -> -8, rotation y -> -30, cube centre x -> -11
        double[] expected = about(new double[]{-8, 0, 0}, new double[]{-11, 1, 1}, p -> rotY(p, -30));
        assertEquals(-11.098, expected[0], 1e-3);
        assertEquals(-0.634, expected[2], 1e-3);
        assertPoint(expected, solveCube(bp, new PoseSet(bp.bones().size()), 0), "child centre after parent Y rotation");
    }

    @Test
    @DisplayName("two-level chain: animated child rotation composes inside the parent's rotation")
    void twoLevelChainWithAnimatedPose() {
        GeometryModel geo = geometry("""
                {"format_version": "1.12.0", "minecraft:geometry": [{
                  "description": {"identifier": "geometry.arm2", "texture_width": 64, "texture_height": 64},
                  "bones": [
                    {"name": "upper", "pivot": [0, 16, 0], "rotation": [0, 0, 30]},
                    {"name": "lower", "parent": "upper", "pivot": [0, 8, 0],
                     "cubes": [{"origin": [-2, 0, -2], "size": [4, 8, 4], "uv": [0, 0]}]}
                  ]}]}
                """);
        ModelBlueprint bp = ModelBlueprint.of(geo, "arm2");
        PoseSet poses = new PoseSet(bp.bones().size());
        Pose lower = poses.get(bp.boneIndex("lower"));
        lower.rotX = 45; // Blockbench-space animated rotation added to the rest rotation

        // innermost first: the child rotates about its own pivot, then the parent rotates the result
        double[] p = {0, 4, 0};
        p = about(new double[]{0, 8, 0}, p, v -> rotX(v, 45));
        p = about(new double[]{0, 16, 0}, p, v -> rotZ(v, 30));
        assertEquals(5.4142, p[0], 1e-3);
        assertEquals(6.6221, p[1], 1e-3);
        assertEquals(-2.8284, p[2], 1e-3);
        assertPoint(p, solveCube(bp, poses, 0), "child centre with animated child and rotated parent");
    }

    @Test
    @DisplayName("animated position offset is applied in pixels and X is mirrored for Bedrock data")
    void animatedPosition() {
        GeometryModel geo = geometry("""
                {"format_version": "1.12.0", "minecraft:geometry": [{
                  "description": {"identifier": "geometry.slider", "texture_width": 16, "texture_height": 16},
                  "bones": [{"name": "part", "pivot": [0, 0, 0],
                     "cubes": [{"origin": [-1, 0, -1], "size": [2, 2, 2], "uv": [0, 0]}]}]}]}
                """);
        ModelBlueprint bp = ModelBlueprint.of(geo, "slider");
        PoseSet poses = new PoseSet(1);
        poses.get(0).posX = 8; // 8 px = half a block along +X (Blockbench space)
        poses.get(0).posY = 4;
        Vector3f c = solveCube(bp, poses, 0);
        assertEquals(0.5f, c.x, TOL);
        assertEquals(1.0f / 16.0f + 0.25f, c.y, TOL);
        assertEquals(0.0f, c.z, TOL);
    }

    @Test
    @DisplayName("a cube's own rotation turns its corners about the cube pivot")
    void cubeOwnRotation() {
        GeometryModel geo = geometry("""
                {"format_version": "1.12.0", "minecraft:geometry": [{
                  "description": {"identifier": "geometry.spin", "texture_width": 16, "texture_height": 16},
                  "bones": [{"name": "b", "pivot": [0, 0, 0],
                     "cubes": [{"origin": [-8, 0, -8], "size": [16, 16, 16], "pivot": [0, 8, 0], "rotation": [0, 45, 0], "uv": [0, 0]}]}]}]}
                """);
        ModelBlueprint bp = ModelBlueprint.of(geo, "spin");
        Matrix4f[] cubes = bp.newCubeMatrices();
        bp.solve(new PoseSet(1), new Matrix4f(), bp.newBoneMatrices(), cubes);
        // The cube spans x,z in [-8,8] and y in [0,16] px, so the unit-cube corner (+.5,+.5,+.5) is model-space
        // (8,16,8). Bedrock rotation y=45 is -45 in Blockbench space and turns the corner about the pivot (0,8,0).
        double[] expected = about(new double[]{0, 8, 0}, new double[]{8, 16, 8}, p -> rotY(p, -45));
        assertEquals(0.0, expected[0], 1e-9);
        assertEquals(11.3137, expected[2], 1e-3);
        Vector3f corner = cubes[0].transformPosition(new Vector3f(0.5f, 0.5f, 0.5f));
        assertPoint(expected, corner, "rotated cube corner");
    }

    @Test
    @DisplayName("toQuaternion converts degrees to radians and applies X first (R = Rz*Ry*Rx)")
    void quaternionConvention() {
        Vector3f v = new Vector3f(0, 1, 0);
        // X first: (0,1,0) --Rx(90)--> (0,0,1) --Ry(90)--> (1,0,0)
        BoneMath.toQuaternion(90, 90, 0).transform(v);
        assertEquals(1.0f, v.x, 1e-5f);
        assertEquals(0.0f, v.y, 1e-5f);
        assertEquals(0.0f, v.z, 1e-5f);
        // a value that is wrong if radians and degrees are mixed up
        Vector3f w = new Vector3f(1, 0, 0);
        BoneMath.toQuaternion(0, 0, 30).transform(w);
        assertEquals(Math.cos(Math.toRadians(30)), w.x, 1e-5);
        assertEquals(Math.sin(Math.toRadians(30)), w.y, 1e-5);
    }
}
