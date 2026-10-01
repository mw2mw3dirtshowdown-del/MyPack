package com.operator.mypack.content.item;

/**
 * One step of an item's {@code on_right_click} / {@code on_left_click} / {@code on_hit_entity} script. Actions are
 * parsed into these records at load time, so malformed scripts are rejected when the pack is installed and not when a
 * player first uses the item.
 */
public sealed interface ItemAction {

    /** Plays a (vanilla or pack) sound event at the player. */
    record Sound(String sound, float volume, float pitch) implements ItemAction {
    }

    /** Spawns particles in front of the player. */
    record Particle(String particle, int count, double offsetX, double offsetY, double offsetZ, double speed)
            implements ItemAction {
    }

    /** Sends a MiniMessage line to the player. */
    record Message(String text) implements ItemAction {
    }

    /** Runs a command; only executed when {@code packs.security.allow-commands} is enabled. */
    record Command(String executor, String command) implements ItemAction {
        public static final String CONSOLE = "console";
        public static final String PLAYER = "player";
    }

    /** Applies a potion effect to the player. */
    record PotionEffect(String effect, int durationTicks, int amplifier) implements ItemAction {
    }

    /** Ray trace (entities) from the player's eyes; damages the first living entity that is hit. */
    record DamageRay(double range, double damage, double raySize, double knockback, String trailParticle)
            implements ItemAction {
    }

    /** Removes items from the used stack. */
    record Consume(int amount) implements ItemAction {
    }

    /** Applies durability damage to the used item. */
    record Durability(int amount) implements ItemAction {
    }
}
