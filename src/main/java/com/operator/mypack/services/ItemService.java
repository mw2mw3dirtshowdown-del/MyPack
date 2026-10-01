package com.operator.mypack.services;

import com.operator.mypack.content.item.ItemDefinition;
import com.operator.mypack.content.mob.FurnitureDefinition;
import com.operator.mypack.pack.ContentRegistry;
import com.operator.mypack.utils.TextUtils;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.inventory.meta.components.UseCooldownComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Builds and recognises custom items. An item is identified <em>only</em> by the persistent data entry
 * {@code mypack:id} on the stack - never by material, name or lore - so players cannot forge items by renaming and
 * the base material can change freely. The look comes from the {@code item_model} component, which points at the item
 * definition generated into the resource pack.
 */
public final class ItemService {

    private final ContentRegistry registry;
    private final Logger log;
    private final Plugin plugin;
    private final NamespacedKey idKey;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public ItemService(Plugin plugin, ContentRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.log = plugin.getLogger();
        this.idKey = new NamespacedKey(plugin, "id");
    }

    public NamespacedKey idKey() {
        return idKey;
    }

    // ------------------------------------------------------------------ recognition

    /** The custom id stored on the stack ({@code namespace:path}) or {@code null} for any other item. */
    public String idOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        return stack.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    public ItemDefinition definitionOf(ItemStack stack) {
        String id = idOf(stack);
        return id == null ? null : registry.item(id);
    }

    // ------------------------------------------------------------------ creation

    public ItemStack create(ItemDefinition def, int amount) {
        Material material = material(def.baseMaterial(), Material.PAPER, def.id().full());
        int max = def.maxStackSize() > 0 ? def.maxStackSize() : material.getMaxStackSize();
        ItemStack stack = new ItemStack(material, Math.max(1, Math.min(amount, Math.max(1, max))));
        stack.editMeta(meta -> apply(def, meta));
        return stack;
    }

