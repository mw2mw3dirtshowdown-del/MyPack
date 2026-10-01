package com.operator.mypack.model;

import com.operator.mypack.content.ParseContext;
import com.operator.mypack.model.geo.GeometryModel;
import com.operator.mypack.model.geo.GeometryParser;
import com.operator.mypack.pack.Issues;
import com.operator.mypack.utils.JsonUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Shared helpers: parsing geometry from JSON text and plain-double rotation math used as the test oracle. */
final class ModelTestSupport {

    private ModelTestSupport() {
    }

    static GeometryModel geometry(String json, Issues issues) {
        ParseContext ctx = new ParseContext("aether", "geo.json", issues);
        List<GeometryModel> models = GeometryParser.parse(JsonUtils.parseObject(json.getBytes(StandardCharsets.UTF_8)), ctx);
        assertEquals(1, models.size(), issues.all().toString());
        return models.get(0);
    }

    static GeometryModel geometry(String json) {
        return geometry(json, new Issues());
    }

    // ---- independent oracle: right-handed rotations of a point about the three axes, angle in degrees ----

    static double[] rotX(double[] p, double deg) {
        double c = Math.cos(Math.toRadians(deg));
        double s = Math.sin(Math.toRadians(deg));
        return new double[]{p[0], p[1] * c - p[2] * s, p[1] * s + p[2] * c};
    }

    static double[] rotY(double[] p, double deg) {
        double c = Math.cos(Math.toRadians(deg));
        double s = Math.sin(Math.toRadians(deg));
        return new double[]{p[0] * c + p[2] * s, p[1], -p[0] * s + p[2] * c};
    }

    static double[] rotZ(double[] p, double deg) {
        double c = Math.cos(Math.toRadians(deg));
        double s = Math.sin(Math.toRadians(deg));
        return new double[]{p[0] * c - p[1] * s, p[0] * s + p[1] * c, p[2]};
    }

    /** Rotates {@code p} about {@code pivot}. */
    static double[] about(double[] pivot, double[] p, java.util.function.UnaryOperator<double[]> rotation) {
        double[] rel = {p[0] - pivot[0], p[1] - pivot[1], p[2] - pivot[2]};
        double[] r = rotation.apply(rel);
        return new double[]{pivot[0] + r[0], pivot[1] + r[1], pivot[2] + r[2]};
    }
}
