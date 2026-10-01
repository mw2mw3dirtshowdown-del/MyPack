package com.operator.mypack.model;

import com.operator.mypack.content.ParseContext;
import com.operator.mypack.model.anim.Animation;
import com.operator.mypack.model.anim.AnimationParser;
import com.operator.mypack.model.anim.AnimationPlayer;
import com.operator.mypack.model.anim.AnimationSampler;
import com.operator.mypack.model.anim.MolangContext;
import com.operator.mypack.model.anim.Pose;
import com.operator.mypack.model.anim.PoseSet;
import com.operator.mypack.pack.Issues;
import com.operator.mypack.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationTest {

    private static final String FILE = """
            {"format_version": "1.8.0", "animations": {
              "animation.wyvern.idle": {"loop": true, "animation_length": 4.0, "bones": {
                 "Wing": {"rotation": {"0.0": [0, 0, 0], "2.0": [0, 0, 20], "4.0": [0, 0, 0]},
                          "position": {"0.0": [0, 0, 0], "4.0": [4, 2, 0]}}}},
              "animation.wyvern.sweep": {"loop": "hold_on_last_frame", "animation_length": 1.0, "bones": {
                 "wing": {"rotation": [10, 20, 30], "scale": 2}}},
              "animation.wyvern.wave": {"animation_length": 2, "bones": {
                 "wing": {"rotation": ["math.sin(query.anim_time * 90) * 10", 0, 0]}}},
              "animation.wyvern.smooth": {"loop": true, "bones": {
                 "wing": {"rotation": {"0.0": {"post": [0,0,0], "lerp_mode": "catmullrom"},
                                       "1.0": {"post": [0,0,10], "lerp_mode": "catmullrom"},
                                       "2.0": {"post": [0,0,0], "lerp_mode": "catmullrom"}}}}},
              "animation.wyvern.override": {"override_previous_animation": true, "bones": {"wing": {"rotation": [0, 0, 5]}}}
            }}
            """;

    private static Map<String, Animation> parse(Issues issues) {
        List<Animation> list = AnimationParser.parse(JsonUtils.parseObject(FILE.getBytes(StandardCharsets.UTF_8)),
                new ParseContext("aether", "a.json", issues));
        Map<String, Animation> map = new java.util.HashMap<>();
        for (Animation a : list) {
            map.put(a.name(), a);
        }
        return map;
    }

    private static final Map<String, Animation> ANIMS = parse(new Issues());

    private static int bone(String name) {
        return name.equalsIgnoreCase("wing") ? 0 : -1;
    }

    private static Pose sample(String animation, double t) {
        PoseSet poses = new PoseSet(1);
        AnimationSampler.apply(ANIMS.get(animation), t, new MolangContext(new Random(1)), AnimationTest::bone, poses, 1.0);
        return poses.get(0);
    }

    @Test
    @DisplayName("parses loop modes, lengths, bone names (case-insensitive) and all value forms")
    void parsing() {
        assertEquals(5, ANIMS.size());
        Animation idle = ANIMS.get("animation.wyvern.idle");
        assertEquals(Animation.Loop.LOOP, idle.loop());
        assertEquals(4.0, idle.length(), 0);
        assertTrue(idle.bones().containsKey("wing"), "bone keys are lower-cased");
        assertEquals(Animation.Loop.HOLD, ANIMS.get("animation.wyvern.sweep").loop());
        assertEquals(Animation.Loop.ONCE, ANIMS.get("animation.wyvern.wave").loop());
        assertEquals(2.0, ANIMS.get("animation.wyvern.wave").length(), 0);
        assertTrue(ANIMS.get("animation.wyvern.override").overridePrevious());
    }

    @Test
    @DisplayName("linear interpolation: halfway between keyframes, Bedrock rotation Z keeps its sign")
    void linearInterpolation() {
        Pose p = sample("animation.wyvern.idle", 1.0); // halfway 0 -> 20 about Z
        assertEquals(10.0, p.rotZ, 1e-9);
        assertEquals(0.0, p.rotX, 1e-9);
        assertEquals(20.0, sample("animation.wyvern.idle", 2.0).rotZ, 1e-9);
        assertEquals(10.0, sample("animation.wyvern.idle", 3.0).rotZ, 1e-9);
    }

    @Test
    @DisplayName("Bedrock -> Blockbench axis conversion: position X, rotation X and Y are negated")
    void axisConversion() {
        Pose p = sample("animation.wyvern.sweep", 0.5); // constant rotation [10, 20, 30], scale 2
        assertEquals(-10.0, p.rotX, 1e-9);
        assertEquals(-20.0, p.rotY, 1e-9);
        assertEquals(30.0, p.rotZ, 1e-9);
        assertEquals(2.0, p.scaleX, 1e-9);
        Pose pos = sample("animation.wyvern.idle", 2.0); // position halfway to [4, 2, 0]
        assertEquals(-2.0, pos.posX, 1e-9);
        assertEquals(1.0, pos.posY, 1e-9);
    }

    @Test
    @DisplayName("before the first and after the last keyframe the nearest value is held")
    void holdsOutsideRange() {
        assertEquals(0.0, sample("animation.wyvern.idle", -5).rotZ, 1e-9);
        assertEquals(0.0, sample("animation.wyvern.idle", 99).rotZ, 1e-9);
    }

    @Test
    @DisplayName("Molang keyframe values are evaluated with query.anim_time")
    void molangKeyframes() {
        PoseSet poses = new PoseSet(1);
        MolangContext ctx = new MolangContext(new Random(1)).setQuery("anim_time", 1.0);
        AnimationSampler.apply(ANIMS.get("animation.wyvern.wave"), 1.0, ctx, AnimationTest::bone, poses, 1.0);
        assertEquals(-10.0, poses.get(0).rotX, 1e-9, "sin(90deg)*10 = 10, X rotation is negated");
    }

    @Test
    @DisplayName("catmull-rom passes through its keyframes and is smooth between them")
    void catmullRom() {
        assertEquals(10.0, sample("animation.wyvern.smooth", 1.0).rotZ, 1e-9);
        double mid = sample("animation.wyvern.smooth", 0.5).rotZ;
        assertTrue(mid > 0 && mid < 10, "value between neighbours: " + mid);
        assertEquals(sample("animation.wyvern.smooth", 0.5).rotZ, sample("animation.wyvern.smooth", 1.5).rotZ, 1e-9,
                "symmetric keyframes give a symmetric curve");
    }

    @Test
    @DisplayName("blend weights scale rotation and position; scale blends toward the target")
    void weights() {
        PoseSet poses = new PoseSet(1);
        AnimationSampler.apply(ANIMS.get("animation.wyvern.sweep"), 0.5, new MolangContext(), AnimationTest::bone, poses, 0.5);
        assertEquals(15.0, poses.get(0).rotZ, 1e-9);
        assertEquals(1.5, poses.get(0).scaleX, 1e-9, "1 + (2-1)*0.5");
        AnimationSampler.apply(ANIMS.get("animation.wyvern.sweep"), 0.5, new MolangContext(), AnimationTest::bone, poses, 0.0);
        assertEquals(15.0, poses.get(0).rotZ, 1e-9, "weight 0 is a no-op");
    }

    @Test
    @DisplayName("several animations add up, override_previous_animation replaces")
    void layering() {
        PoseSet poses = new PoseSet(1);
        MolangContext ctx = new MolangContext();
        AnimationSampler.apply(ANIMS.get("animation.wyvern.sweep"), 0.5, ctx, AnimationTest::bone, poses, 1.0);
        AnimationSampler.apply(ANIMS.get("animation.wyvern.idle"), 2.0, ctx, AnimationTest::bone, poses, 1.0);
        assertEquals(50.0, poses.get(0).rotZ, 1e-9, "30 + 20");
        AnimationSampler.apply(ANIMS.get("animation.wyvern.override"), 0.0, ctx, AnimationTest::bone, poses, 1.0);
        assertEquals(5.0, poses.get(0).rotZ, 1e-9, "override discards what came before");
    }

    @Test
    @DisplayName("bad keyframes are skipped with warnings, bad expressions become 0")
    void badInput() {
        Issues issues = new Issues();
        List<Animation> list = AnimationParser.parse(JsonUtils.parseObject("""
                {"animations": {"animation.x": {"bones": {"b": {
                    "rotation": {"abc": [1,2,3], "1.0": [1,2], "2.0": ["math.nope(1)", 0, 0], "3.0": [0,0,0]}}}}}}
                """.getBytes(StandardCharsets.UTF_8)), new ParseContext("aether", "x.json", issues));
        assertEquals(1, list.size());
        assertTrue(issues.count(Issues.Level.WARN) >= 3, issues.all().toString());
        assertEquals(2, list.get(0).bones().get("b").rotation().keys().size());
        assertEquals(3.0, list.get(0).length(), 0, "length falls back to the last keyframe time");
    }

    // ------------------------------------------------------------------ player

    private static double rotZAt(AnimationPlayer player, long tick) {
        PoseSet poses = new PoseSet(1);
        player.evaluate(tick, new MolangContext(), AnimationTest::bone, poses);
        return poses.get(0).rotZ;
    }

    @Test
    @DisplayName("the looping base animation advances with ticks and wraps around")
    void playerLoops() {
        AnimationPlayer player = new AnimationPlayer();
        player.setBase(ANIMS.get("animation.wyvern.idle"), 100);
        assertEquals(0.0, rotZAt(player, 100), 1e-9);
        assertEquals(10.0, rotZAt(player, 120), 1e-9, "1 second in");
        assertEquals(20.0, rotZAt(player, 140), 1e-9, "2 seconds in");
        assertEquals(10.0, rotZAt(player, 100 + 4 * 20 + 20), 1e-9, "wrapped: 5 s == 1 s");
    }

    @Test
    @DisplayName("switching the base animation cross-fades instead of snapping")
    void playerCrossFades() {
        AnimationPlayer player = new AnimationPlayer();
        player.setBase(ANIMS.get("animation.wyvern.sweep"), 0); // constant rotZ 30 (weight 1)
        assertEquals(30.0, rotZAt(player, 5), 1e-9);
        player.setBase(ANIMS.get("animation.wyvern.idle"), 10); // idle rotZ 0 at its start
        double halfway = rotZAt(player, 10 + AnimationPlayer.BASE_FADE_TICKS / 2);
        assertTrue(halfway > 10 && halfway < 20, "old weight 0.5 -> 15, got " + halfway);
        // fade finished: only idle contributes, 5 ticks (0.25 s) into its 0 -> 20 ramp over 2 s = 2.5 (the old 30 is gone)
        assertEquals(2.5, rotZAt(player, 10 + AnimationPlayer.BASE_FADE_TICKS + 1), 1e-9, "fade finished");
        assertEquals(ANIMS.get("animation.wyvern.idle"), player.currentBase());
    }

    @Test
    @DisplayName("setting the same base animation again changes nothing")
    void playerIgnoresSameBase() {
        AnimationPlayer player = new AnimationPlayer();
        player.setBase(ANIMS.get("animation.wyvern.idle"), 0);
        player.setBase(ANIMS.get("animation.wyvern.idle"), 50);
        assertEquals(20.0, rotZAt(player, 40), 1e-9, "time is not restarted by the second call");
    }

    @Test
    @DisplayName("one-shot animations layer on top, finish, fade out and are removed")
    void playerOneShot() {
        AnimationPlayer player = new AnimationPlayer();
        player.playOneShot(ANIMS.get("animation.wyvern.wave"), 0); // length 2 s, ONCE
        assertTrue(player.isPlayingOneShot(10));
        assertFalse(player.isPlayingOneShot(2 * 20 + 1), "finished after its length");
        assertTrue(player.hasTracks());
        PoseSet poses = new PoseSet(1);
        player.evaluate(2 * 20 + 1, new MolangContext(), AnimationTest::bone, poses);
        player.evaluate(2 * 20 + 1 + AnimationPlayer.ONE_SHOT_FADE_TICKS + 1, new MolangContext(), AnimationTest::bone, poses);
        assertFalse(player.hasTracks(), "a finished one-shot disappears so idle models can stop ticking");
    }

    @Test
    @DisplayName("hold-on-last-frame one-shots stay until cleared (death animations)")
    void playerHold() {
        AnimationPlayer player = new AnimationPlayer();
        player.playOneShot(ANIMS.get("animation.wyvern.sweep"), 0);
        assertEquals(30.0, rotZAt(player, 1000), 1e-9);
        assertTrue(player.hasTracks());
        player.clear();
        assertFalse(player.hasTracks());
    }
}
