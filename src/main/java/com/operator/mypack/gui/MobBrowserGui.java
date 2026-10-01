package com.operator.mypack.gui;

import com.operator.mypack.config.LangManager;
import com.operator.mypack.content.mob.MobDefinition;
import com.operator.mypack.utils.TextUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Browse custom mobs (optionally of one namespace); click to spawn one at your feet. */
public final class MobBrowserGui extends PaginatedGui<MobDefinition> {

    private final GuiContext ctx;
    private final String namespace;

    public MobBrowserGui(GuiContext ctx, String namespaceOrNull) {
        super(ctx.lang(), ctx.schedulers(), mobsOf(ctx, namespaceOrNull));
        this.ctx = ctx;
        this.namespace = namespaceOrNull;
    }

    private static List<MobDefinition> mobsOf(GuiContext ctx, String namespace) {
        List<MobDefinition> mobs = new ArrayList<>();
        for (MobDefinition mob : ctx.registry().snapshot().mobs().values()) {
            if (namespace == null || mob.id().namespace().equals(namespace)) {
                mobs.add(mob);
            }
        }
        mobs.sort(Comparator.comparing(m -> m.id().full()));
        return mobs;
    }

    @Override
    protected Component title() {
        return namespace == null ? lang.get("gui.mobs.title")
                : lang.get("gui.mobs.title-pack", Placeholder.unparsed("namespace", namespace));
    }

    @Override
    protected ItemStack icon(MobDefinition mob) {
        Material egg = Material.matchMaterial(mob.baseEntity().toUpperCase(Locale.ROOT) + "_SPAWN_EGG");
        String name = mob.displayName() != null && !mob.displayName().isBlank() ? mob.displayName()
                : "<white>" + TextUtils.escape(mob.id().path());
        return GuiItems.of(egg != null ? egg : Material.ZOMBIE_SPAWN_EGG, TextUtils.mm(name), List.of(
                lang.get("gui.mobs.id", Placeholder.unparsed("id", mob.id().full())),
                lang.get("gui.mobs.health", LangManager.number("health", (int) mob.maxHealth())),
                lang.get("gui.mobs.model", Placeholder.unparsed("model", mob.model() == null ? "-" : mob.model().geometry())),
                Component.empty(),
                lang.get("gui.mobs.click")));
    }

    @Override
    protected void onSelect(MobDefinition mob, InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        if (!player.hasPermission("mypack.spawn")) {
            lang.send(player, "gui.no-permission");
            return;
        }
        if (ctx.mobs().spawn(mob, player.getLocation()) != null) {
            lang.send(player, "gui.mobs.spawned", Placeholder.unparsed("id", mob.id().full()));
        } else {
            lang.send(player, "gui.mobs.failed", Placeholder.unparsed("id", mob.id().full()));
        }
    }
}
