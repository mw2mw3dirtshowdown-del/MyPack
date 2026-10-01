package com.operator.mypack.services;

import com.operator.mypack.config.ConfigManager;
import com.operator.mypack.content.mob.FurnitureDefinition;
import com.operator.mypack.database.PlacedContentRepository;
import com.operator.mypack.model.runtime.ModelBlueprint;
import com.operator.mypack.pack.ContentRegistry;
import com.operator.mypack.tasks.Schedulers;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Placeable decoration. The persistent anchor is an {@link Interaction} entity that also provides the hitbox; its
 * visuals (a 3D model or a flat icon display) are non-persistent and are re-created after every chunk load or reload.
 * A furniture piece whose definition is missing keeps its anchor, so disabling a pack never destroys what players built;
 * the visuals return as soon as the pack is back.
 */
public final class FurnitureService implements ContentListener {

    private static final float DEFAULT_SIZE = 0.9F;

    /** A rider on one seat. */
    private record Seat(UUID standId, ArmorStand stand, UUID anchorId, int index, UUID playerId) {
    }

    private final ContentRegistry registry;
    private final ModelService models;
    private final ItemService items;
    private final ConfigManager config;
    private final Schedulers schedulers;
    private final PlacedContentRepository ledger;
    private final Logger log;
    private final NamespacedKey furnitureKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey seatKey;
    private final Map<UUID, Interaction> tracked = new ConcurrentHashMap<>();
    private final Map<UUID, ItemDisplay> flatVisuals = new ConcurrentHashMap<>();
    private final Map<UUID, Seat> seats = new ConcurrentHashMap<>();
    private final Map<UUID, Entity> deferred = new ConcurrentHashMap<>();
    private volatile boolean contentReady;

    public FurnitureService(Plugin plugin, ContentRegistry registry, ModelService models, ItemService items,
                            ConfigManager config, Schedulers schedulers, PlacedContentRepository ledger) {
        this.registry = registry;
        this.models = models;
        this.items = items;
        this.config = config;
        this.schedulers = schedulers;
        this.ledger = ledger;
        this.log = plugin.getLogger();
        this.furnitureKey = new NamespacedKey(plugin, "furniture");
        this.ownerKey = new NamespacedKey(plugin, "furniture_owner");
        this.seatKey = new NamespacedKey(plugin, "furniture_seat");
    }

    public NamespacedKey furnitureKey() {
        return furnitureKey;
    }

    public String furnitureIdOf(Entity entity) {
        return entity.getPersistentDataContainer().get(furnitureKey, PersistentDataType.STRING);
    }

