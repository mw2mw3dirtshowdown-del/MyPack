package com.operator.mypack.model.geo;

import java.util.EnumMap;
import java.util.Map;

/**
 * Converts a cube's UV description into one texture rectangle per face, following Blockbench's layout for Bedrock
 * models (box UV) so that the texture appears exactly as it does in the editor.
 *
 * <p>Rectangles are {@code [u1, v1, u2, v2]} in texture pixels; {@code u1 > u2} or {@code v1 > v2} means the face is
 * flipped, which Java model JSON expresses the same way.</p>
 */
public final class UvMapper {

    /** A face rectangle in texture pixels plus an optional 0/90/180/270 texture rotation. */
    public record FaceRect(double u1, double v1, double u2, double v2, int rotation) {
    }

    private UvMapper() {
    }

    public static Map<Face, FaceRect> faces(GeometryModel.Cube cube) {
        return switch (cube.uv()) {
            case GeometryModel.UvSpec.Box box -> boxLayout(cube, box);
            case GeometryModel.UvSpec.PerFace perFace -> perFace(cube, perFace);
        };
    }

    /**
     * Box layout. With {@code (sx, sy, sz)} the floored cube size and {@code (u0, v0)} the UV offset:
     * <pre>
     *            +-------+-------+
     *            |  up   | down  |      row 0 (height sz)
     *   +--------+-------+-------+-------+
     *   |  east  | north | west  | south |  row 1 (height sy)
     *   +--------+-------+-------+-------+
     * </pre>
     * Up and down are drawn upside down and mirrored (negative sizes), exactly as in Blockbench. With
     * {@code mirror} every face is flipped horizontally and east/west swap places.
     */
    static Map<Face, FaceRect> boxLayout(GeometryModel.Cube cube, GeometryModel.UvSpec.Box box) {
        double sx = Math.floor(cube.size().x() + 1e-7);
        double sy = Math.floor(cube.size().y() + 1e-7);
        double sz = Math.floor(cube.size().z() + 1e-7);

        // each entry: {fromX, fromY, sizeX, sizeY}
        Map<Face, double[]> layout = new EnumMap<>(Face.class);
        layout.put(Face.EAST, new double[]{0, sz, sz, sy});
        layout.put(Face.WEST, new double[]{sz + sx, sz, sz, sy});
        layout.put(Face.UP, new double[]{sz + sx, sz, -sx, -sz});
        layout.put(Face.DOWN, new double[]{sz + 2 * sx, 0, -sx, sz});
        layout.put(Face.SOUTH, new double[]{2 * sz + sx, sz, sx, sy});
        layout.put(Face.NORTH, new double[]{sz, sz, sx, sy});

        if (cube.mirror()) {
            for (double[] f : layout.values()) {
                f[0] += f[2];
                f[2] = -f[2];
            }
            double[] east = layout.get(Face.EAST);
            layout.put(Face.EAST, layout.get(Face.WEST));
            layout.put(Face.WEST, east);
        }

        Map<Face, FaceRect> out = new EnumMap<>(Face.class);
        for (Map.Entry<Face, double[]> entry : layout.entrySet()) {
            double[] f = entry.getValue();
            out.put(entry.getKey(), new FaceRect(
                    f[0] + box.u(), f[1] + box.v(),
                    f[0] + f[2] + box.u(), f[1] + f[3] + box.v(), 0));
        }
        return out;
    }

    /**
     * Explicit per-face UVs. A missing {@code uv_size} falls back to the natural size of that face. Up and down use
     * flipped rectangles (Blockbench swaps the corners for those two faces when importing Bedrock models).
     */
    static Map<Face, FaceRect> perFace(GeometryModel.Cube cube, GeometryModel.UvSpec.PerFace spec) {
        double sx = cube.size().x();
        double sy = cube.size().y();
        double sz = cube.size().z();
        Map<Face, FaceRect> out = new EnumMap<>(Face.class);
        for (Map.Entry<Face, GeometryModel.FaceEntry> entry : spec.faces().entrySet()) {
            Face face = entry.getKey();
            GeometryModel.FaceEntry e = entry.getValue();
            double defaultU;
            double defaultV;
            switch (face) {
                case NORTH, SOUTH -> {
                    defaultU = sx;
                    defaultV = sy;
                }
                case EAST, WEST -> {
                    defaultU = sz;
                    defaultV = sy;
                }
                default -> {
                    defaultU = sx;
                    defaultV = sz;
                }
            }
            double w = Double.isNaN(e.uSize()) ? defaultU : e.uSize();
            double h = Double.isNaN(e.vSize()) ? defaultV : e.vSize();
            double u1 = e.u();
            double v1 = e.v();
            double u2 = e.u() + w;
            double v2 = e.v() + h;
            if (face == Face.UP || face == Face.DOWN) {
                out.put(face, new FaceRect(u2, v2, u1, v1, e.rotation()));
            } else {
                out.put(face, new FaceRect(u1, v1, u2, v2, e.rotation()));
            }
        }
        return out;
    }

    /** Scales a pixel rectangle into the 0..16 UV space of Java model JSON. */
    public static double[] toJavaUv(FaceRect rect, int textureWidth, int textureHeight) {
        double fu = 16.0D / textureWidth;
        double fv = 16.0D / textureHeight;
        return new double[]{rect.u1() * fu, rect.v1() * fv, rect.u2() * fu, rect.v2() * fv};
    }
}
