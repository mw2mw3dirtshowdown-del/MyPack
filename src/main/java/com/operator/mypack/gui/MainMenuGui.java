package com.operator.mypack.gui;

import com.operator.mypack.config.LangManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Entry point of {@code /mypack browse}: packs, items and furniture, mobs. */
public final class MainMenuGui extends AbstractGui {

    private final GuiContext ctx;

    public MainMenuGui(GuiContext ctx) {
        super(ctx.lang(), ctx.schedulers());
        this.ctx = ctx;
    }

    @Override
    protected Component title() {
        return lang.get("gui.main.title");
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected void render() {
        var snapshot = ctx.registry().snapshot();
        set(11, GuiItems.of(Material.ENCHANTED_BOOK, lang.get("gui.main.packs"),
                List.of(lang.get("gui.main.packs-lore", LangManager.number("count", ctx.packs().loadedPacks().size())))),
                event -> new PackBrowserGui(ctx, ctx.packs().statuses()).open((Player) event.getWhoClicked()));
        set(13, GuiItems.of(Material.CHEST, lang.get("gui.main.items"),
                List.of(lang.get("gui.main.items-lore",
                        LangManager.number("count", snapshot.items().size() + snapshot.furniture().size())))),
                event -> new ItemBrowserGui(ctx, null).open((Player) event.getWhoClicked()));
        set(15, GuiItems.of(Material.ZOMBIE_SPAWN_EGG, lang.get("gui.main.mobs"),
                List.of(lang.get("gui.main.mobs-lore", LangManager.number("count", snapshot.mobs().size())))),
                event -> new MobBrowserGui(ctx, null).open((Player) event.getWhoClicked()));
        fill(GuiItems.filler(Material.BLACK_STAINED_GLASS_PANE));
    }
}
