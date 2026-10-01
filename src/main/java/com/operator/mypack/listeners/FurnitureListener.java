package com.operator.mypack.listeners;

import com.operator.mypack.config.ConfigManager;
import com.operator.mypack.config.LangManager;
import com.operator.mypack.content.mob.FurnitureDefinition;
import com.operator.mypack.gui.FurnitureSettingsGui;
import com.operator.mypack.gui.GuiContext;
import com.operator.mypack.pack.ContentRegistry;
import com.operator.mypack.services.FurnitureService;
import com.operator.mypack.services.ItemService;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player interaction with furniture: right-click a block with a furniture item to place it, right-click furniture to
 * sit, sneak + right-click for the settings panel, left-click to pick it up (owner or {@code mypack.furniture.admin}).
 */
public final class FurnitureListener implements Listener {

    private final ContentRegistry registry;
    private final ItemService items;
    private final FurnitureService furniture;
    private final LangManager lang;
    private final ConfigManager config;
    private final GuiContext gui;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public FurnitureListener(ContentRegistry registry, ItemService items, FurnitureService furniture, LangManager lang,
                             ConfigManager config, GuiContext gui) {
        this.registry = registry;
        this.items = items;
        this.furniture = furniture;
        this.lang = lang;
        this.config = config;
        this.gui = gui;
    }

    // ------------------------------------------------------------------ placing

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getItem() == null) {
            return;
        }
        String id = items.idOf(event.getItem());
        FurnitureDefinition definition = id == null ? null : registry.furniture(id);
        if (definition == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!config.settings().furniture().enabled()) {
            lang.send(player, "furniture.disabled");
            return;
        }
        if (!player.hasPermission("mypack.furniture.place")) {
            lang.send(player, "furniture.no-permission");
            return;
        }
        if (onCooldown(player)) {
            return;
        }
        Block clicked = event.getClickedBlock();
        BlockFace face = event.getBlockFace();
        Block target = clicked.getRelative(face);
        if (!target.isPassable() || target.isLiquid()) {
            lang.send(player, "furniture.cannot-place");
            return;
        }
        Location base = target.getLocation().add(0.5D, 0.0D, 0.5D);
        if (furniture.countInChunk(base) >= config.settings().furniture().maxPerChunk()) {
            lang.send(player, "furniture.limit", Placeholder.unparsed("limit",
                    String.valueOf(config.settings().furniture().maxPerChunk())));
            return;
        }
        Interaction anchor = furniture.place(definition, base, player.getLocation().getYaw() + 180.0F, player.getUniqueId());
        if (anchor == null) {
            lang.send(player, "furniture.cannot-place");
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            ItemStack hand = player.getInventory().getItem(EquipmentSlot.HAND);
            if (hand != null) {
                hand.subtract(1);
                player.getInventory().setItem(EquipmentSlot.HAND, hand.getAmount() <= 0 ? null : hand);
            }
        }
        lang.send(player, "furniture.placed", Placeholder.unparsed("id", definition.id().full()));
    }

    // ------------------------------------------------------------------ using

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUse(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Interaction anchor)
                || furniture.furnitureIdOf(anchor) == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("mypack.furniture.use") || onCooldown(player)) {
            return;
        }
        if (player.isSneaking()) {
            if (canModify(player, anchor)) {
                new FurnitureSettingsGui(gui, anchor).open(player);
            } else {
                lang.send(player, "furniture.not-yours");
            }
            return;
        }
        if (!furniture.sit(player, anchor)) {
            lang.send(player, "furniture.cannot-sit");
        }
    }

    // ------------------------------------------------------------------ picking up

    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Interaction anchor && furniture.furnitureIdOf(anchor) != null) {
            event.setCancelled(true);
            if (event.getDamager() instanceof Player player) {
                pickUp(player, anchor);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        Entity attacked = event.getAttacked();
        if (attacked instanceof Interaction anchor && furniture.furnitureIdOf(anchor) != null) {
            event.setCancelled(true);
            pickUp(event.getPlayer(), anchor);
        }
    }

    private void pickUp(Player player, Interaction anchor) {
        // one click can raise two events; the cooldown also stops accidental double pick-ups
        if (onCooldown(player)) {
            return;
        }
        if (!canModify(player, anchor)) {
            lang.send(player, "furniture.not-yours");
            return;
        }
        furniture.pickUp(anchor, player);
        lang.send(player, "furniture.picked-up");
    }

    private boolean canModify(Player player, Interaction anchor) {
        UUID owner = furniture.ownerOf(anchor);
        return player.hasPermission("mypack.furniture.admin") || (owner != null && owner.equals(player.getUniqueId()));
    }

    private boolean onCooldown(Player player) {
        long now = System.currentTimeMillis();
        long minGap = config.settings().furniture().interactCooldownTicks() * 50L;
        Long previous = cooldowns.put(player.getUniqueId(), now);
        return previous != null && now - previous < minGap;
    }

    // ------------------------------------------------------------------ seats

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (furniture.isSeat(event.getDismounted())) {
            furniture.dismounted(event.getDismounted());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cooldowns.remove(event.getPlayer().getUniqueId());
        furniture.releasePlayer(event.getPlayer().getUniqueId());
    }
}
