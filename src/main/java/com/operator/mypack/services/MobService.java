package com.operator.mypack.services;

import com.operator.mypack.config.ConfigManager;
import com.operator.mypack.content.mob.MobDefinition;
import com.operator.mypack.database.PlacedContentRepository;
import com.operator.mypack.model.runtime.ModelBlueprint;
import com.operator.mypack.pack.ContentRegistry;
import com.operator.mypack.tasks.Schedulers;
import com.operator.mypack.utils.TextUtils;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Spawns, restores and cleans up custom mobs.
 *
 * <p>A mob is a vanilla living entity (the <em>carrier</em>, chosen by {@code mypack:base_entity}) that provides AI,
 * health and collision. The definition is applied to it for real - max health plus current health, movement speed,
 * attack damage, follow range, knockback resistance, scale, silence and persistence - it is made invisible and its
 * identity is stored in the entity's persistent data ({@code mypack:mob}). The 3D model is shown by display entities
 * that follow the carrier ({@link ModelService}).</p>
 */
public final class MobService implements ContentListener {

    private final ContentRegistry registry;
    private final ModelService models;
    private final ConfigManager config;
    private final Schedulers schedulers;
    private final PlacedContentRepository ledger;
    private final Logger log;
    private final NamespacedKey mobKey;
    private final Map<UUID, LivingEntity> tracked = new ConcurrentHashMap<>();
    private final Map<UUID, Entity> deferred = new ConcurrentHashMap<>();
    private volatile boolean contentReady;

    public MobService(Plugin plugin, ContentRegistry registry, ModelService models, ConfigManager config,
                      Schedulers schedulers, PlacedContentRepository ledger) {
        this.registry = registry;
        this.models = models;
        this.config = config;
        this.schedulers = schedulers;
        this.ledger = ledger;
        this.log = plugin.getLogger();
        this.mobKey = new NamespacedKey(plugin, "mob");
    }

    public NamespacedKey mobKey() {
        return mobKey;
    }

    /** The definition id stored on an entity, or {@code null} when it is not a MyPack mob. */
    public String mobIdOf(Entity entity) {
        return entity.getPersistentDataContainer().get(mobKey, PersistentDataType.STRING);
    }

    /** The tracked carrier entity with this id, or {@code null}. */
    public LivingEntity carrierOf(UUID entityId) {
        return tracked.get(entityId);
    }

    public boolean isTracked(UUID entityId) {
        return tracked.containsKey(entityId);
    }

    public int trackedCount() {
        return tracked.size();
    }

    // ------------------------------------------------------------------ spawning

