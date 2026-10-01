package com.operator.mypack.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;

/** Opened by sneak + right-click on furniture: rotate it or pick it up. */
public final class FurnitureSettingsGui extends AbstractGui {

    private final GuiContext ctx;
    private final Interaction anchor;

    public FurnitureSettingsGui(GuiContext ctx, Interaction anchor) {
        super(ctx.lang(), ctx.schedulers());
        this.ctx = ctx;
        this.anchor = anchor;
    }

    @Override
    protected Component title() {
        return lang.get("gui.furniture.title");
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected void render() {
        set(10, GuiItems.of(Material.ARROW, lang.get("gui.furniture.rotate-left")), event -> {
            if (anchor.isValid()) {
                ctx.furniture().rotate(anchor, -1.0F);
            }
        });
        set(12, GuiItems.of(Material.ARROW, lang.get("gui.furniture.rotate-right")), event -> {
            if (anchor.isValid()) {
                ctx.furniture().rotate(anchor, 1.0F);
            }
        });
        set(14, GuiItems.of(Material.HOPPER, lang.get("gui.furniture.pickup")), event -> {
            Player player = (Player) event.getWhoClicked();
            if (anchor.isValid()) {
                ctx.furniture().pickUp(anchor, player);
            }
            player.closeInventory();
        });
        set(16, GuiItems.of(Material.BARRIER, lang.get("gui.close")), event -> event.getWhoClicked().closeInventory());
        fill(GuiItems.filler(Material.BLACK_STAINED_GLASS_PANE));
    }
}
