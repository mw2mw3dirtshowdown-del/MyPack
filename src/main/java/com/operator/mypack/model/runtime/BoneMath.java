package com.operator.mypack.model.runtime;

import com.operator.mypack.model.anim.Pose;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Matrix helpers for bone hierarchies. Convention (identical to Blockbench / Minecraft's model parts): a rotation
 * given as Euler angles {@code (x, y, z)} in degrees is the matrix {@code Rz * Ry * Rx}, i.e. X is applied first.
 * All lengths handed to this class are already in blocks (pixels / 16).
 */
public final class BoneMath {

    /** One pixel of a Blockbench model in blocks. */
    public static final float PIXEL = 1.0F / 16.0F;

    private BoneMath() {
    }

    /** Converts Euler angles in <b>degrees</b> to a quaternion; this is the only place degrees become radians. */
    public static Quaternionf toQuaternion(double xDegrees, double yDegrees, double zDegrees) {
        return new Quaternionf().rotationZYX(
                (float) Math.toRadians(zDegrees),
                (float) Math.toRadians(yDegrees),
                (float) Math.toRadians(xDegrees));
    }

    /**
     * Local transform of a bone: {@code T(pivot + animatedPosition) * R * S * T(-pivot)}. Applying it to a point that
     * belongs to the bone rotates/scales the point around the bone's pivot and then moves it by the animated offset.
     *
     * @param pivot     pivot in blocks
     * @param restRot   rest rotation in degrees
     * @param pose      animated offsets (position in pixels, rotation in degrees added to the rest rotation, scale)
     */
    public static Matrix4f boneLocal(float[] pivot, double[] restRot, Pose pose, Matrix4f dest) {
        return dest.identity()
                .translate(pivot[0] + (float) pose.posX * PIXEL, pivot[1] + (float) pose.posY * PIXEL,
                        pivot[2] + (float) pose.posZ * PIXEL)
                .rotate(toQuaternion(restRot[0] + pose.rotX, restRot[1] + pose.rotY, restRot[2] + pose.rotZ))
                .scale((float) pose.scaleX, (float) pose.scaleY, (float) pose.scaleZ)
                .translate(-pivot[0], -pivot[1], -pivot[2]);
    }

    /**
     * Matrix that maps the unit cube rendered by a display entity (an item model whose single element spans
     * 0..16, i.e. the cube centred on the origin with edge length 1) onto a model cube:
     * {@code [T(pivot) R T(-pivot)] * T(center) * S(size)}.
     *
     * @param center   cube centre in blocks
     * @param size     cube edge lengths in blocks (including inflate)
     * @param pivot    cube rotation pivot in blocks, or {@code null} when the cube is not rotated
     * @param rotation cube rotation in degrees, or {@code null}
     */
    public static Matrix4f cubeLocal(float[] center, float[] size, float[] pivot, double[] rotation, Matrix4f dest) {
        dest.identity();
        if (pivot != null && rotation != null) {
            dest.translate(pivot[0], pivot[1], pivot[2])
                    .rotate(toQuaternion(rotation[0], rotation[1], rotation[2]))
                    .translate(-pivot[0], -pivot[1], -pivot[2]);
        }
        return dest.translate(center[0], center[1], center[2]).scale(size[0], size[1], size[2]);
    }
}
