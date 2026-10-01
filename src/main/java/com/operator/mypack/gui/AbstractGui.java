package com.operator.mypack.gui;

import com.operator.mypack.config.LangManager;
import com.operator.mypack.tasks.Schedulers;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Base class of every MyPack inventory GUI. The GUI object is the inventory's {@link InventoryHolder}; a single
 * {@code GuiListener} routes clicks to it, so GUIs need no listener registration of their own (and there is nothing to
 * unregister when one is closed). All clicks are cancelled: nothing can be moved in or out of a GUI.
 */
public abstract class AbstractGui implements InventoryHolder {

    protected final LangManager lang;
    protected final Schedulers schedulers;
    private final Map<Integer, Consumer<InventoryClickEvent>> handlers = new HashMap<>();
    private Inventory inventory;

    protected AbstractGui(LangManager lang, Schedulers schedulers) {
        this.lang = lang;
        this.schedulers = schedulers;
    }

    protected abstract Component title();

    /** Number of inventory rows (1-6). */
    protected abstract int rows();

    /** Fills the inventory; called on open and on every {@link #refresh()}. */
    protected abstract void render();

    /** Opens the GUI for {@code player} on the player's own thread. */
    public void open(Player player) {
        schedulers.entity(player, () -> {
            inventory = Bukkit.createInventory(this, rows() * 9, title());
            render();
            player.openInventory(inventory);
        }, null);
    }

    /** Clears and re-renders the open inventory (after a state change such as a page turn). */
    protected void refresh() {
        handlers.clear();
        inventory.clear();
        render();
    }

    protected void set(int slot, ItemStack item, Consumer<InventoryClickEvent> onClick) {
        inventory.setItem(slot, item);
        if (onClick != null) {
            handlers.put(slot, onClick);
        } else {
            handlers.remove(slot);
        }
    }

    protected void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    protected void fill(ItemStack item) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, item);
            }
        }
    }

    protected int size() {
        return inventory.getSize();
    }

    /** Called by the listener for every click inside any inventory whose holder is this GUI. */
    public final void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (inventory == null || event.getClickedInventory() != inventory) {
            return;
        }
        Consumer<InventoryClickEvent> handler = handlers.get(event.getRawSlot());
        if (handler != null) {
            handler.accept(event);
        }
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
