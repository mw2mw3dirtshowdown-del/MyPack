package com.operator.mypack.services;

import com.operator.mypack.config.ConfigManager;
import com.operator.mypack.content.mob.ModelBinding;
import com.operator.mypack.model.anim.Animation;
import com.operator.mypack.model.geo.GeometryModel;
import com.operator.mypack.model.runtime.ModelBlueprint;
import com.operator.mypack.model.runtime.ModelInstance;
import com.operator.mypack.pack.ContentRegistry;
import com.operator.mypack.tasks.ModelTicker;
import com.operator.mypack.tasks.Schedulers;
import com.operator.mypack.utils.NameUtils;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Creates and tracks the 3D model of every mob and piece of furniture. The service is the only place that knows how a
 * binding in a definition becomes a {@link ModelInstance}; mobs and furniture just ask for one for their anchor.
 */
public final class ModelService implements ContentListener {

    private final ContentRegistry registry;
    private final ConfigManager config;
    private final Schedulers schedulers;
    private final Logger log;
    private final ModelTicker ticker;
    private final NamespacedKey partKey;
    private final NamespacedKey hitboxKey;
    private final Map<UUID, ModelInstance> instances = new ConcurrentHashMap<>();
    private final Map<String, ModelBlueprint> blueprints = new ConcurrentHashMap<>();

    public ModelService(Plugin plugin, ContentRegistry registry, ConfigManager config, Schedulers schedulers) {
        this.registry = registry;
        this.config = config;
        this.schedulers = schedulers;
        this.log = plugin.getLogger();
        this.partKey = new NamespacedKey(plugin, "model_part");
        this.hitboxKey = new NamespacedKey(plugin, "model_hitbox");
        this.ticker = new ModelTicker(schedulers, config, log, instance -> detach(instance.anchorId()));
    }

    public void start() {
        ticker.start();
    }

    public NamespacedKey partKey() {
        return partKey;
    }

    public NamespacedKey hitboxKey() {
        return hitboxKey;
    }

    public ModelInstance get(UUID anchorId) {
        return instances.get(anchorId);
    }

    public Collection<ModelInstance> all() {
        return instances.values();
    }

    public int count() {
        return instances.size();
    }

    public long currentTick() {
        return ticker.currentTick();
    }

    /** {@code true} when {@code entity} is a display or hitbox spawned for a model (used for orphan cleanup). */
    public boolean isModelPart(Entity entity) {
        return entity.getPersistentDataContainer().has(partKey, PersistentDataType.STRING)
                || entity.getPersistentDataContainer().has(hitboxKey, PersistentDataType.STRING);
    }

    /** UUID of the anchor a model entity belongs to, or {@code null}. */
    public UUID anchorOf(Entity entity) {
        String raw = entity.getPersistentDataContainer().get(partKey, PersistentDataType.STRING);
        if (raw == null) {
            raw = entity.getPersistentDataContainer().get(hitboxKey, PersistentDataType.STRING);
        }
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Builds the model for {@code anchor}. Must run on the anchor's thread.
     *
     * @param ownerId   id of the mob / furniture definition (decides the generated resource keys)
     * @param ownHitbox spawn a separate Interaction hitbox (mobs); furniture uses its anchor Interaction instead
     * @param hitboxOverride explicit hitbox size from the definition, or {@code null} to derive it from the model
     * @return the instance, or {@code null} when the geometry is unknown or the instance cap is reached
     */
    public ModelInstance attach(Entity anchor, Location origin, String ownerId, ModelBinding binding, boolean ownHitbox,
                                ModelBlueprint.Hitbox hitboxOverride) {
        detach(anchor.getUniqueId());
        if (instances.size() >= config.settings().models().maxInstances()) {
            log.warning("models.max-instances (" + config.settings().models().maxInstances() + ") reached; "
                    + ownerId + " is shown without a model");
            return null;
        }
        ModelBlueprint blueprint = blueprint(ownerId, binding);
        if (blueprint == null) {
            return null;
        }
        Map<String, Animation> animations = new HashMap<>();
        for (Map.Entry<String, String> entry : binding.animations().entrySet()) {
            Animation animation = registry.animation(entry.getValue());
            if (animation != null) {
                animations.put(entry.getKey(), animation);
            }
        }
        ModelInstance instance = new ModelInstance(anchor.getUniqueId(), ownerId, blueprint, binding, animations, ticker.currentTick(),
                hitboxOverride);
        String namespace = ownerId.substring(0, ownerId.indexOf(':'));
        instance.spawn(origin, namespace, partKey, hitboxKey, config.settings().models().viewRange(), ownHitbox);
        instances.put(anchor.getUniqueId(), instance);
        if (instance.isAnimated() || ownHitbox) {
            ticker.register(instance, anchor);
        }
        return instance;
    }

    /**
     * Tears the model down. Every spawned entity is removed through its own scheduler, so this is safe to call from any
     * thread (including the ticker's finish callback).
     */
    public void detach(UUID anchorId) {
        ModelInstance instance = instances.remove(anchorId);
        ticker.unregister(anchorId);
        if (instance == null) {
            return;
        }
        for (Entity entity : instance.entities()) {
            schedulers.entity(entity, entity::remove, null);
        }
    }

    /**
     * Removes every model immediately on the calling thread (plugin shutdown: schedulers are already cancelled).
     * Failures are ignored - the entities are not persistent and vanish with the server anyway.
     */
    public void detachAllNow() {
        ticker.stop();
        for (ModelInstance instance : instances.values()) {
            for (Entity entity : instance.entities()) {
                try {
                    entity.remove();
                } catch (RuntimeException ignored) {
                    // wrong thread on Folia during shutdown or already gone
                }
            }
        }
        instances.clear();
    }

    /** Hitbox size of a model (used to size furniture anchors); {@code null} when the geometry is not loaded. */
    public ModelBlueprint.Hitbox hitboxFor(String ownerId, ModelBinding binding) {
        ModelBlueprint blueprint = blueprint(ownerId, binding);
        return blueprint == null ? null : blueprint.hitbox(binding.scale());
    }

    private ModelBlueprint blueprint(String ownerId, ModelBinding binding) {
        ModelBlueprint cached = blueprints.get(ownerId);
        if (cached != null) {
            return cached;
        }
        GeometryModel geometry = registry.geometry(binding.geometry());
        if (geometry == null) {
            log.warning(ownerId + ": geometry " + binding.geometry() + " is not loaded; no model is shown");
            return null;
        }
        String path = ownerId.substring(ownerId.indexOf(':') + 1);
        ModelBlueprint blueprint = ModelBlueprint.of(geometry, NameUtils.safe(path));
        blueprints.put(ownerId, blueprint);
        return blueprint;
    }

    @Override
    public void onContentReplaced(ContentRegistry.Snapshot snapshot) {
        blueprints.clear();
    }
}
