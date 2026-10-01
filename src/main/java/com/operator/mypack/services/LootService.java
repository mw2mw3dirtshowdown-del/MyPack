package com.operator.mypack.services;

import com.operator.mypack.content.loot.LootEvaluator;
import com.operator.mypack.content.loot.LootTable;
import com.operator.mypack.pack.ContentRegistry;
import com.operator.mypack.utils.TextUtils;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Rolls loot tables for mobs and for the API. Tables are evaluated by {@link LootEvaluator} (pure and unit tested);
 * this class only turns the resulting drops into item stacks. Because evaluation happens inside MyPack, tables work
 * immediately after a reload and need no datapack or server restart.
 */
public final class LootService {

    private final ContentRegistry registry;
    private final ItemService items;
    private final Logger log;

    public LootService(ContentRegistry registry, ItemService items, Logger log) {
        this.registry = registry;
        this.items = items;
        this.log = log;
    }

    public boolean exists(String tableId) {
        return registry.lootTable(tableId) != null;
    }

    /** Rolls {@code tableId}; an unknown table yields no items. */
    public List<ItemStack> roll(String tableId, LootEvaluator.Context context) {
        LootTable table = registry.lootTable(tableId);
        if (table == null) {
            return List.of();
        }
        List<LootEvaluator.Drop> drops = LootEvaluator.roll(table, context, ThreadLocalRandom.current(), registry::lootTable);
        List<ItemStack> stacks = new ArrayList<>(drops.size());
        for (LootEvaluator.Drop drop : drops) {
            ItemStack stack = items.createFromId(drop.item(), drop.amount());
            if (stack == null) {
                log.fine("Loot table " + tableId + " names unknown item " + drop.item() + "; skipped");
                continue;
            }
            if (drop.name() != null || drop.lore() != null) {
                stack.editMeta(meta -> {
                    if (drop.name() != null) {
                        meta.displayName(TextUtils.mmNoItalic(drop.name()));
                    }
                    if (drop.lore() != null) {
                        meta.lore(TextUtils.mmNoItalic(drop.lore()));
                    }
                });
            }
            stacks.add(stack);
        }
        return stacks;
    }
}
