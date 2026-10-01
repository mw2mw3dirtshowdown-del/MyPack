package com.operator.mypack.listeners;

import com.operator.mypack.config.ConfigManager;
import com.operator.mypack.config.Settings;
import com.operator.mypack.services.RecipeService;
import com.operator.mypack.services.ResourcePackService;
import com.operator.mypack.tasks.Schedulers;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/** Pushes the resource pack to joining players and records what their client answered. */
public final class ResourcePackListener implements Listener {

    private final ConfigManager config;
    private final Schedulers schedulers;
    private final ResourcePackService resourcePack;
    private final RecipeService recipes;

    public ResourcePackListener(ConfigManager config, Schedulers schedulers, ResourcePackService resourcePack,
                                RecipeService recipes) {
        this.config = config;
        this.schedulers = schedulers;
        this.resourcePack = resourcePack;
        this.recipes = recipes;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Settings settings = config.settings();
        if (settings.packs().discoverRecipes()) {
            recipes.discoverFor(player);
        }
        if (settings.resourcePack().enabled() && settings.resourcePack().pushOnJoin()) {
            schedulers.entityLater(player, settings.resourcePack().pushDelayTicks(), () -> {
                if (player.isOnline()) {
                    resourcePack.push(player);
                }
            }, null);
        }
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        resourcePack.onStatus(event.getPlayer(), event.getID(), event.getStatus());
    }
}
