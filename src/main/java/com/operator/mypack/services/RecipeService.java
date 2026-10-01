package com.operator.mypack.services;

import com.operator.mypack.content.recipe.RecipeDefinition;
import com.operator.mypack.content.recipe.RecipeDefinition.CookingType;
import com.operator.mypack.pack.ContentRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmokingRecipe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Registers the pack recipes with the server. {@link #sync} must run on the global region thread (recipe registration
 * touches server-wide state); it removes everything it registered before, so reloads never leave stale recipes behind.
 */
public final class RecipeService {

    private final ItemService items;
    private final Logger log;
    private final Set<NamespacedKey> registered = ConcurrentHashMap.newKeySet();

    public RecipeService(ItemService items, Logger log) {
        this.items = items;
        this.log = log;
    }

    /** Replaces all registered pack recipes with those of {@code snapshot}. Global thread only. */
    public void sync(ContentRegistry.Snapshot snapshot) {
        removeAll(false);
        int added = 0;
        for (RecipeDefinition definition : snapshot.recipes().values()) {
            try {
                for (Recipe recipe : build(definition)) {
                    NamespacedKey key = ((org.bukkit.Keyed) recipe).getKey();
                    if (Bukkit.addRecipe(recipe)) {
                        registered.add(key);
                        added++;
                    } else {
                        log.warning("Recipe " + key + " could not be registered (duplicate key?)");
                    }
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                log.warning("Recipe " + definition.id() + " was rejected by the server: " + e.getMessage());
            }
        }
        Bukkit.updateRecipes();
        log.info("Registered " + added + " recipe(s)");
    }

    /** Removes every recipe this service registered. Global thread only. */
    public void removeAll(boolean resend) {
        for (NamespacedKey key : registered) {
            Bukkit.removeRecipe(key, false);
        }
        registered.clear();
        if (resend) {
            Bukkit.updateRecipes();
        }
    }

    public Collection<NamespacedKey> keys() {
        return Set.copyOf(registered);
    }

    /** Unlocks all pack recipes in the player's recipe book. */
    public void discoverFor(Player player) {
        if (!registered.isEmpty()) {
            player.discoverRecipes(registered);
        }
    }

    // ------------------------------------------------------------------ construction

    private List<Recipe> build(RecipeDefinition definition) {
        List<Recipe> out = new ArrayList<>();
        ItemStack result = items.createFromId(definition.result().item(), definition.result().count());
        if (result == null) {
            log.warning("Recipe " + definition.id() + ": result '" + definition.result().item() + "' is not a known item; skipped");
            return out;
        }
        NamespacedKey key = definition.id().toKey();
        switch (definition) {
            case RecipeDefinition.Shaped shaped -> {
                ShapedRecipe recipe = new ShapedRecipe(key, result);
                recipe.shape(shaped.pattern().toArray(new String[0]));
                for (Map.Entry<Character, RecipeDefinition.Ingredient> entry : shaped.key().entrySet()) {
                    RecipeChoice choice = choice(entry.getValue(), definition);
                    if (choice == null) {
                        return out;
                    }
                    recipe.setIngredient(entry.getKey(), choice);
                }
                if (!shaped.group().isEmpty()) {
                    recipe.setGroup(shaped.group());
                }
                out.add(recipe);
            }
            case RecipeDefinition.Shapeless shapeless -> {
                ShapelessRecipe recipe = new ShapelessRecipe(key, result);
                for (RecipeDefinition.Ingredient ingredient : shapeless.ingredients()) {
                    RecipeChoice choice = choice(ingredient, definition);
                    if (choice == null) {
                        return out;
                    }
                    recipe.addIngredient(choice);
                }
                if (!shapeless.group().isEmpty()) {
                    recipe.setGroup(shapeless.group());
                }
                out.add(recipe);
            }
            case RecipeDefinition.Cooking cooking -> {
                RecipeChoice choice = choice(cooking.input(), definition);
                if (choice == null) {
                    return out;
                }
                for (CookingType type : cooking.types()) {
                    int time = cooking.cookTimeTicks() > 0 ? cooking.cookTimeTicks() : type.defaultCookTime();
                    NamespacedKey typedKey = type == CookingType.FURNACE ? key
                            : new NamespacedKey(key.getNamespace(), key.getKey() + "_" + type.name().toLowerCase(java.util.Locale.ROOT));
                    out.add(cookingRecipe(type, typedKey, result, choice, cooking.experience(), time));
                }
            }
        }
        return out;
    }

    private static CookingRecipe<?> cookingRecipe(CookingType type, NamespacedKey key, ItemStack result, RecipeChoice choice,
                                                  float experience, int time) {
        return switch (type) {
            case FURNACE -> new FurnaceRecipe(key, result, choice, experience, time);
            case BLASTING -> new BlastingRecipe(key, result, choice, experience, time);
            case SMOKING -> new SmokingRecipe(key, result, choice, experience, time);
            case CAMPFIRE -> new CampfireRecipe(key, result, choice, experience, time);
        };
    }

    /** Tags and vanilla items match by material; custom items must match the exact stack (including their id). */
    private RecipeChoice choice(RecipeDefinition.Ingredient ingredient, RecipeDefinition owner) {
        if (ingredient.isTag()) {
            NamespacedKey tagKey = NamespacedKey.fromString(ingredient.tag());
            Tag<Material> tag = tagKey == null ? null : Bukkit.getTag(Tag.REGISTRY_ITEMS, tagKey, Material.class);
            if (tag == null) {
                log.warning("Recipe " + owner.id() + ": unknown item tag '" + ingredient.tag() + "'; recipe skipped");
                return null;
            }
            return new RecipeChoice.MaterialChoice(tag);
        }
        String item = ingredient.item();
        if (item.startsWith("minecraft:")) {
            Material material = Material.matchMaterial(item.substring("minecraft:".length()));
            if (material == null || !material.isItem() || material.isAir()) {
                log.warning("Recipe " + owner.id() + ": unknown item '" + item + "'; recipe skipped");
                return null;
            }
            return new RecipeChoice.MaterialChoice(material);
        }
        ItemStack stack = items.createFromId(item, 1);
        if (stack == null) {
            log.warning("Recipe " + owner.id() + ": unknown custom item '" + item + "'; recipe skipped");
            return null;
        }
        return new RecipeChoice.ExactChoice(stack);
    }
}
