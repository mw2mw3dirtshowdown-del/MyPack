package com.operator.mypack.listeners;

import com.operator.mypack.content.loot.LootEvaluator;
import com.operator.mypack.content.mob.MobDefinition;
import com.operator.mypack.content.mob.ModelBinding;
import com.operator.mypack.model.runtime.ModelInstance;
import com.operator.mypack.pack.ContentRegistry;
import com.operator.mypack.services.LootService;
import com.operator.mypack.services.MobService;
import com.operator.mypack.services.ModelService;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Behaviour of custom mobs: animation triggers, damage forwarding from the model hitbox, and death (loot + death
 * animation). The vanilla drops of the carrier entity are always removed.
 */
public final class MobListener implements Listener {

    private final ContentRegistry registry;
    private final MobService mobs;
    private final ModelService models;
    private final LootService loot;
    /** Last tick in which a hit of a player on a hitbox was forwarded; prevents double damage from two events. */
    private final Map<UUID, Long> lastForward = new ConcurrentHashMap<>();

    public MobListener(ContentRegistry registry, MobService mobs, ModelService models, LootService loot) {
        this.registry = registry;
        this.mobs = mobs;
        this.models = models;
        this.loot = loot;
    }

    // ------------------------------------------------------------------ animations

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        ModelInstance instance = models.get(event.getEntity().getUniqueId());
        if (instance != null && !instance.isDead()) {
            instance.requestOneShot(ModelBinding.HURT);
        }
    }

    /**
     * A hit on the Interaction hitbox is forwarded to the mob and the original event is cancelled (an Interaction entity
     * cannot take damage); a mob that attacks plays its attack animation.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        if (victim instanceof Interaction hitbox && hitbox.getPersistentDataContainer().has(models.hitboxKey())) {
            event.setCancelled(true);
            if (event.getDamager() instanceof Player player) {
                forward(player, models.anchorOf(hitbox));
            }
            return;
        }
        ModelInstance attacker = models.get(event.getDamager().getUniqueId());
        if (attacker != null && !attacker.isDead()) {
            attacker.requestOneShot(ModelBinding.ATTACK);
        }
    }

    /** Paper's pre-attack event covers the same click even when the server does not raise a damage event for it. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        if (event.getAttacked() instanceof Interaction hitbox && hitbox.getPersistentDataContainer().has(models.hitboxKey())) {
            event.setCancelled(true);
            forward(event.getPlayer(), models.anchorOf(hitbox));
        }
    }

    private void forward(Player player, UUID anchorId) {
        if (anchorId == null) {
            return;
        }
        long tick = models.currentTick();
        Long previous = lastForward.put(player.getUniqueId(), tick);
        if (previous != null && previous == tick) {
            return;
        }
        LivingEntity carrier = mobs.carrierOf(anchorId);
        if (carrier == null || !carrier.isValid()) {
            return;
        }
        carrier.damage(attackDamage(player), player);
        player.resetCooldown();
    }

    /** Vanilla melee damage: the attribute value (includes the held weapon) scaled by the attack cooldown. */
    private static double attackDamage(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.ATTACK_DAMAGE);
        double base = attribute == null ? 1.0D : attribute.getValue();
        double cooldown = player.getAttackCooldown();
        return base * (0.2D + cooldown * cooldown * 0.8D);
    }

    // ------------------------------------------------------------------ death

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        String id = mobs.mobIdOf(entity);
        if (id == null) {
            return;
        }
        event.getDrops().clear(); // vanilla drops of the carrier never apply
        MobDefinition definition = registry.mob(id);
        if (definition != null && definition.lootTable() != null) {
            Player killer = entity.getKiller();
            int looting = killer == null ? 0 : killer.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.LOOTING);
            event.getDrops().addAll(loot.roll(definition.lootTable(), new LootEvaluator.Context(killer != null, looting)));
        }
        ModelInstance instance = models.get(entity.getUniqueId());
        if (instance != null) {
            instance.markDead(models.currentTick());
        }
        mobs.died(entity);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastForward.remove(event.getPlayer().getUniqueId());
    }
}
