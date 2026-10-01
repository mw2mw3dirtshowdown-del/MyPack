package com.operator.mypack.pack;

import com.operator.mypack.content.item.ItemDefinition;
import com.operator.mypack.content.loot.LootTable;
import com.operator.mypack.content.mob.FurnitureDefinition;
import com.operator.mypack.content.mob.MobDefinition;
import com.operator.mypack.content.recipe.RecipeDefinition;
import com.operator.mypack.content.sound.SoundEvent;
import com.operator.mypack.model.anim.Animation;
import com.operator.mypack.model.geo.GeometryModel;

import java.util.List;

/** Everything one pack declares, parsed and validated for syntax (cross references are checked later). */
public record PackContent(
        List<ItemDefinition> items,
        List<RecipeDefinition> recipes,
        List<LootTable> lootTables,
        List<MobDefinition> mobs,
        List<FurnitureDefinition> furniture,
        List<SoundEvent> sounds,
        List<GeometryModel> geometries,
        List<Animation> animations) {

    public static final PackContent EMPTY = new PackContent(List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of());

    public int totalCount() {
        return items.size() + recipes.size() + lootTables.size() + mobs.size() + furniture.size() + sounds.size()
                + geometries.size() + animations.size();
    }
}
