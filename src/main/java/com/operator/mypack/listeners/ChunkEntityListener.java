package com.operator.mypack.listeners;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import com.operator.mypack.services.FurnitureService;
import com.operator.mypack.services.MobService;
import com.operator.mypack.services.ModelService;
import com.operator.mypack.tasks.Schedulers;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;

import java.util.UUID;

/**
 * Display entities are deliberately not persistent, so after a restart (or when a chunk loads) the visuals of every
 * mob and furniture piece are re-created from the persistent carrier / anchor entity. This listener finds those
 * entities as their chunks load and releases the visuals when the chunk unloads.
 */
public final class ChunkEntityListener implements Listener {

    private final MobService mobs;
    private final FurnitureService furniture;
    private final ModelService models;
    private final Schedulers schedulers;

    public ChunkEntityListener(MobService mobs, FurnitureService furniture, ModelService models, Schedulers schedulers) {
        this.mobs = mobs;
        this.furniture = furniture;
        this.models = models;
        this.schedulers = schedulers;
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            handleLoaded(entity);
        }
    }

    @EventHandler
    public void onUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            UUID id = entity.getUniqueId();
            if (mobs.isTracked(id)) {
                mobs.release(id);
            }
            if (furniture.furnitureIdOf(entity) != null) {
                furniture.unloaded(id);
            }
            if (furniture.isSeat(entity)) {
                furniture.dismounted(entity);
            }
        }
    }

    /** A carrier that despawns without dying (distance, commands) must not leave its displays behind. */
    @EventHandler
    public void onRemove(EntityRemoveFromWorldEvent event) {
        Entity entity = event.getEntity();
        if (mobs.isTracked(entity.getUniqueId()) && !entity.isDead()) {
            mobs.release(entity.getUniqueId());
        }
    }

    /** Processes every already loaded chunk; used once at start-up (chunk-load events were missed). */
    public void scanLoadedChunks() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                Location where = new Location(world, chunk.getX() * 16 + 8, 64, chunk.getZ() * 16 + 8);
                schedulers.region(where, () -> {
                    for (Entity entity : chunk.getEntities()) {
                        handleLoaded(entity);
                    }
                });
            }
        }
    }

    private void handleLoaded(Entity entity) {
        if (mobs.mobIdOf(entity) != null) {
            schedulers.entity(entity, () -> mobs.restore(entity), null);
        } else if (furniture.furnitureIdOf(entity) != null) {
            schedulers.entity(entity, () -> furniture.restore(entity), null);
        } else if (models.isModelPart(entity)) {
            UUID anchor = models.anchorOf(entity);
            if (anchor == null || models.get(anchor) == null) {
                schedulers.entity(entity, entity::remove, null); // orphaned display from before a reload
            }
        }
    }
}
