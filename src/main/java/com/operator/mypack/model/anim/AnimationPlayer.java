package com.operator.mypack.model.anim;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Plays animations for one model instance: a looping <em>base</em> animation (idle, walk, ...) that cross-fades when it
 * changes, plus one-shot animations (attack, hurt, death) layered on top. Time is measured in server ticks so the
 * player is deterministic and independent of wall-clock jitter.
 */
public final class AnimationPlayer {

    /** Ticks of cross-fade between two base animations. */
    public static final int BASE_FADE_TICKS = 4;
    /** Ticks a one-shot fades in / out. */
    public static final int ONE_SHOT_FADE_TICKS = 2;
    private static final double TICKS_PER_SECOND = 20.0D;

    private static final class Track {
        final Animation animation;
        final long startTick;
        final int fadeInTicks;
        final int fadeOutTicks;
        final boolean oneShot;
        long fadeOutStart = -1L;

        Track(Animation animation, long startTick, int fadeInTicks, int fadeOutTicks, boolean oneShot) {
            this.animation = animation;
            this.startTick = startTick;
            this.fadeInTicks = fadeInTicks;
            this.fadeOutTicks = fadeOutTicks;
            this.oneShot = oneShot;
        }

        double weight(long now) {
            double in = fadeInTicks <= 0 ? 1.0D : Math.min(1.0D, (now - startTick) / (double) fadeInTicks);
            double out = 1.0D;
            if (fadeOutStart >= 0) {
                out = fadeOutTicks <= 0 ? 0.0D : 1.0D - Math.min(1.0D, (now - fadeOutStart) / (double) fadeOutTicks);
            }
            return in * out;
        }

        double localTime(long now) {
            double t = Math.max(0L, now - startTick) / TICKS_PER_SECOND;
            double length = animation.length();
            if (length <= 0.0D) {
                return 0.0D;
            }
            return switch (animation.loop()) {
                case LOOP -> t % length;
                case ONCE, HOLD -> Math.min(t, length);
            };
        }

        boolean finished(long now) {
            return animation.loop() == Animation.Loop.ONCE
                    && Math.max(0L, now - startTick) / TICKS_PER_SECOND >= animation.length();
        }
    }

    private final List<Track> tracks = new ArrayList<>();
    private Track base;

    /** Switches the looping base animation; a no-op when it is already playing. {@code null} fades the base out. */
    public void setBase(Animation next, long now) {
        if (base != null && next != null && base.animation == next && base.fadeOutStart < 0) {
            return;
        }
        if (base != null && base.fadeOutStart < 0) {
            base.fadeOutStart = now;
        }
        if (next == null) {
            base = null;
            return;
        }
        base = new Track(next, now, tracks.isEmpty() ? 0 : BASE_FADE_TICKS, BASE_FADE_TICKS, false);
        tracks.add(base);
    }

    /** Starts a one-shot animation on top of whatever is playing. */
    public void playOneShot(Animation animation, long now) {
        // restarting the same one-shot replaces the running copy
        for (Track track : tracks) {
            if (track.oneShot && track.animation == animation && track.fadeOutStart < 0) {
                track.fadeOutStart = now - ONE_SHOT_FADE_TICKS; // fade the old copy out immediately
            }
        }
        tracks.add(new Track(animation, now, ONE_SHOT_FADE_TICKS, ONE_SHOT_FADE_TICKS, true));
    }

    public boolean isPlayingOneShot(long now) {
        for (Track track : tracks) {
            if (track.oneShot && track.fadeOutStart < 0 && !track.finished(now)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasTracks() {
        return !tracks.isEmpty();
    }

    /** The base animation currently targeted (not one that is merely fading out), or {@code null}. */
    public Animation currentBase() {
        return base == null ? null : base.animation;
    }

    public void clear() {
        tracks.clear();
        base = null;
    }

    /**
     * Writes the combined pose for tick {@code now} into {@code out} (which is reset first). Finished tracks are
     * removed, so a model with nothing left to play can be skipped by the ticker.
     */
    public void evaluate(long now, MolangContext ctx, ToIntFunction<String> boneIndex, PoseSet out) {
        out.reset();
        Iterator<Track> it = tracks.iterator();
        while (it.hasNext()) {
            Track track = it.next();
            if (track.oneShot && track.fadeOutStart < 0 && track.finished(now)) {
                track.fadeOutStart = now;
            }
            double weight = track.weight(now);
            if (track.fadeOutStart >= 0 && weight <= 0.0D) {
                it.remove();
                continue;
            }
            ctx.setQuery("anim_time", track.localTime(now));
            AnimationSampler.apply(track.animation, track.localTime(now), ctx, boneIndex, out, weight);
        }
    }
}
