package com.operator.mypack.api;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Public API of MyPack. Obtain it through Bukkit's service manager:
 *
 * <pre>{@code
 * RegisteredServiceProvider<MyPackApi> rsp = Bukkit.getServicesManager().getRegistration(MyPackApi.class);
 * MyPackApi api = rsp == null ? null : rsp.getProvider();
 * }</pre>
 *
 * <p>Methods that create or spawn things must be called on the thread that owns the target (the main thread on Paper;
 * the owning region or entity scheduler on Folia). Query methods are safe from any thread.</p>
 */
public interface MyPackApi {

    /** Creates a custom (or {@code minecraft:*}, or furniture) item by identifier; empty for unknown ids. */
    Optional<ItemStack> createItem(String id, int amount);

    /** The MyPack identifier stored on {@code stack}, or empty when it is not a MyPack item. */
    Optional<String> itemId(ItemStack stack);

    default boolean isCustomItem(ItemStack stack) {
        return itemId(stack).isPresent();
    }

    /** Spawns a custom mob; empty when the id is unknown or the base entity cannot be spawned. */
    Optional<LivingEntity> spawnMob(String id, Location location);

    /** Rolls a loot table with the given circumstances; unknown tables yield an empty list. */
    List<ItemStack> rollLoot(String tableId, boolean killedByPlayer, int lootingLevel);

    Collection<String> itemIds();

    Collection<String> mobIds();

    Collection<String> furnitureIds();

    Collection<String> lootTableIds();

    /** Namespaces of all loaded packs. */
    Collection<String> loadedNamespaces();

    /** URL the resource pack is downloaded from, or empty when none is built or hosted. */
    Optional<String> resourcePackUrl();

    /** Rescans the packs and rebuilds the resource pack; completes when everything is published. */
    CompletableFuture<Void> reload();
}