    /**
     * Spawns {@code definition} at {@code location}. Must be called on the thread that owns the location's region.
     *
     * @return the spawned carrier entity, or {@code null} when the base entity type is invalid
     */
    public LivingEntity spawn(MobDefinition definition, Location location) {
        EntityType type = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(definition.baseEntity().toLowerCase(Locale.ROOT)));
        Class<? extends Entity> entityClass = type == null ? null : type.getEntityClass();
        if (entityClass == null || !LivingEntity.class.isAssignableFrom(entityClass)) {
            log.warning(definition.id() + ": mypack:base_entity '" + definition.baseEntity() + "' is not a living entity type");
            return null;
        }
        World world = location.getWorld();
        Entity spawned = spawnTyped(world, location, entityClass, entity -> prepare(definition, entity));
        if (!(spawned instanceof LivingEntity living)) {
            spawned.remove();
            return null;
        }
        track(definition, living);
        recordPlacement(living, definition);
        return living;
    }

    private static <T extends Entity> T spawnTyped(World world, Location location, Class<T> type, Consumer<Entity> prepare) {
        return world.spawn(location, type, prepare::accept);
    }

    /** Applies the definition to a carrier before it enters the world. */
    private void prepare(MobDefinition def, Entity entity) {
        entity.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, def.id().full());
        entity.setPersistent(def.persistent());
        entity.setSilent(def.silent());
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        living.setRemoveWhenFarAway(!def.persistent());
        living.setCanPickupItems(false);
        if (def.displayName() != null && !def.displayName().isBlank()) {
            living.customName(TextUtils.mm(def.displayName()));
            living.setCustomNameVisible(def.nameVisible());
        }
        if (living instanceof Ageable ageable) {
            ageable.setAdult();
        }
        if (living instanceof Zombie zombie) {
            zombie.setShouldBurnInDay(def.burnsInDaylight());
        }
        if (living instanceof AbstractSkeleton skeleton) {
            skeleton.setShouldBurnInDay(def.burnsInDaylight());
        }
        clearEquipment(living);

        set(living, Attribute.MAX_HEALTH, def.maxHealth());
        AttributeInstance health = living.getAttribute(Attribute.MAX_HEALTH);
        living.setHealth(health == null ? Math.min(def.maxHealth(), 20.0D) : Math.min(def.maxHealth(), health.getValue()));
        if (def.movementSpeed() > 0.0D) {
            set(living, Attribute.MOVEMENT_SPEED, def.movementSpeed());
        }
        if (def.attackDamage() >= 0.0D) {
            set(living, Attribute.ATTACK_DAMAGE, def.attackDamage());
        }
        if (def.followRange() > 0.0D) {
            set(living, Attribute.FOLLOW_RANGE, def.followRange());
        }
        if (def.knockbackResistance() >= 0.0D) {
            set(living, Attribute.KNOCKBACK_RESISTANCE, Math.min(1.0D, def.knockbackResistance()));
        }
        if (def.scale() != 1.0D) {
            set(living, Attribute.SCALE, def.scale());
        }
        if (def.model() != null && config.settings().models().hideBaseEntity()) {
            living.setInvisible(true);
        }
        if (living instanceof Mob mob) {
            mob.setAware(true);
        }
    }

    private void set(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            log.fine(entity.getType() + " has no attribute " + attribute.getKey() + "; value ignored");
            return;
        }
        instance.setBaseValue(value);
    }

    /** Spawned mobs may roll random armour; an invisible mob would still show it and drop it. */
    private static void clearEquipment(LivingEntity entity) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) {
            return;
        }
        equipment.clear();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            try {
                equipment.setDropChance(slot, 0.0F);
            } catch (IllegalArgumentException ignored) {
                // slot not supported by this entity type
            }
        }
    }

    // ------------------------------------------------------------------ model + tracking

    private void track(MobDefinition def, LivingEntity living) {
        tracked.put(living.getUniqueId(), living);
        if (def.model() != null) {
            ModelBlueprint.Hitbox override = def.hitbox() == null ? null
                    : new ModelBlueprint.Hitbox((float) def.hitbox().width(), (float) def.hitbox().height(), 0.0F);
            models.attach(living, living.getLocation(), def.id().full(), def.model(), true, override);
        }
    }

    /**
     * Called for entities that appear through chunk loading. Restores the model of a known mob. A carrier whose
     * definition does not exist is orphaned content (its pack was disabled or uninstalled) and is removed - but only once
     * the first pack load has finished, because chunks around spawn load before the packs do. Runs on the entity's thread.
     */
    public void restore(Entity entity) {
        String id = mobIdOf(entity);
        if (id == null) {
            return;
        }
        if (!(entity instanceof LivingEntity living)) {
            forget(entity);
            entity.remove();
            return;
        }
        MobDefinition def = registry.mob(id);
        if (def == null) {
            if (contentReady) {
                forget(entity);
                entity.remove();
            } else {
                deferred.put(entity.getUniqueId(), entity);
            }
            return;
        }
        if (!tracked.containsKey(entity.getUniqueId()) || models.get(entity.getUniqueId()) == null) {
            track(def, living);
        }
    }

    /**
     * The carrier died: its placement entry is deleted, but the model stays until the death animation finished (the
     * ticker tears it down then).
     */
    public void died(Entity entity) {
        tracked.remove(entity.getUniqueId());
        schedulers.async(() -> {
            try {
                ledger.delete(entity.getUniqueId());
            } catch (SQLException | RuntimeException e) {
                log.log(Level.FINE, "Could not update the placement ledger", e);
            }
        });
    }

    /** Stops tracking and removes the model; the carrier itself is left alone. */
    public void release(UUID entityId) {
        tracked.remove(entityId);
        models.detach(entityId);
    }

    /** Removes bookkeeping for an entity that is going away (death, unload, cleanup). */
    public void forget(Entity entity) {
        release(entity.getUniqueId());
        schedulers.async(() -> {
            try {
                ledger.delete(entity.getUniqueId());
            } catch (SQLException | RuntimeException e) {
                log.log(Level.FINE, "Could not update the placement ledger", e);
            }
        });
    }

    private void recordPlacement(LivingEntity living, MobDefinition def) {
        Location loc = living.getLocation();
        PlacedContentRepository.PlacedContent row = new PlacedContentRepository.PlacedContent(living.getUniqueId(),
                PlacedContentRepository.KIND_MOB, def.id().full(), loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), System.currentTimeMillis());
        schedulers.async(() -> {
            try {
                ledger.upsert(row);
            } catch (SQLException | RuntimeException e) {
                log.log(Level.FINE, "Could not write the placement ledger", e);
            }
        });
    }

    /** Re-creates the models of all tracked mobs after a reload; removes mobs whose definition disappeared. */
    @Override
    public void onContentReplaced(ContentRegistry.Snapshot snapshot) {
        contentReady = true;
        for (LivingEntity entity : tracked.values()) {
            schedulers.entity(entity, () -> refresh(entity), () -> release(entity.getUniqueId()));
        }
        for (Entity entity : deferred.values()) {
            schedulers.entity(entity, () -> restore(entity), null);
        }
        deferred.clear();
    }

    private void refresh(LivingEntity entity) {
        String id = mobIdOf(entity);
        MobDefinition def = id == null ? null : registry.mob(id);
        if (def == null) {
            forget(entity);
            entity.remove();
            return;
        }
        models.detach(entity.getUniqueId());
        if (def.model() != null && entity.isValid()) {
            ModelBlueprint.Hitbox override = def.hitbox() == null ? null
                    : new ModelBlueprint.Hitbox((float) def.hitbox().width(), (float) def.hitbox().height(), 0.0F);
            models.attach(entity, entity.getLocation(), def.id().full(), def.model(), true, override);
        }
    }

    // ------------------------------------------------------------------ shutdown / commands

    /** Removes all carriers that are currently tracked (used by {@code /mypack mobs clear}). */
    public int removeAll() {
        int count = 0;
        for (LivingEntity entity : tracked.values()) {
            schedulers.entity(entity, () -> {
                forget(entity);
                entity.remove();
            }, () -> release(entity.getUniqueId()));
            count++;
        }
        return count;
    }

    /** Drops all tracking state without touching entities (plugin shutdown). */
    public void clearTracking() {
        tracked.clear();
        deferred.clear();
    }

    /** Removes the loaded mobs of one namespace; returns how many were scheduled for removal. */
    public int purgeNamespace(String namespace) {
        int count = 0;
        for (LivingEntity entity : tracked.values()) {
            String id = mobIdOf(entity);
            if (id != null && id.startsWith(namespace + ":")) {
                schedulers.entity(entity, () -> {
                    forget(entity);
                    entity.remove();
                }, () -> release(entity.getUniqueId()));
                count++;
            }
        }
        return count;
    }
}
