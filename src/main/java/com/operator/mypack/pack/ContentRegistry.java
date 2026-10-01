package com.operator.mypack.pack;

import com.operator.mypack.content.item.ItemDefinition;
import com.operator.mypack.content.loot.LootTable;
import com.operator.mypack.content.mob.FurnitureDefinition;
import com.operator.mypack.content.mob.MobDefinition;
import com.operator.mypack.content.recipe.RecipeDefinition;
import com.operator.mypack.content.sound.SoundEvent;
import com.operator.mypack.model.anim.Animation;
import com.operator.mypack.model.geo.GeometryModel;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lookup tables for all loaded content, keyed by full identifier ({@code namespace:path}). The registry is a
 * copy-on-write snapshot: writers build a new immutable {@link Snapshot} and publish it atomically, so readers on any
 * thread (listeners, tick tasks, the HTTP thread) never see a half-updated registry and never need locks.
 */
public final class ContentRegistry {

    /** An immutable view of everything that is registered. */
    public record Snapshot(
            Map<String, ItemDefinition> items,
            Map<String, RecipeDefinition> recipes,
            Map<String, LootTable> lootTables,
            Map<String, MobDefinition> mobs,
            Map<String, FurnitureDefinition> furniture,
            Map<String, SoundEvent> sounds,
            Map<String, GeometryModel> geometries,
            Map<String, Animation> animations) {

        public static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of());

        public int totalCount() {
            return items.size() + recipes.size() + lootTables.size() + mobs.size() + furniture.size() + sounds.size()
                    + geometries.size() + animations.size();
        }
    }

    private volatile Snapshot current = Snapshot.EMPTY;

    public Snapshot snapshot() {
        return current;
    }

    /** Replaces the whole registry with the content of {@code packs} (in the given order). */
    public synchronized void replaceAll(Iterable<InstalledPack> packs) {
        Maps maps = new Maps();
        for (InstalledPack pack : packs) {
            maps.add(pack.namespace(), pack.content());
        }
        current = maps.freeze();
    }

    /** Removes every entry whose identifier starts with {@code namespace + ":"}. */
    public synchronized void removeByNamespace(String namespace) {
        String prefix = namespace + ":";
        Snapshot s = current;
        current = new Snapshot(
                without(s.items(), prefix), without(s.recipes(), prefix), without(s.lootTables(), prefix),
                without(s.mobs(), prefix), without(s.furniture(), prefix), without(s.sounds(), prefix),
                without(s.geometries(), prefix), without(s.animations(), prefix));
    }

    /** Adds the content of one pack. Existing entries with the same identifier are replaced. */
    public synchronized void register(String namespace, PackContent content) {
        Snapshot s = current;
        Maps maps = new Maps();
        maps.items.putAll(s.items());
        maps.recipes.putAll(s.recipes());
        maps.loot.putAll(s.lootTables());
        maps.mobs.putAll(s.mobs());
        maps.furniture.putAll(s.furniture());
        maps.sounds.putAll(s.sounds());
        maps.geometries.putAll(s.geometries());
        maps.animations.putAll(s.animations());
        maps.add(namespace, content);
        current = maps.freeze();
    }

    // ------------------------------------------------------------------ convenience lookups

    public ItemDefinition item(String id) {
        return current.items().get(id);
    }

    public FurnitureDefinition furniture(String id) {
        return current.furniture().get(id);
    }

    public MobDefinition mob(String id) {
        return current.mobs().get(id);
    }

    public LootTable lootTable(String id) {
        return current.lootTables().get(id);
    }

    public GeometryModel geometry(String id) {
        return current.geometries().get(id);
    }

    public Animation animation(String id) {
        return current.animations().get(id);
    }

    // ------------------------------------------------------------------ internals

    private static <V> Map<String, V> without(Map<String, V> source, String prefix) {
        Map<String, V> out = new LinkedHashMap<>();
        for (Map.Entry<String, V> e : source.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static final class Maps {
        final Map<String, ItemDefinition> items = new LinkedHashMap<>();
        final Map<String, RecipeDefinition> recipes = new LinkedHashMap<>();
        final Map<String, LootTable> loot = new LinkedHashMap<>();
        final Map<String, MobDefinition> mobs = new LinkedHashMap<>();
        final Map<String, FurnitureDefinition> furniture = new LinkedHashMap<>();
        final Map<String, SoundEvent> sounds = new LinkedHashMap<>();
        final Map<String, GeometryModel> geometries = new LinkedHashMap<>();
        final Map<String, Animation> animations = new HashMap<>();

        void add(String namespace, PackContent c) {
            c.items().forEach(i -> items.put(i.id().full(), i));
            c.recipes().forEach(r -> recipes.put(r.id().full(), r));
            c.lootTables().forEach(l -> loot.put(l.id().full(), l));
            c.mobs().forEach(m -> mobs.put(m.id().full(), m));
            c.furniture().forEach(f -> furniture.put(f.id().full(), f));
            c.sounds().forEach(s -> sounds.put(s.id().full(), s));
            c.geometries().forEach(g -> geometries.put(g.id().full(), g));
            c.animations().forEach(a -> animations.put(namespace + ":" + a.name(), a));
        }

        Snapshot freeze() {
            return new Snapshot(Collections.unmodifiableMap(items), Collections.unmodifiableMap(recipes),
                    Collections.unmodifiableMap(loot), Collections.unmodifiableMap(mobs),
                    Collections.unmodifiableMap(furniture), Collections.unmodifiableMap(sounds),
                    Collections.unmodifiableMap(geometries), Collections.unmodifiableMap(animations));
        }
    }
}
