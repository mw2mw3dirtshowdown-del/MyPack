package com.operator.mypack.model.geo;

import com.operator.mypack.content.ContentId;
import com.operator.mypack.model.Vec3;

import java.util.List;
import java.util.Map;

/**
 * A parsed Bedrock geometry ({@code *.geo.json}). All coordinates are stored in <em>Blockbench / Java space</em>
 * and in pixels (1/16 block): the Bedrock X axis is mirrored while parsing (pivot X and cube origin X are negated,
 * rotation X and Y are negated), exactly like Blockbench does when it imports a Bedrock model. Downstream code can
 * therefore treat the model like any Java model.
 *
 * @param id            {@code <namespace>:geometry.<name>}
 * @param textureWidth  pixel width the UV coordinates refer to
 * @param textureHeight pixel height the UV coordinates refer to
 * @param bones         bones in parent-first order; {@link Bone#parent()} is an index into this list
 */
public record GeometryModel(ContentId id, int textureWidth, int textureHeight, List<Bone> bones) {

    /** Hard limit protecting the server: every cube becomes one display entity. */
    public static final int MAX_CUBES = 1024;

    /**
     * @param parent     index of the parent bone or {@code -1} for a root bone
     * @param pivot      rotation / scale pivot in pixels (absolute model space)
     * @param rotation   rest rotation in degrees, applied as {@code Rz * Ry * Rx}
     * @param neverRender bone is skipped when building displays
     */
    public record Bone(String name, int parent, Vec3 pivot, Vec3 rotation, boolean neverRender, List<Cube> cubes) {
    }

    /**
     * @param name     unique name inside the bone ({@code c0}, {@code c1}, ...)
     * @param from     minimum corner in pixels (absolute model space), before {@code inflate}
     * @param size     extent in pixels
     * @param inflate  grows the cube by this many pixels on every side
     * @param pivot    rotation pivot in pixels or {@code null} when the cube is not rotated
     * @param rotation cube rotation in degrees or {@code null}
     * @param mirror   mirrors a box-UV layout horizontally
     */
    public record Cube(String name, Vec3 from, Vec3 size, double inflate, Vec3 pivot, Vec3 rotation, boolean mirror,
                       UvSpec uv) {
        public boolean isRotated() {
            return rotation != null && !rotation.isZero();
        }
    }

    /** How the texture region of every face is determined. */
    public sealed interface UvSpec {
        /** Classic Minecraft "box" layout starting at pixel {@code (u, v)}. */
        record Box(double u, double v) implements UvSpec {
        }

        /** Explicit rectangle per face; faces that are absent are not rendered. */
        record PerFace(Map<Face, FaceEntry> faces) implements UvSpec {
        }
    }

    /** A per-face texture rectangle. {@code uSize}/{@code vSize} are {@code NaN} when the file omitted them. */
    public record FaceEntry(double u, double v, double uSize, double vSize, int rotation) {
    }

    public int cubeCount() {
        int n = 0;
        for (Bone bone : bones) {
            n += bone.cubes().size();
        }
        return n;
    }
}
