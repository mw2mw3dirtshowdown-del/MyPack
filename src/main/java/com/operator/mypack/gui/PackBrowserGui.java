package com.operator.mypack.gui;

import com.operator.mypack.config.LangManager;
import com.operator.mypack.pack.InstalledPack;
import com.operator.mypack.services.PackService;
import com.operator.mypack.utils.TextUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Lists every pack source with its state; clicking a loaded pack opens its content. */
public final class PackBrowserGui extends PaginatedGui<PackService.PackStatus> {

    private final GuiContext ctx;

    public PackBrowserGui(GuiContext ctx, List<PackService.PackStatus> statuses) {
        super(ctx.lang(), ctx.schedulers(), statuses);
        this.ctx = ctx;
    }

    @Override
    protected Component title() {
        return lang.get("gui.packs.title");
    }

    @Override
    protected ItemStack icon(PackService.PackStatus status) {
        Material material = switch (status.state()) {
            case LOADED -> Material.ENCHANTED_BOOK;
            case DISABLED -> Material.BOOK;
            case NOT_INSTALLED -> Material.WRITABLE_BOOK;
            case REJECTED, INVALID, MISSING -> Material.BARRIER;
        };
        List<Component> lore = new ArrayList<>();
        lore.add(lang.get("gui.packs.state", Placeholder.unparsed("state", status.state().name().toLowerCase())));
        lore.add(lang.get("gui.packs.namespace", Placeholder.unparsed("namespace", status.namespace())));
        lore.add(lang.get("gui.packs.version", Placeholder.unparsed("version", status.version())));
        InstalledPack pack = status.pack();
        if (pack != null) {
            lore.add(lang.get("gui.packs.counts",
                    LangManager.number("items", pack.content().items().size()),
                    LangManager.number("mobs", pack.content().mobs().size()),
                    LangManager.number("furniture", pack.content().furniture().size())));
            lore.add(lang.get("gui.packs.click"));
        } else {
            lore.add(TextUtils.mm("<gray>" + TextUtils.escape(status.detail())));
        }
        return GuiItems.of(material, lang.get("gui.packs.name", Placeholder.unparsed("name", status.name())), lore);
    }

    @Override
    protected void onSelect(PackService.PackStatus status, InventoryClickEvent event) {
        if (status.pack() != null) {
            new ItemBrowserGui(ctx, status.namespace()).open((Player) event.getWhoClicked());
        }
    }
}
