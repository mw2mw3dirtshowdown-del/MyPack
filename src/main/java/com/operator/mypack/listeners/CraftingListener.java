package com.operator.mypack.listeners;

import com.operator.mypack.services.ItemService;
import com.operator.mypack.services.RecipeService;
import org.bukkit.Keyed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

/**
 * A custom item is "paper with an id" - without this guard it would also work as plain paper in every vanilla recipe
 * (books, maps, ...). MyPack's own recipes use exact item matching, so only those may consume custom items.
 */
public final class CraftingListener implements Listener {

    private final ItemService items;
    private final RecipeService recipes;

    public CraftingListener(ItemService items, RecipeService recipes) {
        this.items = items;
        this.recipes = recipes;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepare(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (recipe == null || event.isRepair()) {
            return;
        }
        if (recipe instanceof Keyed keyed && recipes.keys().contains(keyed.getKey())) {
            return;
        }
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (items.idOf(ingredient) != null) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }
}
