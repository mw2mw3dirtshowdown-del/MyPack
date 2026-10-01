package com.operator.mypack.gui;

import com.operator.mypack.config.LangManager;
import com.operator.mypack.tasks.Schedulers;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * A six row GUI that shows a list in pages of 45 entries with previous / next / close buttons in the bottom row.
 * Entries beyond one page never overflow the inventory: they are reachable with the arrows.
 */
public abstract class PaginatedGui<T> extends AbstractGui {

    /** Entries per page: the top five rows. */
    public static final int PER_PAGE = 45;
    private static final int SLOT_PREVIOUS = 45;
    private static final int SLOT_CENTER = 49;
    private static final int SLOT_NEXT = 53;

    private final List<T> entries;
    private int page;

    protected PaginatedGui(LangManager lang, Schedulers schedulers, List<T> entries) {
        super(lang, schedulers);
        this.entries = entries;
    }

    protected abstract ItemStack icon(T entry);

    protected abstract void onSelect(T entry, InventoryClickEvent event);

    /** Optional extra buttons in the bottom row (slots 46-48 and 50-52). */
    protected void renderExtras() {
    }

    @Override
    protected final int rows() {
        return 6;
    }

    @Override
    protected final void render() {
        Paging paging = Paging.of(entries.size(), PER_PAGE, page);
        page = paging.index();
        for (int i = paging.from(); i < paging.to(); i++) {
            T entry = entries.get(i);
            set(i - paging.from(), icon(entry), event -> onSelect(entry, event));
        }
        if (paging.hasPrevious()) {
            set(SLOT_PREVIOUS, GuiItems.of(Material.ARROW, lang.get("gui.previous")), event -> {
                page--;
                refresh();
            });
        }
        if (paging.hasNext()) {
            set(SLOT_NEXT, GuiItems.of(Material.ARROW, lang.get("gui.next")), event -> {
                page++;
                refresh();
            });
        }
        set(SLOT_CENTER, GuiItems.of(Material.BARRIER, lang.get("gui.close"),
                List.of(lang.get("gui.page", LangManager.number("page", paging.index() + 1),
                        LangManager.number("pages", paging.pageCount())))),
                event -> event.getWhoClicked().closeInventory());
        renderExtras();
        for (int slot = 45; slot < 54; slot++) {
            if (getInventory().getItem(slot) == null) {
                set(slot, GuiItems.filler(Material.GRAY_STAINED_GLASS_PANE));
            }
        }
    }
}
