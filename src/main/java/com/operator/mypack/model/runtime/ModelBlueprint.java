package com.operator.mypack.model.runtime;

import com.operator.mypack.model.Vec3;
import com.operator.mypack.model.anim.Pose;
import com.operator.mypack.model.anim.PoseSet;
import com.operator.mypack.model.geo.GeometryModel;
import com.operator.mypack.utils.NameUtils;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The runtime form of a {@link GeometryModel}: bones with block-space pivots and one entry per renderable cube with the
 * matrix that maps a display's unit cube onto that cube. {@link #solve} composes the bone hierarchy for a pose; it is
 * the single place where hierarchy, pivots and animation come together, and it is what the unit tests verify against
 * hand-computed numbers.
 */
public final class ModelBlueprint {

    /** A bone with block-space pivot. {@code restRotation} is in degrees. */
    public record BoneNode(String name, int parent, float[] pivot, double[] restRotation, boolean neverRender) {
    }

    /**
     * A renderable cube.
     *
     * @param path  file-name safe key shared by the model JSON, the item definition and the {@code item_model} value
     * @param local matrix mapping the display's unit cube onto the cube in the bone's (rest) space
     */
    public record CubeNode(int bone, String path, GeometryModel.Cube cube, Matrix4f local) {
    }

    /** Axis-aligned bounds in blocks. */
    public record Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    }

    /** Size and vertical offset of an {@code Interaction} entity that covers the model. */
    public record Hitbox(float width, float height, float offsetY) {
    }

    private final GeometryModel geometry;
    private final String modelName;
    private final List<BoneNode> bones;
    private final List<CubeNode> cubes;
    private final Map<String, Integer> boneByName;

    private ModelBlueprint(GeometryModel geometry, String modelName, List<BoneNode> bones, List<CubeNode> cubes,
                           Map<String, Integer> boneByName) {
        this.geometry = geometry;
        this.modelName = modelName;
        this.bones = List.copyOf(bones);
        this.cubes = List.copyOf(cubes);
        this.boneByName = Map.copyOf(boneByName);
    }

    /**
     * @param modelName short name used in generated resource keys (usually the geometry id without the
     *                  {@code geometry.} prefix)
     */
    public static ModelBlueprint of(GeometryModel geometry, String modelName) {
        List<BoneNode> bones = new ArrayList<>();
        List<CubeNode> cubes = new ArrayList<>();
        Map<String, Integer> byName = new HashMap<>();
        Set<String> usedPaths = new HashSet<>();

        for (int i = 0; i < geometry.bones().size(); i++) {
            GeometryModel.Bone bone = geometry.bones().get(i);
            Vec3 p = bone.pivot();
            Vec3 r = bone.rotation();
            bones.add(new BoneNode(bone.name(), bone.parent(),
                    new float[]{(float) (p.x() / 16.0D), (float) (p.y() / 16.0D), (float) (p.z() / 16.0D)},
                    new double[]{r.x(), r.y(), r.z()}, bone.neverRender()));
            byName.put(bone.name().toLowerCase(Locale.ROOT), i);
            if (bone.neverRender()) {
                continue;
            }
            for (GeometryModel.Cube cube : bone.cubes()) {
                String path = NameUtils.cubeModelPath(modelName, bone.name(), cube.name());
                String unique = path;
                int n = 2;
                while (!usedPaths.add(unique)) {
                    unique = path + "_" + n++;
                }
                cubes.add(new CubeNode(i, unique, cube, localMatrix(cube)));
            }
        }
        return new ModelBlueprint(geometry, modelName, bones, cubes, byName);
    }

    private static Matrix4f localMatrix(GeometryModel.Cube cube) {
        double inflate = cube.inflate();
        Vec3 from = cube.from();
        Vec3 size = cube.size();
        double sx = size.x() + 2.0D * inflate;
        double sy = size.y() + 2.0D * inflate;
        double sz = size.z() + 2.0D * inflate;
        float[] center = {
                (float) ((from.x() + size.x() / 2.0D) / 16.0D),
                (float) ((from.y() + size.y() / 2.0D) / 16.0D),
                (float) ((from.z() + size.z() / 2.0D) / 16.0D)};
        float[] extent = {(float) (sx / 16.0D), (float) (sy / 16.0D), (float) (sz / 16.0D)};
        float[] pivot = null;
        double[] rotation = null;
        if (cube.isRotated()) {
            pivot = new float[]{(float) (cube.pivot().x() / 16.0D), (float) (cube.pivot().y() / 16.0D),
                    (float) (cube.pivot().z() / 16.0D)};
            rotation = new double[]{cube.rotation().x(), cube.rotation().y(), cube.rotation().z()};
        }
        return BoneMath.cubeLocal(center, extent, pivot, rotation, new Matrix4f());
    }

    public GeometryModel geometry() {
        return geometry;
    }

    public String modelName() {
        return modelName;
    }

    public List<BoneNode> bones() {
        return bones;
    }

    public List<CubeNode> cubes() {
        return cubes;
    }

    /** Index of the bone with this (case-insensitive) name, or -1. */
    public int boneIndex(String name) {
        Integer index = boneByName.get(name.toLowerCase(Locale.ROOT));
        return index == null ? -1 : index;
    }

    /**
     * Composes the bone hierarchy for {@code poses} and writes one matrix per cube into {@code cubeOut} (same order as
     * {@link #cubes()}). The result maps the display's unit cube to the cube's place in model space (blocks, origin at
     * the entity's feet); {@code root} is applied last (model scale, vertical offset).
     *
     * @param worldScratch per-bone scratch matrices, length {@code bones().size()}
     */
    public void solve(PoseSet poses, Matrix4f root, Matrix4f[] worldScratch, Matrix4f[] cubeOut) {
        Matrix4f local = new Matrix4f();
        for (int i = 0; i < bones.size(); i++) {
            BoneNode bone = bones.get(i);
            Pose pose = poses.get(i);
            BoneMath.boneLocal(bone.pivot(), bone.restRotation(), pose, local);
            if (bone.parent() >= 0) {
                worldScratch[bone.parent()].mul(local, worldScratch[i]);
            } else {
                root.mul(local, worldScratch[i]);
            }
        }
        for (int k = 0; k < cubes.size(); k++) {
            CubeNode cube = cubes.get(k);
            worldScratch[cube.bone()].mul(cube.local(), cubeOut[k]);
        }
    }

    /** Allocates scratch arrays for {@link #solve}. */
    public Matrix4f[] newBoneMatrices() {
        return newMatrices(bones.size());
    }

    public Matrix4f[] newCubeMatrices() {
        return newMatrices(cubes.size());
    }

    private static Matrix4f[] newMatrices(int n) {
        Matrix4f[] out = new Matrix4f[n];
        for (int i = 0; i < n; i++) {
            out[i] = new Matrix4f();
        }
        return out;
    }

    /** Bounds of the model in its rest pose (blocks, relative to the feet), or {@code null} without cubes. */
    public Bounds restBounds() {
        if (cubes.isEmpty()) {
            return null;
        }
        PoseSet rest = new PoseSet(bones.size());
        Matrix4f[] cubeMatrices = newCubeMatrices();
        solve(rest, new Matrix4f(), newBoneMatrices(), cubeMatrices);
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        Vector3f corner = new Vector3f();
        for (Matrix4f m : cubeMatrices) {
            for (int c = 0; c < 8; c++) {
                corner.set((c & 1) == 0 ? -0.5F : 0.5F, (c & 2) == 0 ? -0.5F : 0.5F, (c & 4) == 0 ? -0.5F : 0.5F);
                m.transformPosition(corner);
                minX = Math.min(minX, corner.x);
                minY = Math.min(minY, corner.y);
                minZ = Math.min(minZ, corner.z);
                maxX = Math.max(maxX, corner.x);
                maxY = Math.max(maxY, corner.y);
                maxZ = Math.max(maxZ, corner.z);
            }
        }
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Interaction entity that covers the rest-pose model. An Interaction is axis aligned and does not turn with the mob,
     * so its footprint is a square that must work for every yaw. Taking the farthest point of the model would let a thin
     * tail or wing tip inflate the box until players could hit the mob by clicking empty air, so the radius is the
     * <em>volume-weighted</em> mean of every cube's circumscribed horizontal radius: big body parts dominate, small
     * appendages barely count. Set {@code mypack:hitbox} in the definition to override it.
     *
     * <p>The height spans the whole model. An entity's position is the <em>bottom</em> centre of its box, hence
     * {@code offsetY} is the model's lowest point.</p>
     */
    public Hitbox hitbox(double modelScale) {
        if (cubes.isEmpty()) {
            return new Hitbox(0.6F, 1.8F, 0.0F);
        }
        PoseSet rest = new PoseSet(bones.size());
        Matrix4f[] cubeMatrices = newCubeMatrices();
        solve(rest, new Matrix4f(), newBoneMatrices(), cubeMatrices);
        double weightedRadius = 0.0D;
        double totalVolume = 0.0D;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        Vector3f corner = new Vector3f();
        for (Matrix4f m : cubeMatrices) {
            double volume = Math.abs(m.determinant());
            double radius = 0.0D;
            for (int c = 0; c < 8; c++) {
                corner.set((c & 1) == 0 ? -0.5F : 0.5F, (c & 2) == 0 ? -0.5F : 0.5F, (c & 4) == 0 ? -0.5F : 0.5F);
                m.transformPosition(corner);
                radius = Math.max(radius, Math.hypot(corner.x, corner.z));
                minY = Math.min(minY, corner.y);
                maxY = Math.max(maxY, corner.y);
            }
            weightedRadius += volume * radius;
            totalVolume += volume;
        }
        double radius = totalVolume > 0.0D ? weightedRadius / totalVolume : 0.3D;
        float bottom = Math.min(0.0F, minY);
        float scale = (float) modelScale;
        return new Hitbox(Math.max(0.1F, (float) (2.0D * radius) * scale), Math.max(0.1F, (maxY - bottom) * scale), bottom * scale);
    }
}
