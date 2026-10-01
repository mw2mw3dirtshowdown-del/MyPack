package com.operator.mypack.content.mob;

import com.operator.mypack.content.ContentId;

import java.util.List;

/**
 * Placeable decoration. The visual is either a 3D model or (without {@code model}) the flat icon item shown by a
 * single display entity; an {@code Interaction} entity provides the hitbox for clicking, sitting and removal.
 *
 * @param iconTexture  texture below {@code textures/items/}; also the icon of the item that places the furniture
 * @param model        optional 3D model
 * @param width        hitbox width in blocks ({@code <= 0} derives it from the model)
 * @param height       hitbox height in blocks ({@code <= 0} derives it from the model)
 * @param seats        seat offsets relative to the furniture origin; empty means it cannot be sat on
 * @param rotationStep placement rotation snapping in degrees (0 = free rotation)
 */
public record FurnitureDefinition(
        ContentId id,
        String displayName,
        List<String> lore,
        String iconTexture,
        ModelBinding model,
        double width,
        double height,
        List<Seat> seats,
        int rotationStep) {

    /** A place for one rider, in blocks relative to the furniture origin. */
    public record Seat(double x, double y, double z) {
    }
}
