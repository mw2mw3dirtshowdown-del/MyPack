package com.operator.mypack.model.anim;

import java.util.List;
import java.util.Map;

/**
 * One Bedrock animation. Keyframe values are Molang expressions (plain numbers are constants) in <em>Bedrock</em>
 * axis conventions; the sampler converts them to the Blockbench/Java space used by the geometry.
 *
 * @param name             lower-case animation id, e.g. {@code animation.wyvern.idle}
 * @param length           duration in seconds
 * @param overridePrevious replaces (instead of adds to) the channels other animations produced for the same bones
 * @param bones            channels per lower-case bone name
 */
public record Animation(String name, double length, Loop loop, boolean overridePrevious, Map<String, BoneChannels> bones) {

    public enum Loop {
        /** Plays once, then the pose returns to rest. */
        ONCE,
        /** Repeats forever. */
        LOOP,
        /** Plays once and keeps the last frame. */
        HOLD
    }

    public enum Interpolation {
        LINEAR, CATMULL_ROM, STEP
    }

    /** A three-component keyframe value. */
    public record Vec(Molang.Expr x, Molang.Expr y, Molang.Expr z) {
        public void eval(MolangContext ctx, double[] out) {
            out[0] = x.eval(ctx);
            out[1] = y.eval(ctx);
            out[2] = z.eval(ctx);
        }
    }

    /**
     * @param time seconds
     * @param pre  value approaching the keyframe
     * @param post value leaving the keyframe (equal to {@code pre} unless the file splits them)
     */
    public record Keyframe(double time, Vec pre, Vec post, Interpolation interpolation) {
    }

    /** Time-sorted keyframes of one channel. */
    public record Channel(List<Keyframe> keys) {
    }

    /** Rotation, position and scale channels of one bone; absent channels are {@code null}. */
    public record BoneChannels(Channel rotation, Channel position, Channel scale) {
    }
}
