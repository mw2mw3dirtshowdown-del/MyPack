package com.operator.mypack.gui;

import com.operator.mypack.config.LangManager;
import com.operator.mypack.pack.ContentRegistry;
import com.operator.mypack.utils.TextUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Browse custom items and furniture (optionally of one namespace); click to take one, shift-click for a full stack. */
public final class ItemBrowserGui extends PaginatedGui<String> {

    private final GuiContext ctx;
    private final String namespace;

    public ItemBrowserGui(GuiContext ctx, String namespaceOrNull) {
        super(ctx.lang(), ctx.schedulers(), idsOf(ctx.registry().snapshot(), namespaceOrNull));
        this.ctx = ctx;
        this.namespace = namespaceOrNull;
    }

    private static List<String> idsOf(ContentRegistry.Snapshot snapshot, String namespace) {
        List<String> ids = new ArrayList<>();
        snapshot.items().keySet().stream().filter(id -> matches(id, namespace)).forEach(ids::add);
        snapshot.furniture().keySet().stream().filter(id -> matches(id, namespace)).forEach(ids::add);
        ids.sort(String::compareTo);
        return ids;
    }

    private static boolean matches(String id, String namespace) {
        return namespace == null || id.startsWith(namespace + ":");
    }

    @Override
    protected Component title() {
        return namespace == null ? lang.get("gui.items.title")
                : lang.get("gui.items.title-pack", Placeholder.unparsed("namespace", namespace));
    }

    @Override
    protected ItemStack icon(String id) {
        ItemStack stack = ctx.items().createFromId(id, 1);
        if (stack == null) {
            return GuiItems.of(Material.BARRIER, TextUtils.mm("<red>" + TextUtils.escape(id)));
        }
        stack.editMeta(meta -> {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.empty());
            lore.add(TextUtils.noItalic(lang.get("gui.items.id", Placeholder.unparsed("id", id))));
            lore.add(TextUtils.noItalic(lang.get("gui.items.click")));
            meta.lore(lore);
        });
        return stack;
    }

    @Override
    protected void onSelect(String id, InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        if (!player.hasPermission("mypack.give")) {
            lang.send(player, "gui.no-permission");
            return;
        }
        ItemStack stack = ctx.items().createFromId(id, event.isShiftClick() ? 64 : 1);
        if (stack == null) {
            return;
        }
        for (ItemStack leftover : player.getInventory().addItem(stack).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        lang.send(player, "gui.items.given", Placeholder.unparsed("id", id), LangManager.number("amount", stack.getAmount()));
    }
}
