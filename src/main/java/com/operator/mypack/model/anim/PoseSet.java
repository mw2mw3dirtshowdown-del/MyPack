package com.operator.mypack.model.anim;

/** One {@link Pose} per bone, indexed like the bone list of the model blueprint. */
public final class PoseSet {

    private final Pose[] poses;

    public PoseSet(int boneCount) {
        poses = new Pose[boneCount];
        for (int i = 0; i < boneCount; i++) {
            poses[i] = new Pose();
        }
    }

    public int size() {
        return poses.length;
    }

    public Pose get(int bone) {
        return poses[bone];
    }

    public void reset() {
        for (Pose pose : poses) {
            pose.reset();
        }
    }
}
