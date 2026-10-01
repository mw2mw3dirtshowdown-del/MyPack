package com.operator.mypack.model.bake;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.operator.mypack.model.geo.Face;
import com.operator.mypack.model.geo.GeometryModel;
import com.operator.mypack.model.geo.UvMapper;
import com.operator.mypack.model.runtime.ModelBlueprint;

import java.util.Map;

/**
 * Produces the Java resource pack JSON for the cubes of a model.
 *
 * <p>Every cube becomes the same unit cube ({@code from [0,0,0] to [16,16,16]}) carrying that cube's six UV rectangles.
 * Size, position and rotation are <em>not</em> part of the model: they live in the display entity's transformation
 * matrix, which lets cubes of any size or rotation be shown (a Java model element cannot rotate on more than one axis
 * and is limited to 48 pixels). The model has no {@code parent}: a model whose parent chain reaches
 * {@code builtin/generated} (such as {@code item/generated}) is turned into a flat sprite by the game and its elements
 * are discarded.</p>
 */
public final class CubeModelBaker {

    private CubeModelBaker() {
    }

    /**
     * @param textureRef resource location of the texture, e.g. {@code aether:entity/wyvern}
     */
    public static JsonObject cubeModel(ModelBlueprint.CubeNode node, GeometryModel geometry, String textureRef) {
        JsonObject root = new JsonObject();
        JsonObject textures = new JsonObject();
        textures.addProperty("0", textureRef);
        textures.addProperty("particle", textureRef);
        root.add("textures", textures);

        JsonObject element = new JsonObject();
        element.add("from", array(0, 0, 0));
        element.add("to", array(16, 16, 16));
        JsonObject faces = new JsonObject();
        Map<Face, UvMapper.FaceRect> rects = UvMapper.faces(node.cube());
        for (Face face : Face.values()) {
            UvMapper.FaceRect rect = rects.get(face);
            if (rect == null) {
                continue; // a per-face UV model may omit faces on purpose
            }
            double[] uv = UvMapper.toJavaUv(rect, geometry.textureWidth(), geometry.textureHeight());
            JsonObject f = new JsonObject();
            f.add("uv", array(round(uv[0]), round(uv[1]), round(uv[2]), round(uv[3])));
            f.addProperty("texture", "#0");
            if (rect.rotation() != 0) {
                f.addProperty("rotation", rect.rotation());
            }
            faces.add(face.key(), f);
        }
        element.add("faces", faces);
        JsonArray elements = new JsonArray();
        elements.add(element);
        root.add("elements", elements);
        return root;
    }

    /** {@code assets/<ns>/items/<key>.json}: points the {@code item_model} component at the baked model. */
    public static JsonObject itemDefinition(String modelRef) {
        JsonObject root = new JsonObject();
        JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("model", modelRef);
        root.add("model", model);
        return root;
    }

    private static JsonArray array(double... values) {
        JsonArray array = new JsonArray();
        for (double v : values) {
            array.add(v);
        }
        return array;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0D) / 10000.0D;
    }
}
