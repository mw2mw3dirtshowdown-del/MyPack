package com.operator.mypack.listeners;

import com.operator.mypack.content.item.ItemAction;
import com.operator.mypack.content.item.ItemDefinition;
import com.operator.mypack.services.ActionExecutor;
import com.operator.mypack.services.ItemService;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Runs item scripts on right click, left click and when the holder hits an entity. Items are recognised by their
 * persistent id only. Cooldowns use the client-visible item cooldown ({@code Player#setCooldown(ItemStack, int)}), so the
 * overlay shown in the hotbar always matches the real restriction.
 */
public final class ItemListener implements Listener {

    private final ItemService items;
    private final ActionExecutor actions;

    public ItemListener(ItemService items, ActionExecutor actions) {
        this.items = items;
        this.actions = actions;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getItem() == null) {
            return;
        }
        Action action = event.getAction();
        ItemDefinition.Trigger trigger = switch (action) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> ItemDefinition.Trigger.RIGHT_CLICK;
            case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> ItemDefinition.Trigger.LEFT_CLICK;
            default -> null;
        };
        if (trigger == null) {
            return;
        }
        boolean onBlock = action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK;
        if (onBlock && event.useItemInHand() == Event.Result.DENY) {
            return; // a protection plugin denied the interaction
        }
        ItemDefinition definition = items.definitionOf(event.getItem());
        if (definition != null) {
            fire(event.getPlayer(), event.getItem(), definition, trigger);
        }
    }

    /** {@code on_hit_entity}: the holder damaged an entity with a melee attack. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return;
        }
        ItemStack held = player.getInventory().getItem(EquipmentSlot.HAND);
        ItemDefinition definition = items.definitionOf(held);
        if (definition != null) {
            fire(player, held, definition, ItemDefinition.Trigger.HIT_ENTITY);
        }
    }

    /** Custom items must not be placeable as the vanilla block of their base material (MyPack has no custom blocks). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (items.idOf(event.getItemInHand()) == null) {
            return;
        }
        ItemDefinition definition = items.definitionOf(event.getItemInHand());
        if (definition == null || !definition.tags().contains("placeable")) {
            event.setCancelled(true);
        }
    }

    private void fire(Player player, ItemStack stack, ItemDefinition definition, ItemDefinition.Trigger trigger) {
        List<ItemAction> script = definition.actions().forTrigger(trigger);
        if (script.isEmpty() || player.hasCooldown(stack)) {
            return;
        }
        actions.run(player, EquipmentSlot.HAND, script);
        int cooldown = definition.actions().cooldownTicks();
        if (cooldown > 0) {
            ItemStack held = player.getInventory().getItem(EquipmentSlot.HAND);
            if (held != null && !held.getType().isAir()) {
                player.setCooldown(held, cooldown);
            }
        }
    }
}
