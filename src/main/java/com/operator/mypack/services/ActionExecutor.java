package com.operator.mypack.services;

import com.operator.mypack.config.ConfigManager;
import com.operator.mypack.content.item.ItemAction;
import com.operator.mypack.tasks.Schedulers;
import com.operator.mypack.utils.TextUtils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Runs the {@link ItemAction} scripts of custom items. Everything here executes on the thread that owns the player.
 * The only heavy math - the points of a damage ray's particle trail - is computed on an async thread and spawned back
 * on the region that owns the location.
 */
public final class ActionExecutor {

    private static final Pattern SAFE_PLACEHOLDER = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");

    private final ConfigManager config;
    private final Schedulers schedulers;
    private final MobService mobs;
    private final ModelService models;
    private final Logger log;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public ActionExecutor(ConfigManager config, Schedulers schedulers, MobService mobs, ModelService models, Logger log) {
        this.config = config;
        this.schedulers = schedulers;
        this.mobs = mobs;
        this.models = models;
        this.log = log;
    }

    /**
     * @param hand the hand that holds the used item (consume / durability act on it)
     */
    public void run(Player player, EquipmentSlot hand, List<ItemAction> script) {
        for (ItemAction action : script) {
            try {
                execute(player, hand, action);
            } catch (RuntimeException e) {
                warnOnce("action " + action, "An item action failed: " + e);
            }
        }
    }

    private void execute(Player player, EquipmentSlot hand, ItemAction action) {
        switch (action) {
            case ItemAction.Sound sound -> player.getWorld().playSound(player.getLocation(), sound.sound(),
                    SoundCategory.PLAYERS, sound.volume(), sound.pitch());
            case ItemAction.Particle particle -> particle(player, particle);
            case ItemAction.Message message -> player.sendMessage(TextUtils.mm(message.text(),
                    Placeholder.unparsed("player", player.getName())));
            case ItemAction.Command command -> command(player, command);
            case ItemAction.PotionEffect potion -> potion(player, potion);
            case ItemAction.DamageRay ray -> damageRay(player, ray);
            case ItemAction.Consume consume -> {
                ItemStack stack = player.getInventory().getItem(hand);
                if (stack != null && !stack.getType().isAir()) {
                    stack.subtract(consume.amount());
                    player.getInventory().setItem(hand, stack.getAmount() <= 0 ? null : stack);
                }
            }
            case ItemAction.Durability durability -> {
                ItemStack stack = player.getInventory().getItem(hand);
                if (stack != null && !stack.getType().isAir()) {
                    player.getInventory().setItem(hand, stack.damage(durability.amount(), player));
                }
            }
        }
    }

    // ------------------------------------------------------------------ individual actions

    private void particle(Player player, ItemAction.Particle action) {
        Particle particle = particleOf(action.particle());
        if (particle == null) {
            warnOnce("particle " + action.particle(), "Unknown or unsupported particle '" + action.particle() + "' in an item action");
            return;
        }
        Location eye = player.getEyeLocation();
        Location at = eye.add(eye.getDirection().multiply(1.0D));
        player.getWorld().spawnParticle(particle, at, action.count(), action.offsetX(), action.offsetY(), action.offsetZ(), action.speed());
    }

    /** Resolves a particle by registry key; particles that need extra data (dust colour, block data) are not supported. */
    private static Particle particleOf(String name) {
        String key = name.trim().toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "");
        Particle particle = Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(key));
        return particle != null && particle.getDataType() == Void.class ? particle : null;
    }

    private void command(Player player, ItemAction.Command action) {
        if (!config.settings().packs().allowCommands()) {
            warnOnce("commands-disabled", "An item tried to run a command, but packs.security.allow-commands is false. "
                    + "Enable it in config.yml only for packs you trust.");
            return;
        }
        Location loc = player.getLocation();
        String command = action.command()
                .replace("<player>", safe(player.getName()))
                .replace("<uuid>", player.getUniqueId().toString())
                .replace("<world>", safe(loc.getWorld().getName()))
                .replace("<x>", String.valueOf(loc.getBlockX()))
                .replace("<y>", String.valueOf(loc.getBlockY()))
                .replace("<z>", String.valueOf(loc.getBlockZ()));
        if (ItemAction.Command.CONSOLE.equals(action.executor())) {
            schedulers.global(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        } else {
            player.performCommand(command);
        }
    }

    /** Only characters that cannot break out of a command argument are substituted. */
    private static String safe(String value) {
        return SAFE_PLACEHOLDER.matcher(value).matches() ? value : "_";
    }

    private void potion(Player player, ItemAction.PotionEffect action) {
        String key = action.effect().trim().toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "");
        PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(key));
        if (type == null) {
            warnOnce("effect " + action.effect(), "Unknown potion effect '" + action.effect() + "' in an item action");
            return;
        }
        player.addPotionEffect(new PotionEffect(type, action.durationTicks(), action.amplifier()));
    }

    /**
     * Ray trace with {@code world.rayTraceEntities} from the player's eyes. The ray also hits the Interaction hitbox of
     * custom mobs and resolves it to the mob itself.
     */
    private void damageRay(Player player, ItemAction.DamageRay ray) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        World world = player.getWorld();
        RayTraceResult hit = world.rayTraceEntities(eye, direction, ray.range(), ray.raySize(),
                entity -> !entity.equals(player) && (entity instanceof LivingEntity || isModelHitbox(entity)));
        double distance = ray.range();
        if (hit != null && hit.getHitEntity() != null) {
            distance = eye.toVector().distance(hit.getHitPosition());
            LivingEntity victim = resolveVictim(hit.getHitEntity());
            if (victim != null && !victim.equals(player)) {
                victim.damage(ray.damage(), player);
                if (ray.knockback() > 0.0D) {
                    victim.setVelocity(victim.getVelocity().add(direction.clone().multiply(ray.knockback()).setY(0.2D)));
                }
            }
        }
        if (ray.trailParticle() != null) {
            trail(eye, direction, distance, ray.trailParticle());
        }
    }

    private boolean isModelHitbox(Entity entity) {
        return entity instanceof Interaction && entity.getPersistentDataContainer().has(models.hitboxKey());
    }

    private LivingEntity resolveVictim(Entity hit) {
        if (hit instanceof LivingEntity living) {
            return living;
        }
        UUID anchor = models.anchorOf(hit);
        return anchor == null ? null : mobs.carrierOf(anchor);
    }

    /** Particle positions along the ray are computed off-thread and spawned on the region that owns the start. */
    private void trail(Location start, Vector direction, double length, String particleName) {
        Particle particle = particleOf(particleName);
        if (particle == null) {
            warnOnce("particle " + particleName, "Unknown or unsupported trail particle '" + particleName + "'");
            return;
        }
        Location origin = start.clone();
        Vector dir = direction.clone();
        schedulers.async(() -> {
            List<Vector> points = new ArrayList<>();
            for (double d = 1.0D; d <= length; d += 0.5D) {
                points.add(origin.toVector().add(dir.clone().multiply(d)));
            }
            schedulers.region(origin, () -> {
                World world = origin.getWorld();
                for (Vector point : points) {
                    world.spawnParticle(particle, point.getX(), point.getY(), point.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
            });
        });
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            log.warning(message);
        }
    }
}
