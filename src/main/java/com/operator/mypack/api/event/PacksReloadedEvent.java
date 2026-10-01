package com.operator.mypack.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Fired (synchronously, on the global region thread) after MyPack replaced its content registry, i.e. after the
 * packs were installed, enabled, disabled, removed or reloaded. Custom items and recipes of other plugins that depend
 * on MyPack content should be re-resolved when this fires.
 */
public final class PacksReloadedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final List<String> namespaces;
    private final int contentCount;

    public PacksReloadedEvent(List<String> namespaces, int contentCount) {
        super(false);
        this.namespaces = List.copyOf(namespaces);
        this.contentCount = contentCount;
    }

    /** Namespaces of all packs that are loaded now. */
    public List<String> namespaces() {
        return namespaces;
    }

    /** Total number of registered definitions (items, recipes, mobs, ...). */
    public int contentCount() {
        return contentCount;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
