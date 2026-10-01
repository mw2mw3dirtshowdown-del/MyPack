package com.operator.mypack.pack;

import com.google.gson.JsonObject;
import com.operator.mypack.content.ContentId;
import com.operator.mypack.content.ParseContext;
import com.operator.mypack.content.item.ItemDefinition;
import com.operator.mypack.content.item.ItemParser;
import com.operator.mypack.content.loot.LootParser;
import com.operator.mypack.content.loot.LootTable;
import com.operator.mypack.content.mob.FurnitureDefinition;
import com.operator.mypack.content.mob.MobDefinition;
import com.operator.mypack.content.mob.MobParser;
import com.operator.mypack.content.recipe.RecipeDefinition;
import com.operator.mypack.content.recipe.RecipeParser;
import com.operator.mypack.content.sound.SoundEvent;
import com.operator.mypack.content.sound.SoundParser;
import com.operator.mypack.model.anim.Animation;
import com.operator.mypack.model.anim.AnimationParser;
import com.operator.mypack.model.geo.GeometryModel;
import com.operator.mypack.model.geo.GeometryParser;
import com.operator.mypack.utils.JsonUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads every content file of a pack. A broken file is reported to {@link Issues} and skipped: loading never throws
 * because of pack content, so one bad recipe cannot prevent the rest of a pack from installing.
 */
public final class PackLoader {

    /** JSON documents larger than this are rejected (a legitimate definition is a few kilobytes). */
    public static final long MAX_JSON_BYTES = 16L * 1024L * 1024L;

    private PackLoader() {
    }

