package com.operator.mypack.api;

import com.operator.mypack.content.loot.LootEvaluator;
import com.operator.mypack.pack.ContentRegistry;
import com.operator.mypack.pack.InstalledPack;
import com.operator.mypack.services.ItemService;
import com.operator.mypack.services.LootService;
import com.operator.mypack.services.MobService;
import com.operator.mypack.services.PackService;
import com.operator.mypack.services.ResourcePackService;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** The {@link MyPackApi} implementation registered with Bukkit's service manager. */
public final class DefaultMyPackApi implements MyPackApi {

    private final ContentRegistry registry;
    private final ItemService items;
    private final LootService loot;
    private final MobService mobs;
    private final PackService packs;
    private final ResourcePackService resourcePack;

    public DefaultMyPackApi(ContentRegistry registry, ItemService items, LootService loot, MobService mobs, PackService packs,
                            ResourcePackService resourcePack) {
        this.registry = registry;
        this.items = items;
        this.loot = loot;
        this.mobs = mobs;
        this.packs = packs;
        this.resourcePack = resourcePack;
    }

    @Override
    public Optional<ItemStack> createItem(String id, int amount) {
        return Optional.ofNullable(items.createFromId(id, Math.max(1, amount)));
    }

    @Override
    public Optional<String> itemId(ItemStack stack) {
        return Optional.ofNullable(items.idOf(stack));
    }

    @Override
    public Optional<LivingEntity> spawnMob(String id, Location location) {
        var definition = registry.mob(id);
        return definition == null ? Optional.empty() : Optional.ofNullable(mobs.spawn(definition, location));
    }

    @Override
    public List<ItemStack> rollLoot(String tableId, boolean killedByPlayer, int lootingLevel) {
        return loot.roll(tableId, new LootEvaluator.Context(killedByPlayer, Math.max(0, lootingLevel)));
    }

    @Override
    public Collection<String> itemIds() {
        return List.copyOf(registry.snapshot().items().keySet());
    }

    @Override
    public Collection<String> mobIds() {
        return List.copyOf(registry.snapshot().mobs().keySet());
    }

    @Override
    public Collection<String> furnitureIds() {
        return List.copyOf(registry.snapshot().furniture().keySet());
    }

    @Override
    public Collection<String> lootTableIds() {
        return List.copyOf(registry.snapshot().lootTables().keySet());
    }

    @Override
    public Collection<String> loadedNamespaces() {
        List<String> out = new ArrayList<>();
        for (InstalledPack pack : packs.loadedPacks()) {
            out.add(pack.namespace());
        }
        return out;
    }

    @Override
    public Optional<String> resourcePackUrl() {
        ResourcePackService.Published published = resourcePack.published();
        return published == null ? Optional.empty() : Optional.ofNullable(published.url());
    }

    @Override
    public CompletableFuture<Void> reload() {
        return packs.reload("api").thenApply(report -> null);
    }
}
