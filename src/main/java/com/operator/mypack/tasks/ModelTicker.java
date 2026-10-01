package com.operator.mypack.tasks;

import com.operator.mypack.config.ConfigManager;
import com.operator.mypack.model.runtime.ModelInstance;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Drives every animated {@link ModelInstance}. Animation math never runs on a game thread: each tick the owning thread
 * only (1) applies the frame that was computed during the previous tick, (2) reads what it needs from the anchor
 * entity, and (3) hands the pure computation to the async scheduler.
 *
 * <ul>
 *   <li><b>Paper</b>: one global repeating task walks all instances and submits one async batch.</li>
 *   <li><b>Folia</b>: entities of different regions must not be touched from one thread, so every instance gets its own
 *       entity-scheduler timer on its anchor and computes asynchronously on its own.</li>
 * </ul>
 */
public final class ModelTicker {

    private record Entry(ModelInstance instance, Entity anchor) {
    }

    private record Work(ModelInstance instance, ModelInstance.FrameInput input) {
    }

    private final Schedulers schedulers;
    private final ConfigManager config;
    private final Logger log;
    private final Consumer<ModelInstance> onFinished;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> entityTasks = new ConcurrentHashMap<>();
    private final AtomicLong tick = new AtomicLong();
    private final AtomicBoolean batchRunning = new AtomicBoolean();
    private ScheduledTask globalTask;

    /**
     * @param onFinished called (on the owning thread) when an instance must be torn down: its anchor was removed, the
     *                   death animation ended, or the anchor changed world
     */
    public ModelTicker(Schedulers schedulers, ConfigManager config, Logger log, Consumer<ModelInstance> onFinished) {
        this.schedulers = schedulers;
        this.config = config;
        this.log = log;
        this.onFinished = onFinished;
    }

    /** Plugin-wide monotonic tick counter (also valid on Folia, unlike {@code Bukkit#getCurrentTick}). */
    public long currentTick() {
        return tick.get();
    }

    public int activeCount() {
        return entries.size();
    }

    public synchronized void start() {
        if (globalTask == null) {
            globalTask = schedulers.globalTimer(1L, 1L, task -> onGlobalTick());
        }
    }

    public synchronized void stop() {
        if (globalTask != null) {
            globalTask.cancel();
            globalTask = null;
        }
        for (ScheduledTask task : entityTasks.values()) {
            task.cancel();
        }
        entityTasks.clear();
        entries.clear();
    }

    public void register(ModelInstance instance, Entity anchor) {
        UUID id = instance.anchorId();
        Entry entry = new Entry(instance, anchor);
        entries.put(id, entry);
        if (schedulers.isFolia()) {
            int interval = config.settings().models().updateIntervalTicks();
            ScheduledTask task = schedulers.entityTimer(anchor, 1L, interval, t -> tickOne(entry),
                    () -> finish(instance));
            if (task != null) {
                entityTasks.put(id, task);
            }
        }
    }

    public void unregister(UUID anchorId) {
        entries.remove(anchorId);
        ScheduledTask task = entityTasks.remove(anchorId);
        if (task != null) {
            task.cancel();
        }
    }

    // ------------------------------------------------------------------ Paper: one global task

    private void onGlobalTick() {
        long now = tick.incrementAndGet();
        if (schedulers.isFolia() || entries.isEmpty()) {
            return;
        }
        if (now % config.settings().models().updateIntervalTicks() != 0L) {
            return;
        }
        List<Work> work = new ArrayList<>();
        for (Entry entry : entries.values()) {
            try {
                Work w = prepare(entry, now);
                if (w != null) {
                    work.add(w);
                }
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Model tick failed for " + entry.instance().ownerId(), e);
            }
        }
        if (work.isEmpty()) {
            return;
        }
        if (!batchRunning.compareAndSet(false, true)) {
            work.forEach(w -> w.instance().cancelCompute()); // previous batch still running: skip this frame
            return;
        }
        schedulers.async(() -> {
            try {
                for (Work w : work) {
                    try {
                        w.instance().compute(w.input());
                    } catch (RuntimeException e) {
                        log.log(Level.WARNING, "Model compute failed for " + w.instance().ownerId(), e);
                    }
                }
            } finally {
                batchRunning.set(false);
            }
        });
    }

    // ------------------------------------------------------------------ Folia: one task per instance

    private void tickOne(Entry entry) {
        try {
            Work w = prepare(entry, tick.get());
            if (w != null) {
                schedulers.async(() -> {
                    try {
                        w.instance().compute(w.input());
                    } catch (RuntimeException e) {
                        log.log(Level.WARNING, "Model compute failed for " + w.instance().ownerId(), e);
                    }
                });
            }
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Model tick failed for " + entry.instance().ownerId(), e);
        }
    }

    // ------------------------------------------------------------------ shared sync step

    /** Applies the previous frame, follows the anchor and claims the compute slot; {@code null} when nothing to do. */
    private Work prepare(Entry entry, long now) {
        ModelInstance instance = entry.instance();
        Entity anchor = entry.anchor();
        instance.applyPending();
        if (!anchor.isValid() && !instance.isDead()) {
            finish(instance);
            return null;
        }
        ModelInstance.FrameInput input = instance.capture(anchor, now);
        if (input == null || instance.isFinished(now)) {
            finish(instance);
            return null;
        }
        if (!instance.needsCompute(input) || !instance.beginCompute()) {
            return null;
        }
        return new Work(instance, input);
    }

    private void finish(ModelInstance instance) {
        unregister(instance.anchorId());
        onFinished.accept(instance);
    }
}
