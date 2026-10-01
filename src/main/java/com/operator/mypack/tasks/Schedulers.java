package com.operator.mypack.tasks;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Single entry point for every task the plugin schedules. It is built exclusively on Paper's global / region /
 * entity / async schedulers, which work on regular Paper (everything runs on the main thread) and on Folia
 * (everything runs on the owning region thread). The legacy {@code BukkitScheduler} is never touched, because it
 * throws on Folia.
 *
 * <p>Rules of thumb used throughout MyPack: database access, file I/O, zip/JSON work and particle math run through
 * {@link #async(Runnable)}; anything that mutates the world, an entity or an inventory runs through
 * {@link #global(Runnable)}, {@link #region(Location, Runnable)} or {@link #entity(Entity, Runnable, Runnable)}.</p>
 */
public final class Schedulers {

    private final Plugin plugin;
    private final boolean folia;

    public Schedulers(Plugin plugin) {
        this.plugin = plugin;
        this.folia = detectFolia();
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public boolean isFolia() {
        return folia;
    }

    // ------------------------------------------------------------------ async

    public ScheduledTask async(Runnable task) {
        return Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
    }

    public ScheduledTask asyncLater(long delayMillis, Runnable task) {
        return Bukkit.getAsyncScheduler().runDelayed(plugin, t -> task.run(), Math.max(1L, delayMillis), TimeUnit.MILLISECONDS);
    }

    public ScheduledTask asyncTimer(long initialMillis, long periodMillis, Consumer<ScheduledTask> task) {
        return Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task, Math.max(1L, initialMillis),
                Math.max(1L, periodMillis), TimeUnit.MILLISECONDS);
    }

    public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            async(() -> complete(future, supplier));
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    // ------------------------------------------------------------------ global region

    public void global(Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    public ScheduledTask globalLater(long delayTicks, Runnable task) {
        return Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), Math.max(1L, delayTicks));
    }

    public ScheduledTask globalTimer(long delayTicks, long periodTicks, Consumer<ScheduledTask> task) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task, Math.max(1L, delayTicks),
                Math.max(1L, periodTicks));
    }

    public <T> CompletableFuture<T> supplyGlobal(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            global(() -> complete(future, supplier));
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    // ------------------------------------------------------------------ region (location owned)

    public void region(Location location, Runnable task) {
        Bukkit.getRegionScheduler().execute(plugin, location, task);
    }

    public ScheduledTask regionLater(Location location, long delayTicks, Runnable task) {
        return Bukkit.getRegionScheduler().runDelayed(plugin, location, t -> task.run(), Math.max(1L, delayTicks));
    }

    // ------------------------------------------------------------------ entity owned

    /**
     * Runs {@code task} on the thread that owns {@code entity}. {@code retired} (may be {@code null}) runs instead if
     * the entity was removed before the task could execute.
     *
     * @return {@code false} when the entity is already retired and the task was not scheduled
     */
    public boolean entity(Entity entity, Runnable task, Runnable retired) {
        return entity.getScheduler().run(plugin, t -> task.run(), retired) != null;
    }

    public ScheduledTask entityLater(Entity entity, long delayTicks, Runnable task, Runnable retired) {
        return entity.getScheduler().runDelayed(plugin, t -> task.run(), retired, Math.max(1L, delayTicks));
    }

    public ScheduledTask entityTimer(Entity entity, long delayTicks, long periodTicks,
                                     Consumer<ScheduledTask> task, Runnable retired) {
        return entity.getScheduler().runAtFixedRate(plugin, task, retired, Math.max(1L, delayTicks),
                Math.max(1L, periodTicks));
    }

    // ------------------------------------------------------------------ lifecycle

    /** Cancels every global and async task of this plugin. Entity tasks are cancelled by their owners. */
    public void cancelAll() {
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        Bukkit.getAsyncScheduler().cancelTasks(plugin);
    }

    private static <T> void complete(CompletableFuture<T> future, Supplier<T> supplier) {
        try {
            future.complete(supplier.get());
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
    }
}
