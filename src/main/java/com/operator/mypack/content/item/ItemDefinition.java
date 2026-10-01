package com.operator.mypack.content.item;

import com.operator.mypack.content.ContentId;

import java.util.List;
import java.util.Map;

/**
 * A custom item as declared in a pack's {@code items/*.json}. Only plain data is stored here (strings, numbers); the
 * Bukkit objects (materials, attributes, enchantments) are resolved when the item is built on the server thread.
 *
 * @param displayName     MiniMessage; never {@code null}
 * @param baseMaterial    Bukkit material name of the underlying vanilla item
 * @param iconTexture     texture name below {@code textures/items/} (without extension) or {@code null}
 * @param javaModel       pack-relative path of a hand-written Java item model, or {@code null}
 * @param maxStackSize    0 keeps the vanilla default
 * @param maxDurability   0 means the item is not damageable
 * @param glint           {@code null} keeps the vanilla behaviour
 * @param customModelData legacy compatibility value, {@code null} when unused
 */
public record ItemDefinition(
        ContentId id,
        String displayName,
        List<String> lore,
        String baseMaterial,
        String iconTexture,
        String javaModel,
        boolean handEquipped,
        int maxStackSize,
        int maxDurability,
        boolean unbreakable,
        Boolean glint,
        String rarity,
        Integer customModelData,
        List<AttributeSpec> attributes,
        Map<String, Integer> enchantments,
        List<String> flags,
        List<String> tags,
        ItemActions actions) {

    /** An attribute modifier applied while the item is held / worn. */
    public record AttributeSpec(String attribute, double amount, String operation, String slot) {
    }

    /** The interactions that can trigger a script. */
    public enum Trigger {
        RIGHT_CLICK("on_right_click"),
        LEFT_CLICK("on_left_click"),
        HIT_ENTITY("on_hit_entity");

        private final String jsonKey;

        Trigger(String jsonKey) {
            this.jsonKey = jsonKey;
        }

        public String jsonKey() {
            return jsonKey;
        }
    }

    /** All scripts of an item plus the shared cooldown applied after any of them ran. */
    public record ItemActions(int cooldownTicks, Map<Trigger, List<ItemAction>> scripts) {
        public static final ItemActions NONE = new ItemActions(0, Map.of());

        public List<ItemAction> forTrigger(Trigger trigger) {
            return scripts.getOrDefault(trigger, List.of());
        }

        public boolean isEmpty() {
            return scripts.isEmpty();
        }
    }

    /** Value of the {@code item_model} component; also the name of the generated item definition file. */
    public String modelKey() {
        return id.full();
    }
}
