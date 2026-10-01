package com.operator.mypack.content.mob;

import com.operator.mypack.content.ContentId;

import java.util.List;

/**
 * A custom mob. A vanilla entity of type {@code baseEntity} carries the AI, health and collision; it is made invisible
 * and the 3D model is shown through display entities that follow it.
 *
 * @param displayName          MiniMessage custom name or {@code null}
 * @param baseEntity           Bukkit {@code EntityType} name of the carrier (must be a living mob)
 * @param maxHealth            applied as max-health attribute and current health
 * @param movementSpeed        {@code <= 0} keeps the vanilla speed of the base entity
 * @param attackDamage         {@code < 0} keeps the vanilla damage
 * @param followRange          {@code <= 0} keeps the vanilla value
 * @param knockbackResistance  {@code < 0} keeps the vanilla value
 * @param scale                applied to the base entity's scale attribute and to the model
 * @param lootTable            full loot table id or {@code null} (no drops at all, vanilla drops are always removed)
 * @param model                3D model binding or {@code null} (a plain re-statted vanilla mob)
 * @param hitbox               optional explicit hitbox; {@code null} derives it from the model bounds
 */
public record MobDefinition(
        ContentId id,
        String displayName,
        boolean nameVisible,
        String baseEntity,
        double maxHealth,
        double movementSpeed,
        double attackDamage,
        double followRange,
        double knockbackResistance,
        double scale,
        boolean silent,
        boolean persistent,
        boolean burnsInDaylight,
        String lootTable,
        ModelBinding model,
        Hitbox hitbox,
        List<String> families) {

    /** Explicit hitbox size in blocks. */
    public record Hitbox(double width, double height) {
    }
}
