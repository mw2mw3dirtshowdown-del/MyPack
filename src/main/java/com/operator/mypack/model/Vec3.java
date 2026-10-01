package com.operator.mypack.model;

/** Immutable 3-component double vector used by the geometry data model (pixels or degrees depending on context). */
public record Vec3(double x, double y, double z) {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    public static Vec3 of(double[] v) {
        return new Vec3(v[0], v[1], v[2]);
    }

    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 scale(double f) {
        return new Vec3(x * f, y * f, z * f);
    }

    public boolean isZero() {
        return x == 0 && y == 0 && z == 0;
    }
}
