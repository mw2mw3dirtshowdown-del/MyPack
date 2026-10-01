package com.operator.mypack.content.mob;

import java.util.Map;

/**
 * Connects a mob or furniture definition to a geometry, its texture and its animations.
 *
 * @param geometry   full geometry id ({@code ns:geometry.name}, lower-case)
 * @param texture    texture name below {@code textures/entity/} (without extension)
 * @param scale      uniform model scale
 * @param yOffset    vertical offset of the model in blocks
 * @param animations animation id per state ({@code idle, walk, attack, hurt, death}); values are full animation ids
 */
public record ModelBinding(String geometry, String texture, double scale, double yOffset, Map<String, String> animations) {

    public static final String IDLE = "idle";
    public static final String WALK = "walk";
    public static final String ATTACK = "attack";
    public static final String HURT = "hurt";
    public static final String DEATH = "death";
}
