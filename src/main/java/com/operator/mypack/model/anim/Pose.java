package com.operator.mypack.model.anim;

/**
 * The animated offset of one bone relative to its rest pose, in Blockbench space: position in pixels, rotation in
 * degrees (added to the rest rotation component-wise, like Blockbench does) and a scale multiplier.
 */
public final class Pose {
    public double posX;
    public double posY;
    public double posZ;
    public double rotX;
    public double rotY;
    public double rotZ;
    public double scaleX = 1.0D;
    public double scaleY = 1.0D;
    public double scaleZ = 1.0D;

    public void reset() {
        posX = posY = posZ = 0.0D;
        rotX = rotY = rotZ = 0.0D;
        scaleX = scaleY = scaleZ = 1.0D;
    }
}
