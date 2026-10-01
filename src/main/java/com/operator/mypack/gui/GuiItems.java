package com.operator.mypack.gui;

import com.operator.mypack.utils.TextUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** Small factory for the plain items GUIs are built from. All text is MiniMessage with italics switched off. */
public final class GuiItems {

    private GuiItems() {
    }

    public static ItemStack of(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(TextUtils.noItalic(name));
            if (!lore.isEmpty()) {
                meta.lore(lore.stream().map(TextUtils::noItalic).toList());
            }
            hideEverything(meta);
        });
        return stack;
    }

    public static ItemStack of(Material material, Component name) {
        return of(material, name, List.of());
    }

    /** Blank filler that does not show a tooltip. */
    public static ItemStack filler(Material material) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.setHideTooltip(true);
        });
        return stack;
    }

    private static void hideEverything(ItemMeta meta) {
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
    }
}
