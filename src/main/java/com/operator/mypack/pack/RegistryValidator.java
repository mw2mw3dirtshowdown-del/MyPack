package com.operator.mypack.pack;

import com.operator.mypack.content.loot.LootTable;
import com.operator.mypack.content.mob.FurnitureDefinition;
import com.operator.mypack.content.mob.MobDefinition;
import com.operator.mypack.content.mob.ModelBinding;
import com.operator.mypack.content.recipe.RecipeDefinition;

import java.util.Map;
import java.util.TreeMap;

/**
 * Checks references between definitions once every pack is registered: models and animations that mobs use, loot
 * tables, and custom items named by recipes and loot. Vanilla item names are validated later, on the server, where the
 * material registry is available. All findings are warnings: the affected feature is skipped, never the whole pack.
 */
public final class RegistryValidator {

    private RegistryValidator() {
    }

    /** @return warnings grouped by namespace of the definition they belong to */
    public static Map<String, Issues> validate(ContentRegistry.Snapshot s) {
        Map<String, Issues> out = new TreeMap<>();
        for (MobDefinition mob : s.mobs().values()) {
            Issues issues = issuesFor(out, mob.id().namespace());
            String file = "entities/" + mob.id().path();
            checkModel(s, mob.model(), file, issues);
            if (mob.lootTable() != null && !s.lootTables().containsKey(mob.lootTable())) {
                issues.warn(file, "loot table '" + mob.lootTable() + "' is not defined; the mob drops nothing");
            }
        }
        for (FurnitureDefinition furniture : s.furniture().values()) {
            checkModel(s, furniture.model(), "furniture/" + furniture.id().path(), issuesFor(out, furniture.id().namespace()));
        }
        for (RecipeDefinition recipe : s.recipes().values()) {
            Issues issues = issuesFor(out, recipe.id().namespace());
            String file = "recipes/" + recipe.id().path();
            checkItem(s, recipe.result().item(), file, "result", issues);
            switch (recipe) {
                case RecipeDefinition.Shaped shaped -> shaped.key().values().forEach(i -> checkIngredient(s, i, file, issues));
                case RecipeDefinition.Shapeless shapeless -> shapeless.ingredients().forEach(i -> checkIngredient(s, i, file, issues));
                case RecipeDefinition.Cooking cooking -> checkIngredient(s, cooking.input(), file, issues);
            }
        }
        for (LootTable table : s.lootTables().values()) {
            Issues issues = issuesFor(out, table.id().namespace());
            String file = "loot_tables/" + table.id().path();
            for (LootTable.Pool pool : table.pools()) {
                for (LootTable.Entry entry : pool.entries()) {
                    if (entry.type() == LootTable.EntryType.ITEM) {
                        checkItem(s, entry.name(), file, "entry", issues);
                    } else if (entry.type() == LootTable.EntryType.TABLE && !s.lootTables().containsKey(entry.name())) {
                        issues.warn(file, "nested loot table '" + entry.name() + "' is not defined");
                    }
                }
            }
        }
        out.values().removeIf(Issues::isEmpty);
        return out;
    }

    private static void checkModel(ContentRegistry.Snapshot s, ModelBinding model, String file, Issues issues) {
        if (model == null) {
            return;
        }
        if (!s.geometries().containsKey(model.geometry())) {
            issues.warn(file, "geometry '" + model.geometry() + "' is not defined; the model will not be shown");
        }
        for (Map.Entry<String, String> e : model.animations().entrySet()) {
            if (!s.animations().containsKey(e.getValue())) {
                issues.warn(file, "animation '" + e.getValue() + "' for state '" + e.getKey() + "' is not defined");
            }
        }
    }

    private static void checkIngredient(ContentRegistry.Snapshot s, RecipeDefinition.Ingredient ingredient, String file, Issues issues) {
        if (!ingredient.isTag()) {
            checkItem(s, ingredient.item(), file, "ingredient", issues);
        }
    }

    /** Custom (non-vanilla) items must exist in a registered pack. */
    private static void checkItem(ContentRegistry.Snapshot s, String id, String file, String role, Issues issues) {
        if (id.startsWith("minecraft:")) {
            return;
        }
        if (!s.items().containsKey(id) && !s.furniture().containsKey(id)) {
            issues.warn(file, role + " '" + id + "' is not a defined custom item");
        }
    }

    private static Issues issuesFor(Map<String, Issues> map, String namespace) {
        return map.computeIfAbsent(namespace, k -> new Issues());
    }
}
