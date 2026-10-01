package com.operator.mypack.model.runtime;

import com.operator.mypack.content.mob.ModelBinding;
import com.operator.mypack.model.anim.Animation;
import com.operator.mypack.model.anim.AnimationPlayer;
import com.operator.mypack.model.anim.MolangContext;
import com.operator.mypack.model.anim.PoseSet;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The live 3D model of one mob or piece of furniture: a <em>flat</em> list of {@link ItemDisplay} entities, one per
 * cube, plus (for mobs) an {@link Interaction} hitbox. Displays are never mounted on each other: a passenger inherits
 * only its vehicle's position, so a rotating parent bone would leave its children behind. Instead the bone matrices of
 * the whole hierarchy are composed here ({@link ModelBlueprint#solve}) and each cube receives its final matrix.
 *
 * <h2>Threading</h2>
 * <ul>
 *   <li>{@link #capture}, {@link #applyPending}, {@link #spawn} and {@link #remove} touch entities and therefore run on
 *       the thread that owns the anchor entity.</li>
 *   <li>{@link #compute} is pure math (Molang, animation blending, matrix composition, change detection). It runs on an
 *       async thread; its result is handed over through an {@link AtomicReference} and applied on the next tick.</li>
 * </ul>
 */
public final class ModelInstance {

    /** What the owning thread observed this tick. */
    public record FrameInput(long tick, double speed, boolean dead) {
    }

    /** The cubes whose matrix changed since the previous frame, with the new matrices (column-major, 16 floats). */
    private record Frame(int[] cubes, float[][] matrices) {
    }

    private static final double WALK_SPEED_THRESHOLD = 0.01D;
    private static final float MATRIX_EPSILON = 1.0E-4F;
    private static final int MAX_DEATH_TICKS = 60;

    private final UUID anchorId;
    private final String ownerId;
    private final ModelBlueprint blueprint;
    private final ModelBinding binding;
    private final Map<String, Animation> animations;
    private final Matrix4f root;
    private final ModelBlueprint.Hitbox hitboxSpec;
    private final boolean folia;

    // --- state owned by the compute (async) side -------------------------------------------------------------
    private final PoseSet poses;
    private final AnimationPlayer player = new AnimationPlayer();
    private final MolangContext molang = new MolangContext();
    private final Matrix4f[] boneScratch;
    private final Matrix4f[] cubeMatrices;
    private final float[][] lastSent;
    private final long bornTick;

    // --- state shared between both sides ----------------------------------------------------------------------
    private final AtomicReference<Frame> pending = new AtomicReference<>();
    private final AtomicBoolean computing = new AtomicBoolean();
    private final Queue<String> oneShots = new ConcurrentLinkedQueue<>();
    private volatile boolean dead;
    private volatile long deadSinceTick = -1L;
    private volatile boolean animating;

    // --- state owned by the entity thread ---------------------------------------------------------------------
    private final List<ItemDisplay> displays = new ArrayList<>();
    private Interaction hitbox;
    private World world;
    private double lastX;
    private double lastY;
    private double lastZ;
    private float lastYaw;
    private boolean positioned;

    /**
     * @param hitboxOverride explicit hitbox from the definition, or {@code null} to derive it from the model bounds
     * @param folia          {@code true} on Folia, where entities must be moved with {@code teleportAsync}
     */
    public ModelInstance(UUID anchorId, String ownerId, ModelBlueprint blueprint, ModelBinding binding,
                         Map<String, Animation> animations, long bornTick, ModelBlueprint.Hitbox hitboxOverride, boolean folia) {
        this.anchorId = anchorId;
        this.ownerId = ownerId;
        this.blueprint = blueprint;
        this.binding = binding;
        this.animations = animations;
        this.bornTick = bornTick;
        this.folia = folia;
        this.root = new Matrix4f().translate(0.0F, (float) binding.yOffset(), 0.0F)
                .scale((float) binding.scale());
        this.hitboxSpec = hitboxOverride != null ? hitboxOverride : blueprint.hitbox(binding.scale());
        this.poses = new PoseSet(blueprint.bones().size());
        this.boneScratch = blueprint.newBoneMatrices();
        this.cubeMatrices = blueprint.newCubeMatrices();
        this.lastSent = new float[blueprint.cubes().size()][16];
    }

    public UUID anchorId() {
        return anchorId;
    }

    public String ownerId() {
        return ownerId;
    }

    public ModelBinding binding() {
        return binding;
    }

    public ModelBlueprint blueprint() {
        return blueprint;
    }

    public ModelBlueprint.Hitbox hitboxSpec() {
        return hitboxSpec;
    }

    public Interaction hitbox() {
        return hitbox;
    }

    public List<ItemDisplay> displays() {
        return displays;
    }

    /** {@code true} when the model has animations to play (otherwise the instance only has to follow its anchor). */
    public boolean isAnimated() {
        return !animations.isEmpty();
    }

    // ------------------------------------------------------------------ spawning (entity thread)

    /**
     * Spawns one display per cube at {@code origin} (the anchor's feet) and, when {@code spawnHitbox} is set, an
     * Interaction entity sized from the model bounds.
     *
     * @param partKey   PDC key that marks every spawned entity with the anchor's UUID
     * @param hitboxKey PDC key that marks the hitbox with the anchor's UUID (used to forward damage)
     */
    public void spawn(Location origin, String namespace, NamespacedKey partKey, NamespacedKey hitboxKey, float viewRange,
                      boolean spawnHitbox) {
        this.world = origin.getWorld();
        blueprint.solve(poses, root, boneScratch, cubeMatrices);
        Location displayLocation = displayLocation(origin);
        List<ModelBlueprint.CubeNode> cubes = blueprint.cubes();
        for (int i = 0; i < cubes.size(); i++) {
            ModelBlueprint.CubeNode node = cubes.get(i);
            ItemStack item = new ItemStack(Material.STICK);
            item.editMeta(meta -> meta.setItemModel(new NamespacedKey(namespace, node.path())));
            Matrix4f matrix = new Matrix4f(cubeMatrices[i]);
            matrix.get(lastSent[i]);
            ItemDisplay display = world.spawn(displayLocation, ItemDisplay.class, d -> {
                d.setItemStack(item);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setBillboard(Display.Billboard.FIXED);
                d.setInterpolationDuration(1);
                d.setInterpolationDelay(0);
                d.setTeleportDuration(1);
                d.setShadowRadius(0.0F);
                d.setShadowStrength(0.0F);
                d.setViewRange(viewRange);
                d.setPersistent(false);
                d.setInvulnerable(true);
                d.setTransformationMatrix(matrix);
                d.getPersistentDataContainer().set(partKey, PersistentDataType.STRING, anchorId.toString());
            });
            displays.add(display);
        }
        if (spawnHitbox) {
            Location hitLocation = origin.clone().add(0.0D, hitboxSpec.offsetY(), 0.0D);
            hitbox = world.spawn(hitLocation, Interaction.class, h -> {
                h.setInteractionWidth(hitboxSpec.width());
                h.setInteractionHeight(hitboxSpec.height());
                h.setResponsive(true);
                h.setPersistent(false);
                h.getPersistentDataContainer().set(hitboxKey, PersistentDataType.STRING, anchorId.toString());
            });
        }
        remember(origin);
    }

    /** Display entities face the model's front; Bedrock models face -Z while an entity with yaw 0 faces +Z. */
    private static Location displayLocation(Location anchor) {
        Location loc = anchor.clone();
        loc.setYaw(anchor.getYaw() + 180.0F);
        loc.setPitch(0.0F);
        return loc;
    }

    private void remember(Location loc) {
        lastX = loc.getX();
        lastY = loc.getY();
        lastZ = loc.getZ();
        lastYaw = loc.getYaw();
        positioned = true;
    }

    // ------------------------------------------------------------------ per tick (entity thread)

    /** Applies the frame computed during the previous tick. */
    public void applyPending() {
        Frame frame = pending.getAndSet(null);
        if (frame == null) {
            return;
        }
        for (int k = 0; k < frame.cubes().length; k++) {
            int cube = frame.cubes()[k];
            if (cube < displays.size()) {
                ItemDisplay display = displays.get(cube);
                if (display.isValid()) {
                    display.setTransformationMatrix(new Matrix4f().set(frame.matrices()[k]));
                }
            }
        }
    }

    /**
     * Follows the anchor (teleports displays and hitbox when it moved or turned) and reports what the compute side
     * needs. Returns {@code null} when the anchor's world changed, in which case the instance must be re-created.
     */
    public FrameInput capture(Entity anchor, long tick) {
        Location loc = anchor.getLocation();
        if (loc.getWorld() != world) {
            return null;
        }
        double dx = loc.getX() - lastX;
        double dz = loc.getZ() - lastZ;
        boolean moved = !positioned || dx != 0.0D || loc.getY() != lastY || dz != 0.0D || loc.getYaw() != lastYaw;
        double speed = Math.sqrt(dx * dx + dz * dz);
        if (moved) {
            Location target = displayLocation(loc);
            for (ItemDisplay display : displays) {
                if (display.isValid()) {
                    move(display, target);
                }
            }
            if (hitbox != null && hitbox.isValid()) {
                move(hitbox, loc.clone().add(0.0D, hitboxSpec.offsetY(), 0.0D));
            }
            remember(loc);
        }
        return new FrameInput(tick, speed, dead);
    }

    /**
     * Moves a model entity to follow its anchor. Paper teleports immediately (no future, no extra tick of lag); Folia
     * does not allow the synchronous call and needs {@code teleportAsync}.
     */
    private void move(Entity entity, Location target) {
        if (folia) {
            entity.teleportAsync(target);
        } else {
            entity.teleport(target);
        }
    }

    /** {@code true} when this tick needs a compute pass (something moves or an animation is running). */
    public boolean needsCompute(FrameInput input) {
        return isAnimated() || animating || input.dead();
    }

    /** Claims the compute slot; returns {@code false} when the previous frame is still being computed. */
    public boolean beginCompute() {
        return computing.compareAndSet(false, true);
    }

    /** Releases the compute slot claimed by {@link #beginCompute()} when the frame is not going to be computed. */
    public void cancelCompute() {
        computing.set(false);
    }

    /** Every entity this model spawned (displays and the hitbox), for removal on each entity's own thread. */
    public List<Entity> entities() {
        List<Entity> all = new ArrayList<>(displays);
        if (hitbox != null) {
            all.add(hitbox);
        }
        return all;
    }

    // ------------------------------------------------------------------ compute (async thread)

    /** Pure math: chooses animations, evaluates them, composes the hierarchy and detects changed cubes. */
    public void compute(FrameInput input) {
        try {
            long now = input.tick();
            Animation base = null;
            if (!input.dead()) {
                String state = input.speed() > WALK_SPEED_THRESHOLD ? ModelBinding.WALK : ModelBinding.IDLE;
                base = animations.get(state);
                if (base == null) {
                    base = animations.get(ModelBinding.IDLE);
                }
            }
            player.setBase(base, now);
            String request;
            while ((request = oneShots.poll()) != null) {
                Animation oneShot = animations.get(request);
                if (oneShot != null) {
                    player.playOneShot(oneShot, now);
                }
            }
            molang.setQuery("life_time", Math.max(0L, now - bornTick) / 20.0D)
                    .setQuery("ground_speed", input.speed() * 20.0D)
                    .setQuery("is_moving", input.speed() > WALK_SPEED_THRESHOLD ? 1.0D : 0.0D)
                    .setQuery("modified_distance_moved", Math.max(0L, now - bornTick) * input.speed());
            player.evaluate(now, molang, blueprint::boneIndex, poses);
            animating = player.hasTracks();
            blueprint.solve(poses, root, boneScratch, cubeMatrices);

            int count = cubeMatrices.length;
            int[] changed = new int[count];
            float[][] matrices = new float[count][];
            int n = 0;
            float[] scratch = new float[16];
            for (int i = 0; i < count; i++) {
                cubeMatrices[i].get(scratch);
                if (differs(scratch, lastSent[i])) {
                    System.arraycopy(scratch, 0, lastSent[i], 0, 16);
                    changed[n] = i;
                    matrices[n] = scratch.clone();
                    n++;
                }
            }
            if (n > 0) {
                Frame next = new Frame(java.util.Arrays.copyOf(changed, n), java.util.Arrays.copyOf(matrices, n));
                Frame previous = pending.getAndSet(next);
                if (previous != null) {
                    next = merge(previous, next);
                    pending.set(next);
                }
            }
        } finally {
            computing.set(false);
        }
    }

    /** Merges an unapplied older frame with a newer one so no cube update is ever lost. */
    private static Frame merge(Frame older, Frame newer) {
        java.util.Map<Integer, float[]> byCube = new java.util.LinkedHashMap<>();
        for (int i = 0; i < older.cubes().length; i++) {
            byCube.put(older.cubes()[i], older.matrices()[i]);
        }
        for (int i = 0; i < newer.cubes().length; i++) {
            byCube.put(newer.cubes()[i], newer.matrices()[i]);
        }
        int[] cubes = new int[byCube.size()];
        float[][] matrices = new float[byCube.size()][];
        int i = 0;
        for (java.util.Map.Entry<Integer, float[]> e : byCube.entrySet()) {
            cubes[i] = e.getKey();
            matrices[i++] = e.getValue();
        }
        return new Frame(cubes, matrices);
    }

    private static boolean differs(float[] a, float[] b) {
        for (int i = 0; i < 16; i++) {
            if (Math.abs(a[i] - b[i]) > MATRIX_EPSILON) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ events

    /** Queues a one-shot animation ({@code attack}, {@code hurt}); ignored when the model has no such animation. */
    public void requestOneShot(String state) {
        if (animations.containsKey(state)) {
            oneShots.add(state);
        }
    }

    /** The anchor died: plays the death animation (if any) and lets the instance finish soon after. */
    public void markDead(long tick) {
        if (!dead) {
            dead = true;
            deadSinceTick = tick;
            requestOneShot(ModelBinding.DEATH);
        }
    }

    public boolean isDead() {
        return dead;
    }

    /** A dead instance is finished once its animations ran out (or after a hard cap of three seconds). */
    public boolean isFinished(long tick) {
        return dead && deadSinceTick >= 0 && (tick - deadSinceTick > MAX_DEATH_TICKS || (!animating && tick - deadSinceTick > 2));
    }

    // ------------------------------------------------------------------ removal (entity thread)

    /** Removes every display and the hitbox. Safe to call more than once. */
    public void remove() {
        for (ItemDisplay display : displays) {
            if (display.isValid()) {
                display.remove();
            }
        }
        displays.clear();
        if (hitbox != null && hitbox.isValid()) {
            hitbox.remove();
        }
        hitbox = null;
    }
}
