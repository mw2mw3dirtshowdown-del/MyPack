package com.operator.mypack.model.anim;

import com.operator.mypack.model.anim.Animation.BoneChannels;
import com.operator.mypack.model.anim.Animation.Channel;
import com.operator.mypack.model.anim.Animation.Interpolation;
import com.operator.mypack.model.anim.Animation.Keyframe;

import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Samples animations into {@link PoseSet}s. Bedrock channel values are converted to Blockbench space here: position
 * X is negated, rotation X and Y are negated, scale is unchanged. Contributions of several animations are combined
 * the way Blockbench does it: rotation and position add up, scale multiplies.
 */
public final class AnimationSampler {

    private static final double MIN_SCALE = 1e-5D;

    private AnimationSampler() {
    }

    /**
     * Adds {@code animation} at {@code timeSeconds} (already looped/clamped by the caller) to {@code poses}.
     *
     * @param boneIndex maps a lower-case bone name to its index or a negative number when the model has no such bone
     * @param weight    blend weight in 0..1
     */
    public static void apply(Animation animation, double timeSeconds, MolangContext ctx, ToIntFunction<String> boneIndex,
                             PoseSet poses, double weight) {
        if (weight <= 0.0D) {
            return;
        }
        double[] v = new double[3];
        for (Map.Entry<String, BoneChannels> entry : animation.bones().entrySet()) {
            int index = boneIndex.applyAsInt(entry.getKey());
            if (index < 0 || index >= poses.size()) {
                continue;
            }
            Pose pose = poses.get(index);
            BoneChannels channels = entry.getValue();

            if (channels.rotation() != null) {
                if (animation.overridePrevious()) {
                    pose.rotX = pose.rotY = pose.rotZ = 0.0D;
                }
                sample(channels.rotation(), timeSeconds, ctx, v);
                pose.rotX += -v[0] * weight;
                pose.rotY += -v[1] * weight;
                pose.rotZ += v[2] * weight;
            }
            if (channels.position() != null) {
                if (animation.overridePrevious()) {
                    pose.posX = pose.posY = pose.posZ = 0.0D;
                }
                sample(channels.position(), timeSeconds, ctx, v);
                pose.posX += -v[0] * weight;
                pose.posY += v[1] * weight;
                pose.posZ += v[2] * weight;
            }
            if (channels.scale() != null) {
                if (animation.overridePrevious()) {
                    pose.scaleX = pose.scaleY = pose.scaleZ = 1.0D;
                }
                sample(channels.scale(), timeSeconds, ctx, v);
                pose.scaleX = Math.max(MIN_SCALE, pose.scaleX * (1.0D + (v[0] - 1.0D) * weight));
                pose.scaleY = Math.max(MIN_SCALE, pose.scaleY * (1.0D + (v[1] - 1.0D) * weight));
                pose.scaleZ = Math.max(MIN_SCALE, pose.scaleZ * (1.0D + (v[2] - 1.0D) * weight));
            }
        }
    }

    /** Evaluates one channel at {@code t} (seconds) into {@code out} (Bedrock space, unconverted). */
    static void sample(Channel channel, double t, MolangContext ctx, double[] out) {
        List<Keyframe> keys = channel.keys();
        int n = keys.size();
        Keyframe first = keys.get(0);
        if (n == 1 || t <= first.time()) {
            first.post().eval(ctx, out);
            return;
        }
        Keyframe last = keys.get(n - 1);
        if (t >= last.time()) {
            last.post().eval(ctx, out);
            return;
        }
        int i = 0;
        while (i + 1 < n - 1 && keys.get(i + 1).time() <= t) {
            i++;
        }
        Keyframe a = keys.get(i);
        Keyframe b = keys.get(i + 1);
        double span = b.time() - a.time();
        double alpha = span <= 0.0D ? 1.0D : (t - a.time()) / span;

        double[] p1 = new double[3];
        double[] p2 = new double[3];
        a.post().eval(ctx, p1);
        b.pre().eval(ctx, p2);

        boolean step = a.interpolation() == Interpolation.STEP;
        boolean catmull = a.interpolation() == Interpolation.CATMULL_ROM || b.interpolation() == Interpolation.CATMULL_ROM;
        if (step) {
            System.arraycopy(p1, 0, out, 0, 3);
        } else if (catmull) {
            double[] p0 = new double[3];
            double[] p3 = new double[3];
            (i > 0 ? keys.get(i - 1).post() : a.post()).eval(ctx, p0);
            (i + 2 < n ? keys.get(i + 2).pre() : b.pre()).eval(ctx, p3);
            for (int axis = 0; axis < 3; axis++) {
                out[axis] = catmullRom(p0[axis], p1[axis], p2[axis], p3[axis], alpha);
            }
        } else {
            for (int axis = 0; axis < 3; axis++) {
                out[axis] = p1[axis] + (p2[axis] - p1[axis]) * alpha;
            }
        }
    }

    static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5D * ((2.0D * p1) + (-p0 + p2) * t + (2.0D * p0 - 5.0D * p1 + 4.0D * p2 - p3) * t2
                + (-p0 + 3.0D * p1 - 3.0D * p2 + p3) * t3);
    }
}