    public static PackContent load(PackManifest manifest, PackFiles files, Issues issues) {
        String ns = manifest.namespace();
        List<ItemDefinition> items = new ArrayList<>();
        List<RecipeDefinition> recipes = new ArrayList<>();
        List<LootTable> loot = new ArrayList<>();
        List<MobDefinition> mobs = new ArrayList<>();
        List<FurnitureDefinition> furniture = new ArrayList<>();
        List<SoundEvent> sounds = new ArrayList<>();
        List<GeometryModel> geometries = new ArrayList<>();
        List<Animation> animations = new ArrayList<>();

        Set<String> seenItems = new HashSet<>();
        for (String path : files.list("items", ".json")) {
            JsonObject json = read(files, path, issues);
            if (json == null) {
                continue;
            }
            ItemDefinition item = ItemParser.parse(json, new ParseContext(ns, path, issues));
            if (item != null && unique(seenItems, item.id().full(), path, issues)) {
                items.add(item);
                checkIcon(files, ns, path, item.iconTexture(), issues);
                if (item.javaModel() != null && !files.exists(item.javaModel())) {
                    issues.warn(path, "mypack:java_model '" + item.javaModel() + "' does not exist in the pack");
                }
            }
        }

        Set<String> seenRecipes = new HashSet<>();
        for (String path : files.list("recipes", ".json")) {
            JsonObject json = read(files, path, issues);
            if (json == null) {
                continue;
            }
            RecipeDefinition recipe = RecipeParser.parse(json, new ParseContext(ns, path, issues));
            if (recipe != null && unique(seenRecipes, recipe.id().full(), path, issues)) {
                recipes.add(recipe);
            }
        }

        Set<String> seenLoot = new HashSet<>();
        for (String path : files.list("loot_tables", ".json")) {
            JsonObject json = read(files, path, issues);
            if (json == null) {
                continue;
            }
            String relative = path.substring("loot_tables/".length(), path.length() - ".json".length()).toLowerCase(java.util.Locale.ROOT);
            ContentId id;
            try {
                id = new ContentId(ns, relative);
            } catch (IllegalArgumentException e) {
                issues.error(path, "file name is not a valid loot table id: " + e.getMessage());
                continue;
            }
            LootTable table = LootParser.parse(json, id, new ParseContext(ns, path, issues));
            if (table != null && unique(seenLoot, id.full(), path, issues)) {
                loot.add(table);
            }
        }

        Set<String> seenMobs = new HashSet<>();
        for (String path : files.list("entities", ".json")) {
            JsonObject json = read(files, path, issues);
            if (json == null) {
                continue;
            }
            MobDefinition mob = MobParser.parseMob(json, new ParseContext(ns, path, issues));
            if (mob != null && unique(seenMobs, mob.id().full(), path, issues)) {
                mobs.add(mob);
                if (mob.model() != null && !files.exists("textures/entity/" + mob.model().texture() + ".png")) {
                    issues.warn(path, "texture textures/entity/" + mob.model().texture() + ".png is missing from the pack");
                }
            }
        }

        Set<String> seenFurniture = new HashSet<>();
        for (String path : files.list("furniture", ".json")) {
            JsonObject json = read(files, path, issues);
            if (json == null) {
                continue;
            }
            FurnitureDefinition def = MobParser.parseFurniture(json, new ParseContext(ns, path, issues));
            if (def != null && unique(seenFurniture, def.id().full(), path, issues)) {
                furniture.add(def);
                checkIcon(files, ns, path, def.iconTexture(), issues);
                if (def.model() != null && !files.exists("textures/entity/" + def.model().texture() + ".png")) {
                    issues.warn(path, "texture textures/entity/" + def.model().texture() + ".png is missing from the pack");
                }
            }
        }

        if (files.exists("sounds/sound_definitions.json")) {
            JsonObject json = read(files, "sounds/sound_definitions.json", issues);
            if (json != null) {
                Set<String> seenSounds = new HashSet<>();
                for (SoundEvent event : SoundParser.parse(json, new ParseContext(ns, "sounds/sound_definitions.json", issues))) {
                    if (unique(seenSounds, event.id().full(), "sounds/sound_definitions.json", issues)) {
                        sounds.add(event);
                    }
                }
            }
        }

        Set<String> seenGeometry = new HashSet<>();
        for (String path : files.list("models", ".geo.json")) {
            JsonObject json = read(files, path, issues);
            if (json == null) {
                continue;
            }
            for (GeometryModel model : GeometryParser.parse(json, new ParseContext(ns, path, issues))) {
                if (unique(seenGeometry, model.id().full(), path, issues)) {
                    geometries.add(model);
                }
            }
        }

        Set<String> seenAnimations = new HashSet<>();
        for (String path : files.list("animations", ".json")) {
            JsonObject json = read(files, path, issues);
            if (json == null) {
                continue;
            }
            for (Animation animation : AnimationParser.parse(json, new ParseContext(ns, path, issues))) {
                if (unique(seenAnimations, ns + ":" + animation.name(), path, issues)) {
                    animations.add(animation);
                }
            }
        }

        return new PackContent(List.copyOf(items), List.copyOf(recipes), List.copyOf(loot), List.copyOf(mobs),
                List.copyOf(furniture), List.copyOf(sounds), List.copyOf(geometries), List.copyOf(animations));
    }

    private static JsonObject read(PackFiles files, String path, Issues issues) {
        try {
            byte[] bytes = files.read(path);
            if (bytes.length > MAX_JSON_BYTES) {
                issues.error(path, "file is larger than " + (MAX_JSON_BYTES / (1024 * 1024)) + " MB");
                return null;
            }
            return JsonUtils.parseObject(bytes);
        } catch (IOException e) {
            issues.error(path, "cannot be read: " + e.getMessage());
        } catch (RuntimeException e) {
            issues.error(path, "is not valid JSON: " + e.getMessage());
        }
        return null;
    }

    private static boolean unique(Set<String> seen, String id, String path, Issues issues) {
        if (!seen.add(id)) {
            issues.error(path, "duplicate definition of '" + id + "'; the later file was skipped");
            return false;
        }
        return true;
    }

    private static void checkIcon(PackFiles files, String ns, String path, String icon, Issues issues) {
        if (icon == null) {
            return;
        }
        if (!files.exists("textures/items/" + icon + ".png") && !files.exists("textures/item/" + icon + ".png")) {
            issues.warn(path, "icon texture textures/items/" + icon + ".png is missing from the pack (" + ns + ")");
        }
    }
}
