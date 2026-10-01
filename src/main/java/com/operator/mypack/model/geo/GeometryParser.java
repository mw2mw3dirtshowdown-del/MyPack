package com.operator.mypack.model.geo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.operator.mypack.content.ContentId;
import com.operator.mypack.content.ParseContext;
import com.operator.mypack.model.Vec3;
import com.operator.mypack.utils.JsonUtils;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses Bedrock geometry files: the current {@code minecraft:geometry} array (format 1.12+) and the legacy
 * {@code geometry.<name>} root keys (format 1.8). Bad bones and cubes are skipped with a warning.
 */
public final class GeometryParser {

    /** A flat cube (size 0 on an axis) would give a singular matrix, so it is thickened by this many pixels. */
    private static final double MIN_EXTENT = 0.001D;

    private GeometryParser() {
    }

    public static List<GeometryModel> parse(JsonObject root, ParseContext ctx) {
        List<GeometryModel> out = new ArrayList<>();
        JsonArray modern = JsonUtils.array(root, "minecraft:geometry").orElse(null);
        if (modern != null) {
            for (JsonElement element : modern) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject geo = element.getAsJsonObject();
                JsonObject description = JsonUtils.object(geo, "description").orElse(new JsonObject());
                String identifier = JsonUtils.string(description, "identifier", "");
                int w = JsonUtils.integer(description, "texture_width", 64);
                int h = JsonUtils.integer(description, "texture_height", 64);
                GeometryModel model = build(identifier, w, h, geo, ctx);
                if (model != null) {
                    out.add(model);
                }
            }
        }
        for (String key : root.keySet()) {
            if (!key.startsWith("geometry.") || !root.get(key).isJsonObject()) {
                continue;
            }
            JsonObject geo = root.getAsJsonObject(key);
            String identifier = key.contains(":") ? key.substring(0, key.indexOf(':')) : key; // "geometry.a:geometry.b" inherits
            int w = JsonUtils.integer(geo, "texturewidth", JsonUtils.integer(geo, "texture_width", 64));
            int h = JsonUtils.integer(geo, "textureheight", JsonUtils.integer(geo, "texture_height", 64));
            GeometryModel model = build(identifier, w, h, geo, ctx);
            if (model != null) {
                out.add(model);
            }
        }
        if (out.isEmpty()) {
            ctx.error("no geometry found (expected \"minecraft:geometry\" or a \"geometry.<name>\" key)");
        }
        return out;
    }

    private static GeometryModel build(String identifier, int texW, int texH, JsonObject geo, ParseContext ctx) {
        ContentId id;
        try {
            id = new ContentId(ctx.namespace(), identifier.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            ctx.error("geometry identifier '" + identifier + "' is invalid: " + e.getMessage());
            return null;
        }
        if (texW < 1 || texH < 1 || texW > 16384 || texH > 16384) {
            ctx.warn(id + ": texture size " + texW + "x" + texH + " is invalid; using 64x64");
            texW = 64;
            texH = 64;
        }
        JsonArray bonesJson = JsonUtils.array(geo, "bones").orElse(null);
        if (bonesJson == null || bonesJson.isEmpty()) {
            ctx.error(id + " has no bones");
            return null;
        }

        // 1) read bones as raw records, in file order
        List<RawBone> raw = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonElement element : bonesJson) {
            if (!element.isJsonObject()) {
                continue;
            }
            RawBone bone = rawBone(element.getAsJsonObject(), names, ctx, id);
            if (bone != null) {
                raw.add(bone);
            }
        }
        if (raw.isEmpty()) {
            ctx.error(id + " has no usable bones");
            return null;
        }

        // 2) resolve parents and sort parent-first (stable); break cycles
        Map<String, Integer> byName = new HashMap<>();
        for (int i = 0; i < raw.size(); i++) {
            byName.put(raw.get(i).name, i);
        }
        int[] parentOf = new int[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            String parentName = raw.get(i).parent;
            Integer parentIndex = parentName == null ? null : byName.get(parentName);
            if (parentName != null && parentIndex == null) {
                ctx.warn(id + ": bone '" + raw.get(i).name + "' has unknown parent '" + parentName + "'; treated as a root bone");
            }
            parentOf[i] = parentIndex == null ? -1 : parentIndex;
        }
        for (int i = 0; i < raw.size(); i++) {
            Set<Integer> seen = new HashSet<>();
            int cursor = i;
            while (cursor != -1 && seen.add(cursor)) {
                cursor = parentOf[cursor];
            }
            if (cursor != -1) {
                ctx.warn(id + ": bone '" + raw.get(i).name + "' is part of a parent cycle; treated as a root bone");
                parentOf[i] = -1;
            }
        }
        List<Integer> order = new ArrayList<>();
        boolean[] placed = new boolean[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            place(i, parentOf, placed, order);
        }
        int[] newIndex = new int[raw.size()];
        for (int pos = 0; pos < order.size(); pos++) {
            newIndex[order.get(pos)] = pos;
        }

        List<GeometryModel.Bone> bones = new ArrayList<>();
        int cubeCount = 0;
        for (int oldIndex : order) {
            RawBone r = raw.get(oldIndex);
            int parent = parentOf[oldIndex] == -1 ? -1 : newIndex[parentOf[oldIndex]];
            cubeCount += r.cubes.size();
            bones.add(new GeometryModel.Bone(r.name, parent, r.pivot, r.rotation, r.neverRender, List.copyOf(r.cubes)));
        }
        if (cubeCount == 0) {
            ctx.warn(id + " contains no cubes");
        }
        if (cubeCount > GeometryModel.MAX_CUBES) {
            ctx.error(id + " has " + cubeCount + " cubes; the limit is " + GeometryModel.MAX_CUBES
                    + " (every cube is one display entity)");
            return null;
        }
        return new GeometryModel(id, texW, texH, List.copyOf(bones));
    }

    private static void place(int index, int[] parentOf, boolean[] placed, List<Integer> order) {
        if (placed[index]) {
            return;
        }
        if (parentOf[index] != -1) {
            place(parentOf[index], parentOf, placed, order);
        }
        placed[index] = true;
        order.add(index);
    }

    private static final class RawBone {
        String name;
        String parent;
        Vec3 pivot;
        Vec3 rotation;
        boolean neverRender;
        List<GeometryModel.Cube> cubes = new ArrayList<>();
    }

    private static RawBone rawBone(JsonObject b, Set<String> names, ParseContext ctx, ContentId id) {
        String name = JsonUtils.string(b, "name", "").trim();
        if (name.isEmpty()) {
            ctx.warn(id + ": a bone without a name was skipped");
            return null;
        }
        String unique = name;
        int suffix = 2;
        while (!names.add(unique)) {
            unique = name + "_" + suffix++;
        }
        if (!unique.equals(name)) {
            ctx.warn(id + ": duplicate bone name '" + name + "' renamed to '" + unique + "'");
        }
        if (b.has("poly_mesh") || b.has("texture_meshes")) {
            ctx.warn(id + ": bone '" + unique + "' uses poly_mesh/texture_meshes which are not supported; ignored");
        }

        RawBone bone = new RawBone();
        bone.name = unique;
        String parent = JsonUtils.string(b, "parent", null);
        bone.parent = parent == null || parent.isBlank() ? null : parent.trim();
        Vec3 pivot = Vec3.of(JsonUtils.vector(b, "pivot", 3, new double[]{0, 0, 0}));
        Vec3 rotation = Vec3.of(JsonUtils.vector(b, "rotation", 3, new double[]{0, 0, 0}));
        bone.pivot = new Vec3(-pivot.x(), pivot.y(), pivot.z());
        bone.rotation = new Vec3(-rotation.x(), -rotation.y(), rotation.z());
        bone.neverRender = JsonUtils.bool(b, "neverRender", false);
        boolean boneMirror = JsonUtils.bool(b, "mirror", false);
        double boneInflate = JsonUtils.number(b, "inflate", 0.0D);

        int index = 0;
        for (JsonElement element : JsonUtils.array(b, "cubes").orElse(new JsonArray())) {
            if (!element.isJsonObject()) {
                continue;
            }
            GeometryModel.Cube cube = cube(element.getAsJsonObject(), "c" + index, boneMirror, boneInflate, ctx, id, unique);
            index++;
            if (cube != null) {
                bone.cubes.add(cube);
            }
        }
        return bone;
    }

    private static GeometryModel.Cube cube(JsonObject c, String name, boolean boneMirror, double boneInflate,
                                           ParseContext ctx, ContentId id, String boneName) {
        double[] origin = JsonUtils.vector(c, "origin", 3, null);
        double[] size = JsonUtils.vector(c, "size", 3, null);
        if (origin == null || size == null) {
            ctx.warn(id + ": cube " + name + " in bone '" + boneName + "' needs numeric \"origin\" and \"size\"; skipped");
            return null;
        }
        for (double v : size) {
            if (v < 0 || Double.isNaN(v) || Double.isInfinite(v)) {
                ctx.warn(id + ": cube " + name + " in bone '" + boneName + "' has a negative or invalid size; skipped");
                return null;
            }
        }
        GeometryModel.UvSpec uv = uv(c.get("uv"), ctx, id, boneName, name);
        if (uv == null) {
            return null;
        }
        double sx = Math.max(size[0], MIN_EXTENT);
        double sy = Math.max(size[1], MIN_EXTENT);
        double sz = Math.max(size[2], MIN_EXTENT);
        // Bedrock -> Blockbench space: X is mirrored, so the minimum corner is -(origin.x + size.x).
        Vec3 from = new Vec3(-(origin[0] + size[0]), origin[1], origin[2]);
        double[] pivotRaw = JsonUtils.vector(c, "pivot", 3, null);
        double[] rotationRaw = JsonUtils.vector(c, "rotation", 3, null);
        Vec3 pivot = null;
        Vec3 rotation = null;
        if (rotationRaw != null && !(rotationRaw[0] == 0 && rotationRaw[1] == 0 && rotationRaw[2] == 0)) {
            double[] p = pivotRaw != null ? pivotRaw : new double[]{0, 0, 0};
            pivot = new Vec3(-p[0], p[1], p[2]);
            rotation = new Vec3(-rotationRaw[0], -rotationRaw[1], rotationRaw[2]);
        }
        double inflate = c.has("inflate") ? JsonUtils.number(c, "inflate", 0.0D) : boneInflate;
        boolean mirror = c.has("mirror") ? JsonUtils.bool(c, "mirror", false) : boneMirror;
        return new GeometryModel.Cube(name, from, new Vec3(sx, sy, sz), inflate, pivot, rotation, mirror, uv);
    }

    private static GeometryModel.UvSpec uv(JsonElement e, ParseContext ctx, ContentId id, String boneName, String cubeName) {
        if (e != null && e.isJsonArray() && e.getAsJsonArray().size() == 2) {
            try {
                return new GeometryModel.UvSpec.Box(e.getAsJsonArray().get(0).getAsDouble(), e.getAsJsonArray().get(1).getAsDouble());
            } catch (RuntimeException ex) {
                // falls through to the warning below
            }
        } else if (e != null && e.isJsonObject()) {
            Map<Face, GeometryModel.FaceEntry> faces = new EnumMap<>(Face.class);
            JsonObject o = e.getAsJsonObject();
            for (Face face : Face.values()) {
                JsonObject f = JsonUtils.object(o, face.key()).orElse(null);
                if (f == null) {
                    continue;
                }
                double[] uv = JsonUtils.vector(f, "uv", 2, null);
                if (uv == null) {
                    continue;
                }
                double[] sizeVec = JsonUtils.vector(f, "uv_size", 2, new double[]{Double.NaN, Double.NaN});
                int rotation = JsonUtils.integer(f, "uv_rotation", 0);
                if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
                    rotation = 0;
                }
                faces.put(face, new GeometryModel.FaceEntry(uv[0], uv[1], sizeVec[0], sizeVec[1], rotation));
            }
            return new GeometryModel.UvSpec.PerFace(new LinkedHashMap<>(faces));
        }
        ctx.warn(id + ": cube " + cubeName + " in bone '" + boneName + "' has no usable \"uv\"; skipped");
        return null;
    }
}