    /** The placeable item of a furniture definition. */
    public ItemStack createFurnitureItem(FurnitureDefinition def, int amount) {
        ItemStack stack = new ItemStack(Material.PAPER, Math.max(1, Math.min(amount, 16)));
        stack.editMeta(meta -> {
            String name = def.displayName() != null && !def.displayName().isBlank() ? def.displayName() : titleCase(def.id().path());
            meta.displayName(TextUtils.mmNoItalic(name));
            if (!def.lore().isEmpty()) {
                meta.lore(TextUtils.mmNoItalic(def.lore()));
            }
            meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, def.id().full());
            meta.setMaxStackSize(16);
            if (def.iconTexture() != null) {
                meta.setItemModel(def.id().toKey());
            }
        });
        return stack;
    }

    /**
     * Item for an identifier: {@code minecraft:*} gives the vanilla item, anything else a registered custom item or
     * furniture item. Returns {@code null} for unknown ids.
     */
    public ItemStack createFromId(String id, int amount) {
        if (id.startsWith("minecraft:")) {
            Material m = Material.matchMaterial(id.substring("minecraft:".length()));
            if (m == null || !m.isItem() || m.isAir()) {
                return null;
            }
            return new ItemStack(m, Math.max(1, Math.min(amount, m.getMaxStackSize())));
        }
        ItemDefinition item = registry.item(id);
        if (item != null) {
            return create(item, amount);
        }
        FurnitureDefinition furniture = registry.furniture(id);
        return furniture != null ? createFurnitureItem(furniture, amount) : null;
    }

    private void apply(ItemDefinition def, ItemMeta meta) {
        meta.displayName(TextUtils.mmNoItalic(def.displayName()));
        if (!def.lore().isEmpty()) {
            meta.lore(TextUtils.mmNoItalic(def.lore()));
        }
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, def.id().full());
        if (def.hasCustomModel()) {
            meta.setItemModel(def.id().toKey());
        }
        if (def.maxStackSize() > 0) {
            meta.setMaxStackSize(def.maxStackSize());
        }
        if (def.maxDurability() > 0 && meta instanceof Damageable damageable) {
            damageable.setMaxDamage(def.maxDurability());
            if (def.maxStackSize() == 0) {
                meta.setMaxStackSize(1);
            }
        }
        if (def.unbreakable()) {
            meta.setUnbreakable(true);
        }
        if (def.glint() != null) {
            meta.setEnchantmentGlintOverride(def.glint());
        }
        if (def.rarity() != null) {
            meta.setRarity(ItemRarity.valueOf(def.rarity()));
        }
        if (def.customModelData() != null) {
            CustomModelDataComponent component = meta.getCustomModelDataComponent();
            component.setFloats(List.of(def.customModelData().floatValue()));
            meta.setCustomModelDataComponent(component);
        }
        applyAttributes(def, meta);
        for (Map.Entry<String, Integer> entry : def.enchantments().entrySet()) {
            Enchantment enchantment = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
                    .get(NamespacedKey.minecraft(entry.getKey()));
            if (enchantment == null) {
                warnOnce(def.id() + " enchantment " + entry.getKey(), "Unknown enchantment '" + entry.getKey() + "' on " + def.id());
                continue;
            }
            meta.addEnchant(enchantment, entry.getValue(), true);
        }
        for (String flag : def.flags()) {
            try {
                meta.addItemFlags(ItemFlag.valueOf(flag));
            } catch (IllegalArgumentException e) {
                warnOnce(def.id() + " flag " + flag, "Unknown item flag '" + flag + "' on " + def.id());
            }
        }
        int cooldown = def.actions().cooldownTicks();
        if (cooldown > 0) {
            UseCooldownComponent use = meta.getUseCooldown();
            use.setCooldownGroup(def.id().toKey());
            use.setCooldownSeconds(cooldown / 20.0F);
            meta.setUseCooldown(use);
        }
    }

    private void applyAttributes(ItemDefinition def, ItemMeta meta) {
        int index = 0;
        for (ItemDefinition.AttributeSpec spec : def.attributes()) {
            Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(spec.attribute()));
            if (attribute == null) {
                warnOnce(def.id() + " attribute " + spec.attribute(), "Unknown attribute '" + spec.attribute() + "' on " + def.id());
                continue;
            }
            AttributeModifier.Operation operation = switch (spec.operation()) {
                case "add_multiplied_base" -> AttributeModifier.Operation.ADD_SCALAR;
                case "add_multiplied_total" -> AttributeModifier.Operation.MULTIPLY_SCALAR_1;
                default -> AttributeModifier.Operation.ADD_NUMBER;
            };
            NamespacedKey modifierKey = new NamespacedKey(plugin, modifierName(def, spec, index++));
            meta.addAttributeModifier(attribute, new AttributeModifier(modifierKey, spec.amount(), operation, slot(spec.slot())));
        }
    }

    private static EquipmentSlotGroup slot(String name) {
        return switch (name) {
            case "mainhand" -> EquipmentSlotGroup.MAINHAND;
            case "offhand" -> EquipmentSlotGroup.OFFHAND;
            case "hand" -> EquipmentSlotGroup.HAND;
            case "head" -> EquipmentSlotGroup.HEAD;
            case "chest" -> EquipmentSlotGroup.CHEST;
            case "legs" -> EquipmentSlotGroup.LEGS;
            case "feet" -> EquipmentSlotGroup.FEET;
            case "armor" -> EquipmentSlotGroup.ARMOR;
            case "body" -> EquipmentSlotGroup.BODY;
            default -> EquipmentSlotGroup.ANY;
        };
    }

    private static String modifierName(ItemDefinition def, ItemDefinition.AttributeSpec spec, int index) {
        String raw = def.id().namespace() + "_" + def.id().path() + "_" + spec.attribute() + "_" + index;
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._\\-]", "_");
    }

    /** Resolves a material name; falls back (with a one-time warning) for unknown or non-item materials. */
    Material material(String name, Material fallback, String owner) {
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isItem() || material.isAir()) {
            warnOnce(owner + " material " + name, owner + ": '" + name + "' is not a valid item material; using " + fallback);
            return fallback;
        }
        return material;
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            log.warning(message);
        }
    }

    private static String titleCase(String path) {
        StringBuilder sb = new StringBuilder();
        for (String word : path.substring(path.lastIndexOf('/') + 1).split("[_\\-.]+")) {
            if (!word.isEmpty()) {
                sb.append(sb.length() == 0 ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return sb.toString();
    }
}
