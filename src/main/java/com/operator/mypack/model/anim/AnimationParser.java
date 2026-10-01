package com.operator.mypack.model.anim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.operator.mypack.content.ParseContext;
import com.operator.mypack.model.anim.Animation.BoneChannels;
import com.operator.mypack.model.anim.Animation.Channel;
import com.operator.mypack.model.anim.Animation.Interpolation;
import com.operator.mypack.model.anim.Animation.Keyframe;
import com.operator.mypack.model.anim.Animation.Loop;
import com.operator.mypack.model.anim.Animation.Vec;
import com.operator.mypack.utils.JsonUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses {@code animations/*.animation.json}. */
public final class AnimationParser {

    private AnimationParser() {
    }

    public static List<Animation> parse(JsonObject root, ParseContext ctx) {
        JsonObject animations = JsonUtils.object(root, "animations").orElse(null);
        if (animations == null) {
            ctx.error("no \"animations\" object");
            return List.of();
        }
        List<Animation> out = new ArrayList<>();
        for (String key : animations.keySet()) {
            JsonElement element = animations.get(key);
            if (!element.isJsonObject()) {
                ctx.warn("animation '" + key + "' is not an object; skipped");
                continue;
            }
            Animation animation = animation(key.trim().toLowerCase(Locale.ROOT), element.getAsJsonObject(), ctx);
            if (animation != null) {
                out.add(animation);
            }
        }
        return out;
    }

    private static Animation animation(String name, JsonObject o, ParseContext ctx) {
        Loop loop = Loop.ONCE;
        JsonElement loopElement = o.get("loop");
        if (loopElement != null && loopElement.isJsonPrimitive()) {
            if (loopElement.getAsJsonPrimitive().isBoolean()) {
                loop = loopElement.getAsBoolean() ? Loop.LOOP : Loop.ONCE;
            } else if ("hold_on_last_frame".equalsIgnoreCase(loopElement.getAsString())) {
                loop = Loop.HOLD;
            }
        }
        boolean override = JsonUtils.bool(o, "override_previous_animation", false);

        Map<String, BoneChannels> bones = new LinkedHashMap<>();
        double maxTime = 0.0D;
        JsonObject bonesJson = JsonUtils.object(o, "bones").orElse(new JsonObject());
        for (String boneName : bonesJson.keySet()) {
            JsonElement boneElement = bonesJson.get(boneName);
            if (!boneElement.isJsonObject()) {
                continue;
            }
            JsonObject b = boneElement.getAsJsonObject();
            Channel rotation = channel(b.get("rotation"), name, boneName, ctx);
            Channel position = channel(b.get("position"), name, boneName, ctx);
            Channel scale = channel(b.get("scale"), name, boneName, ctx);
            for (Channel c : new Channel[]{rotation, position, scale}) {
                if (c != null) {
                    maxTime = Math.max(maxTime, c.keys().get(c.keys().size() - 1).time());
                }
            }
            if (rotation != null || position != null || scale != null) {
                bones.put(boneName.trim().toLowerCase(Locale.ROOT), new BoneChannels(rotation, position, scale));
            }
        }
        double length = o.has("animation_length") ? JsonUtils.number(o, "animation_length", maxTime) : maxTime;
        if (Double.isNaN(length) || length < 0.0D || length > 3600.0D) {
            ctx.warn(name + ": animation_length is invalid; using the last keyframe time");
            length = maxTime;
        }
        return new Animation(name, length, loop, override, bones);
    }

    /** Reads a channel value: constant vector, uniform scalar/expression, or a time-keyed object. */
    private static Channel channel(JsonElement e, String animation, String bone, ParseContext ctx) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        List<Keyframe> keys = new ArrayList<>();
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("post") || o.has("pre")) {
                Keyframe k = keyframe(0.0D, e, animation, bone, ctx);
                if (k != null) {
                    keys.add(k);
                }
            } else {
                for (String timeKey : o.keySet()) {
                    double time;
                    try {
                        time = Double.parseDouble(timeKey.trim());
                    } catch (NumberFormatException ex) {
                        ctx.warn(animation + "/" + bone + ": keyframe time '" + timeKey + "' is not a number; skipped");
                        continue;
                    }
                    Keyframe k = keyframe(time, o.get(timeKey), animation, bone, ctx);
                    if (k != null) {
                        keys.add(k);
                    }
                }
            }
        } else {
            Keyframe k = keyframe(0.0D, e, animation, bone, ctx);
            if (k != null) {
                keys.add(k);
            }
        }
        if (keys.isEmpty()) {
            return null;
        }
        keys.sort(Comparator.comparingDouble(Keyframe::time));
        return new Channel(List.copyOf(keys));
    }

    private static Keyframe keyframe(double time, JsonElement e, String animation, String bone, ParseContext ctx) {
        String where = animation + "/" + bone;
        Interpolation interpolation = Interpolation.LINEAR;
        Vec post;
        Vec pre;
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            String mode = JsonUtils.string(o, "lerp_mode", "linear").toLowerCase(Locale.ROOT);
            if (mode.equals("catmullrom")) {
                interpolation = Interpolation.CATMULL_ROM;
            } else if (mode.equals("step")) {
                interpolation = Interpolation.STEP;
            } else if (mode.equals("bezier")) {
                ctx.warn(where + ": bezier interpolation is approximated with catmullrom");
                interpolation = Interpolation.CATMULL_ROM;
            }
            post = vec(o.get("post"), where, ctx);
            pre = o.has("pre") ? vec(o.get("pre"), where, ctx) : post;
            if (post == null) {
                post = pre;
            }
        } else {
            post = vec(e, where, ctx);
            pre = post;
        }
        if (post == null || pre == null) {
            return null;
        }
        return new Keyframe(time, pre, post, interpolation);
    }

    private static Vec vec(JsonElement e, String where, ParseContext ctx) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e.isJsonArray()) {
            JsonArray array = e.getAsJsonArray();
            if (array.size() != 3) {
                ctx.warn(where + ": a keyframe vector must have 3 components; skipped");
                return null;
            }
            return new Vec(expr(array.get(0), where, ctx), expr(array.get(1), where, ctx), expr(array.get(2), where, ctx));
        }
        Molang.Expr uniform = expr(e, where, ctx);
        return new Vec(uniform, uniform, uniform);
    }

    private static Molang.Expr expr(JsonElement e, String where, ParseContext ctx) {
        if (!e.isJsonPrimitive()) {
            ctx.warn(where + ": keyframe component is not a number or expression; using 0");
            return Molang.constant(0.0D);
        }
        if (e.getAsJsonPrimitive().isNumber()) {
            return Molang.constant(e.getAsDouble());
        }
        String text = e.getAsString();
        return Molang.compileOrZero(text, message -> ctx.warn(where + ": " + message));
    }
}