    public UUID ownerOf(Entity entity) {
        String raw = entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public int trackedCount() {
        return tracked.size();
    }

    public boolean isSeat(Entity entity) {
        return entity.getPersistentDataContainer().has(seatKey, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------ placing

    /** Rotation snapped to the definition's step; {@code step == 0} keeps the angle. */
    public static float snap(float yaw, int step) {
        if (step <= 0) {
            return yaw;
        }
        float normalized = ((yaw % 360.0F) + 360.0F) % 360.0F;
        return Math.round(normalized / step) * (float) step % 360.0F;
    }

    /** Number of furniture anchors in the chunk of {@code location} (entity thread of that chunk). */
    public int countInChunk(Location location) {
        int count = 0;
        for (Entity entity : location.getChunk().getEntities()) {
            if (entity instanceof Interaction && furnitureIdOf(entity) != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * Places furniture at {@code base} (bottom centre of the piece). Entity thread of {@code base}'s region.
     *
     * @return the anchor, or {@code null} when it could not be created
     */
    public Interaction place(FurnitureDefinition def, Location base, float yaw, UUID owner) {
        World world = base.getWorld();
        Location location = base.clone();
        location.setYaw(snap(yaw, def.rotationStep()));
        location.setPitch(0.0F);
        float[] size = sizeOf(def);
        Interaction anchor = world.spawn(location, Interaction.class, i -> {
            i.setInteractionWidth(size[0]);
            i.setInteractionHeight(size[1]);
            i.setResponsive(true);
            i.setPersistent(true);
            i.getPersistentDataContainer().set(furnitureKey, PersistentDataType.STRING, def.id().full());
            if (owner != null) {
                i.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
            }
        });
        tracked.put(anchor.getUniqueId(), anchor);
        attachVisual(def, anchor);
        PlacedContentRepository.PlacedContent row = new PlacedContentRepository.PlacedContent(anchor.getUniqueId(),
                PlacedContentRepository.KIND_FURNITURE, def.id().full(), world.getName(), location.getX(), location.getY(),
                location.getZ(), location.getYaw(), System.currentTimeMillis());
        schedulers.async(() -> {
            try {
                ledger.upsert(row);
            } catch (SQLException | RuntimeException e) {
                log.log(Level.FINE, "Could not write the placement ledger", e);
            }
        });
        return anchor;
    }

    /** Width and height of the anchor: explicit from the definition, else from the model, else a small default. */
    private float[] sizeOf(FurnitureDefinition def) {
        if (def.width() > 0.0D && def.height() > 0.0D) {
            return new float[]{(float) def.width(), (float) def.height()};
        }
        if (def.model() != null) {
            ModelBlueprint.Hitbox hitbox = models.hitboxFor(def.id().full(), def.model());
            if (hitbox != null) {
                return new float[]{hitbox.width(), hitbox.height()};
            }
        }
        return new float[]{DEFAULT_SIZE, DEFAULT_SIZE};
    }

    private void attachVisual(FurnitureDefinition def, Interaction anchor) {
        releaseVisual(anchor.getUniqueId());
        if (def.model() != null) {
            models.attach(anchor, anchor.getLocation(), def.id().full(), def.model(), false, null);
            return;
        }
        if (def.iconTexture() == null) {
            return;
        }
        float height = anchor.getInteractionHeight();
        Location center = anchor.getLocation().add(0.0D, height / 2.0D, 0.0D);
        ItemStack item = new ItemStack(Material.STICK);
        item.editMeta(meta -> meta.setItemModel(def.id().toKey()));
        ItemDisplay display = anchor.getWorld().spawn(center, ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setViewRange(config.settings().models().viewRange());
            d.setShadowRadius(0.0F);
            d.setPersistent(false);
            d.setInvulnerable(true);
            d.getPersistentDataContainer().set(models.partKey(), PersistentDataType.STRING, anchor.getUniqueId().toString());
        });
        flatVisuals.put(anchor.getUniqueId(), display);
    }

    private void releaseVisual(UUID anchorId) {
        models.detach(anchorId);
        ItemDisplay flat = flatVisuals.remove(anchorId);
        if (flat != null) {
            schedulers.entity(flat, flat::remove, null);
        }
    }

    // ------------------------------------------------------------------ restoring / lifecycle

    /** Re-creates the visuals of a furniture anchor that was loaded with its chunk. Entity thread. */
    public void restore(Entity entity) {
        String id = furnitureIdOf(entity);
        if (id == null) {
            return;
        }
        if (!(entity instanceof Interaction anchor)) {
            entity.remove();
            return;
        }
        tracked.put(anchor.getUniqueId(), anchor);
        FurnitureDefinition def = registry.furniture(id);
        if (def == null) {
            if (!contentReady) {
                deferred.put(anchor.getUniqueId(), anchor);
            }
            return; // keep the anchor: the definition may be back after the pack is re-enabled
        }
        if (models.get(anchor.getUniqueId()) == null && !flatVisuals.containsKey(anchor.getUniqueId())) {
            attachVisual(def, anchor);
        }
    }

    /** The anchor's chunk is unloading: forget the visuals (they are not persistent and unload with the chunk). */
    public void unloaded(UUID anchorId) {
        tracked.remove(anchorId);
        deferred.remove(anchorId);
        releaseVisual(anchorId);
        removeSeatsOf(anchorId);
    }

    @Override
    public void onContentReplaced(ContentRegistry.Snapshot snapshot) {
        contentReady = true;
        for (Interaction anchor : tracked.values()) {
            schedulers.entity(anchor, () -> refresh(anchor), () -> unloaded(anchor.getUniqueId()));
        }
        deferred.clear();
    }

    private void refresh(Interaction anchor) {
        String id = furnitureIdOf(anchor);
        FurnitureDefinition def = id == null ? null : registry.furniture(id);
        releaseVisual(anchor.getUniqueId());
        if (def != null && anchor.isValid()) {
            attachVisual(def, anchor);
        }
    }

    // ------------------------------------------------------------------ actions

    /** Picks the furniture up: removes it and gives the item back (or drops it when the inventory is full). */
    public void pickUp(Interaction anchor, Player player) {
        String id = furnitureIdOf(anchor);
        FurnitureDefinition def = id == null ? null : registry.furniture(id);
        Location location = anchor.getLocation();
        remove(anchor);
        if (def != null) {
            ItemStack item = items.createFurnitureItem(def, 1);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            for (ItemStack rest : leftover.values()) {
                location.getWorld().dropItemNaturally(location, rest);
            }
        }
    }

    /** Removes the anchor, its visuals, its riders and its ledger entry. */
    public void remove(Interaction anchor) {
        UUID id = anchor.getUniqueId();
        tracked.remove(id);
        deferred.remove(id);
        releaseVisual(id);
        removeSeatsOf(id);
        anchor.remove();
        schedulers.async(() -> {
            try {
                ledger.delete(id);
            } catch (SQLException | RuntimeException e) {
                log.log(Level.FINE, "Could not update the placement ledger", e);
            }
        });
    }

    /** Turns the furniture by {@code degrees} (snapped to the definition's step) and rebuilds its visuals. */
    public void rotate(Interaction anchor, float degrees) {
        String id = furnitureIdOf(anchor);
        FurnitureDefinition def = id == null ? null : registry.furniture(id);
        if (def == null) {
            return;
        }
        int step = def.rotationStep() > 0 ? def.rotationStep() : 15;
        float yaw = snap(anchor.getLocation().getYaw() + (degrees < 0 ? -step : step), step);
        anchor.setRotation(yaw, 0.0F);
        attachVisual(def, anchor);
    }

    /** Seats {@code player}; returns {@code false} when the piece has no free seat or the player already rides. */
    public boolean sit(Player player, Interaction anchor) {
        String id = furnitureIdOf(anchor);
        FurnitureDefinition def = id == null ? null : registry.furniture(id);
        if (def == null || def.seats().isEmpty() || player.isInsideVehicle()) {
            return false;
        }
        Set<Integer> taken = new HashSet<>();
        for (Seat seat : seats.values()) {
            if (seat.anchorId().equals(anchor.getUniqueId())) {
                taken.add(seat.index());
            }
        }
        int index = -1;
        for (int i = 0; i < def.seats().size(); i++) {
            if (!taken.contains(i)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return false;
        }
        FurnitureDefinition.Seat offset = def.seats().get(index);
        double radians = Math.toRadians(-anchor.getLocation().getYaw());
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        Location seatLocation = anchor.getLocation().add(offset.x() * cos + offset.z() * sin, offset.y(),
                -offset.x() * sin + offset.z() * cos);
        final int seatIndex = index;
        ArmorStand stand = anchor.getWorld().spawn(seatLocation, ArmorStand.class, a -> {
            a.setInvisible(true);
            a.setMarker(true);
            a.setSmall(true);
            a.setGravity(false);
            a.setSilent(true);
            a.setPersistent(false);
            a.setInvulnerable(true);
            a.getPersistentDataContainer().set(seatKey, PersistentDataType.STRING, anchor.getUniqueId() + ":" + seatIndex);
        });
        seats.put(stand.getUniqueId(), new Seat(stand.getUniqueId(), stand, anchor.getUniqueId(), index, player.getUniqueId()));
        stand.addPassenger(player);
        return true;
    }

    /** The rider left the seat stand: remove it. Called with the stand entity. */
    public void dismounted(Entity stand) {
        Seat seat = seats.remove(stand.getUniqueId());
        if (seat != null || isSeat(stand)) {
            schedulers.entity(stand, stand::remove, null);
        }
    }

    /** Removes the seat of a player that is leaving the server. */
    public void releasePlayer(UUID playerId) {
        for (Seat seat : Set.copyOf(seats.values())) {
            if (seat.playerId().equals(playerId)) {
                seats.remove(seat.standId());
            }
        }
    }

    private void removeSeatsOf(UUID anchorId) {
        for (Seat seat : Set.copyOf(seats.values())) {
            if (seat.anchorId().equals(anchorId)) {
                seats.remove(seat.standId());
            }
        }
    }

    /** Removes every loaded furniture piece of a namespace. */
    public int purgeNamespace(String namespace) {
        int count = 0;
        for (Interaction anchor : tracked.values()) {
            String id = furnitureIdOf(anchor);
            if (id != null && id.startsWith(namespace + ":")) {
                schedulers.entity(anchor, () -> remove(anchor), () -> unloaded(anchor.getUniqueId()));
                count++;
            }
        }
        return count;
    }

    /** Removes seat stands and flat visuals on shutdown (anchors stay: they are persistent world data). */
    public void shutdown() {
        for (ItemDisplay display : flatVisuals.values()) {
            try {
                display.remove();
            } catch (RuntimeException ignored) {
                // wrong thread during Folia shutdown; the entity is not persistent anyway
            }
        }
        flatVisuals.clear();
        for (Seat seat : seats.values()) {
            try {
                seat.stand().remove();
            } catch (RuntimeException ignored) {
                // wrong thread during Folia shutdown; seat stands are not persistent
            }
        }
        seats.clear();
        tracked.clear();
        deferred.clear();
    }
}
