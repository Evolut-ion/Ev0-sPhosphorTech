package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Map;

import javax.annotation.Nonnull;

import org.joml.Vector3d;
import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.mechanical.GearConnectable;
import com.Ev0sMods.PhosphorTech.mechanical.GearNetwork;
import com.Ev0sMods.PhosphorTech.mechanical.MechanicalCapable;
import com.Ev0sMods.PhosphorTech.mechanical.ShaftAxis;
import com.Ev0sMods.PhosphorTech.mechanical.ShaftConnectable;
import com.Ev0sMods.PhosphorTech.mechanical.SpinningGear;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

/**
 * ECS component for the Small Gear (1×1) block.
 *
 * <p>Capacity: 1 J per tick at speed 1.  Participates in the {@link GearNetwork}
 * as a pass-through node — it propagates spin from connected providers onward.
 *
 * <p>Animation: the block has two named states in its JSON definition:
 * <ul>
 *   <li>{@code "Off"}  – idle, no rotation</li>
 *   <li>{@code "Spin"} – looping rotation animation</li>
 * </ul>
 * Adjacent small gears of the same type counter-rotate (checkerboard phase
 * derived from position parity). Small and large gears interlock at a 2:1 gear
 * ratio (large gear animates at half speed via the block-state variant
 * {@code "SpinSlow"} on the large-gear JSON).
 */
@SuppressWarnings({"unchecked", "removal"})
public class SmallGearState
        implements Component<ChunkStore>, TickableBlockState,
                   SpinningGear, MechanicalCapable, GearConnectable {

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Joules this gear can pass per tick at speed 1. */
    public static final double J_PER_TICK = 1.0;

    // ── Component registration ────────────────────────────────────────────────

    public static ComponentType<ChunkStore, SmallGearState> COMPONENT_TYPE;

    // ── Serialised fields ─────────────────────────────────────────────────────

    /** Ticks remaining in the current spin burst.  0 = idle. */
    public int spinTimer = 0;

    // ── Runtime-only state ─────────────────────────────────────────────────────

    /** Set by the cleanup system the moment the block is removed — tick() checks this first. */
    volatile boolean removed = false;

    private Vector3i cachedPosition   = new Vector3i(0, 0, 0);
    private boolean  positionResolved = false;
    private boolean  registeredInNetwork = false;

    /** Last speed received this tick — used for animation variant. */
    private double   currentSpeed    = 1.0;

    /** Spin direction received from the network (+1 forward, -1 reverse). */
    private int      spinDirection   = 1;

    /**
     * Rotation index read from the chunk each tick (0-3 = NESW, 4 = Up, 5 = Down).
     * Used to derive the gear's spin axis.
     */
    private int cachedRotation = 4; // default to flat (Y axis)

    /**
     * The original placement rotation captured while the gear is in "Off" state.
     * Always used as the basis for the alternating 180° flip so that
     * stop→restart cycles don't oscillate the visual direction.
     */
    private int originalRotation = -1; // -1 = not yet captured

    public double getCurrentSpeed() { return currentSpeed; }
    /** Last rendered spin state to avoid redundant setBlock calls. */
    private boolean  lastRenderedSpin = false;
    /** Flagged by onNeighborGearChanged() for a connection refresh. */
    private volatile boolean neighborDirty = false;

    // ── Animation drive (phase-locked retrigger) ────────────────────────────

    /**
     * Period in ticks between animation retriggers, aligned to the global
     * {@link GearNetwork#getCurrentTick()} clock so all gears fire together.
     * Matches the blockyanim duration (60 ticks) so the loop stays in sync.
     */
    private static final int ANIM_PERIOD = 60;

    /** Cached block-entity ref used for direct animation calls. {@code null} until resolved. */
    private Ref<EntityStore> cachedEntityRef = null;
    /** Whether we have tried and failed to find an entity ref this placement. */
    private boolean entityRefSearched = false;
    /** Last global tick on which we fired playAnimation (to avoid double-firing). */
    private int lastAnimTick = -1;

    private static final String ANIM_FORWARD = "Items/Icons/ItemsGenerated/small_gear_spin.blockyanim";
    private static final String ANIM_REVERSE = "Items/Icons/ItemsGenerated/small_gear_spin_reverse.blockyanim";

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<SmallGearState> CODEC =
            BuilderCodec.builder(SmallGearState.class, SmallGearState::new)
                .append(new KeyedCodec<>("SpinTimer", Codec.INTEGER, true),
                        (s, v) -> s.spinTimer = v, s -> s.spinTimer).add()
                .build();

    // ── Constructor ───────────────────────────────────────────────────────────

    public SmallGearState() {}

    private SmallGearState(SmallGearState o) {
        this.spinTimer        = o.spinTimer;
        this.cachedPosition   = new Vector3i(o.cachedPosition.x, o.cachedPosition.y, o.cachedPosition.z);
        this.positionResolved = o.positionResolved;
    }

    // ── Component ────────────────────────────────────────────────────────────

    @Override public SmallGearState clone()    { return new SmallGearState(this); }
    @Override public WorldChunk     getChunk() { return null; }
    @Override public Vector3i       getPosition() { return cachedPosition; }
    @Override public void           invalidate()  { registeredInNetwork = false; }

    // ── GearConnectable ───────────────────────────────────────────────────────

    /**
     * Returns the spin axis of this gear derived from its placement rotation.
     * <ul>
     *   <li>rot 4 (placed on floor, Up face) or 5 (placed on ceiling, Down face) → Y axis (flat gear)</li>
     *   <li>rot 0 (N) or 2 (S) → Z axis (vertical gear, spine along Z)</li>
     *   <li>rot 1 (E) or 3 (W) → X axis (vertical gear, spine along X)</li>
     * </ul>
     */
    @Override
    public ShaftAxis getGearAxis() {
        if (cachedRotation == 4 || cachedRotation == 5) return ShaftAxis.Y;
        if (cachedRotation == 1 || cachedRotation == 3) return ShaftAxis.X;
        return ShaftAxis.Z; // rot 0 (N) or 2 (S)
    }

    // ── MechanicalCapable ─────────────────────────────────────────────────────

    @Override
    public void onNeighborGearChanged() { neighborDirty = true; }

    // ── SpinningGear ──────────────────────────────────────────────────────────

    @Override
    public void receiveSpinSignal(double speed) {
        spinTimer    = 3; // keep alive for 3 ticks after last propagation
        currentSpeed = speed;
        GearNetwork.syncAnimations(cachedPosition);
    }

    @Override
    public void receiveSpinSignal(double speed, int direction) {
        if (direction != spinDirection) {
            spinDirection = direction;
            lastRenderedSpin = false; // force block re-place with correct SpinBack/Spin state
            lastAnimTick = -1;
        }
        receiveSpinSignal(speed);
        GearNetwork.syncAnimations(cachedPosition);
    }

    @Override
    public int getSpinDirection() { return spinDirection; }

    @Override
    public void resetAnimation() {
        lastAnimTick      = -1;
        cachedEntityRef   = null;
        entityRefSearched = false;
    }

    @Override
    public void stopSpin() {
        spinTimer = 0;
        GearNetwork.syncAnimations(cachedPosition);
    }

    /**
     * Called when the block is removed. Clears all visual / animation state so
     * that no in-flight tick can call {@code playAnimation} on the dying entity,
     * which would trigger a divide-by-zero inside the engine's animation system.
     */
    public void clearVisualState() {
        spinTimer         = 0;
        cachedEntityRef   = null;
        entityRefSearched = false;
        // Reset rendered state so the final tick does not see a Spin→Off transition
        // and send an applyBlockState("Off") packet to an already-dying visual entity.
        lastRenderedSpin  = false;
        neighborDirty     = false;
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
                     @Nonnull Store<ChunkStore> store,
                     @Nonnull CommandBuffer<ChunkStore> commandBuffer) {

        if (removed) return;

        World world = store.getExternalData().getWorld();
        if (world == null) return;

        // ── 1. Resolve position ───────────────────────────────────────────────
        if (!positionResolved) {
            probePosition();
            if (!positionResolved) resolvePositionFromStore(store, archetypeChunk.getReferenceTo(index));
            if (!positionResolved) return;
        }

        // ── 2. Register in gear network ───────────────────────────────────────
        if (!registeredInNetwork) {
            GearNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
        }

        // Heartbeat tick for periodic sync
        GearNetwork.heartbeatTick();
        // ── 2b. Refresh rotation from chunk (cheap; catches post-placement changes) ──
        WorldChunk rotChunk = world.getChunkIfInMemory(
                com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(cachedPosition.x, cachedPosition.z));
        if (rotChunk != null) {
            cachedRotation = rotChunk.getRotationIndex(cachedPosition.x, cachedPosition.y, cachedPosition.z);
        }
        // Capture the true placement rotation while the gear is in "Off" state
        // (before any flip).  This value is used for every Off→Spin flip so
        // that stop/restart cycles always produce the same result.
        if (!lastRenderedSpin && originalRotation < 0) {
            originalRotation = cachedRotation;
        }
        // ── 3. Countdown spin timer ───────────────────────────────────────────
        if (spinTimer > 0) spinTimer--;

        // ── 5. Animation ──────────────────────────────────────────────────────
        // Guard every applyBlockState / playAnimation call: never send a block-state
        // packet for a block that has already been destroyed.  This is the root cause
        // of the C# engine divide-by-zero when breaking animating or spinning-down gears.
        WorldChunk animGuard = world.getChunkIfInMemory(
                com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(cachedPosition.x, cachedPosition.z));
        if (animGuard == null || animGuard.getBlockType(cachedPosition.x, cachedPosition.y, cachedPosition.z) == null) {
            cachedEntityRef   = null;
            entityRefSearched = false;
            lastRenderedSpin  = false;
            return;
        }

        boolean spinning = spinTimer > 0;
        neighborDirty = false; // clear regardless; never re-send the same visual state
        if (!spinning) {
            // Stop: replace with static SmallGear block (no State/Definitions = safe to break).
            if (lastRenderedSpin) {
            // Restore orientation on idle using the same axis-derived rotation.
                BlockAnimator.applyBlockState(world, cachedPosition, "Idle", detectSpinRotation());
                lastRenderedSpin  = false;
                lastAnimTick      = -1;
                cachedEntityRef   = null;
                entityRefSearched = false;
            }
        } else {
            // Start: replace with SmallGear_Spin block (no State/Definitions = safe to break).
            // Alternating gears get their rotation flipped 180° so the looping
            // CustomModelAnimation visually spins the opposite direction.
            if (!lastRenderedSpin) {
                int spinRot = detectSpinRotation();
                if (spinDirection < 0) {
                    BlockAnimator.applyBlockState(world, cachedPosition, "SpinBack", spinRot);
                } else {
                    BlockAnimator.applyBlockState(world, cachedPosition, "Spin", spinRot);
                }
                lastRenderedSpin  = true;
                lastAnimTick      = -1; // force immediate retrigger on next phase boundary
                entityRefSearched = false;
            }

            // Lazy-resolve the block entity Ref (needed for AnimationUtils).
            if (cachedEntityRef == null && !entityRefSearched) {
                entityRefSearched = true;
                try {
                    Store<EntityStore> entityStore = world.getEntityStore().getStore();
                    @SuppressWarnings("unchecked")
                    SpatialResource<Ref<EntityStore>, EntityStore> spatial =
                            (SpatialResource<Ref<EntityStore>, EntityStore>)
                            entityStore.getResource(EntityModule.get().getEntitySpatialResourceType());
                    ObjectArrayList<Ref<EntityStore>> found = new ObjectArrayList<>();
                    CompatSpatial.collectCylinder(spatial.getSpatialStructure(),
                            new Vector3d(cachedPosition.x + 0.5, cachedPosition.y + 0.5, cachedPosition.z + 0.5),
                            0.4, 0.4, found);
                    if (!found.isEmpty()) cachedEntityRef = found.get(0);
                } catch (Throwable ignored) {}
            }

            // Phase-locked retrigger: fire on global tick boundaries so every
            // gear in the network calls playAnimation on the same tick, keeping
            // all animations frame-aligned regardless of when they started.
            int globalTick = GearNetwork.getCurrentTick();
            boolean onBoundary = (globalTick % ANIM_PERIOD) == 0 || lastAnimTick < 0;
            if (onBoundary && cachedEntityRef != null && globalTick != lastAnimTick) {
                lastAnimTick = globalTick;
                // Use network-propagated direction to pick animation phase.
                String animName = (spinDirection < 0) ? ANIM_REVERSE : ANIM_FORWARD;

                try {
                    Store<EntityStore> entityStore = world.getEntityStore().getStore();
                    AnimationUtils.playAnimation(cachedEntityRef, AnimationSlot.Action,
                            animName, true, (ComponentAccessor<EntityStore>) entityStore);
                } catch (Throwable ignored) {
                    cachedEntityRef = null;
                    entityRefSearched = false;
                }
            }
        }
    }

    // ── Position resolution (same pattern as CrusherState) ───────────────────

    /**
     * Finds the rotation index that aligns this gear's face with its connected
     * shaft (or nearest gear) in the network.  Priority: Y-axis shaft → rot 4
     * (Up), X-axis shaft → rot 1 (East), Z-axis shaft → rot 0 (North).
     * Falls back to the current cached placement rotation.
     */
    private int detectSpinRotation() {
        if (!positionResolved) return cachedRotation >= 0 ? cachedRotation : 0;
        int x = cachedPosition.x, y = cachedPosition.y, z = cachedPosition.z;
        Object above = GearNetwork.getAt(new Vector3i(x, y + 1, z));
        Object below = GearNetwork.getAt(new Vector3i(x, y - 1, z));
        if ((above instanceof ShaftConnectable sc  && sc.getShaftAxis()  == ShaftAxis.Y) ||
            (below instanceof ShaftConnectable sc2 && sc2.getShaftAxis() == ShaftAxis.Y)) return 4;
        Object xp = GearNetwork.getAt(new Vector3i(x + 1, y, z));
        Object xm = GearNetwork.getAt(new Vector3i(x - 1, y, z));
        if ((xp instanceof ShaftConnectable sc  && sc.getShaftAxis()  == ShaftAxis.X) ||
            (xm instanceof ShaftConnectable sc2 && sc2.getShaftAxis() == ShaftAxis.X)) return 1;
        Object zp = GearNetwork.getAt(new Vector3i(x, y, z + 1));
        Object zm = GearNetwork.getAt(new Vector3i(x, y, z - 1));
        if ((zp instanceof ShaftConnectable sc  && sc.getShaftAxis()  == ShaftAxis.Z) ||
            (zm instanceof ShaftConnectable sc2 && sc2.getShaftAxis() == ShaftAxis.Z)) return 0;
        // No shaft found — normalize opposite-face pairs so a standalone gear
        // looks the same regardless of which face it was placed from.
        return normalizeRotation(cachedRotation >= 0 ? cachedRotation : 0);
    }

    /** Maps opposite-face placement rotations to a single canonical value. */
    private static int normalizeRotation(int r) {
        return switch (r) {
            case 2 -> 0; // South → North  (Z-axis)
            case 3 -> 1; // West  → East   (X-axis)
            case 5 -> 4; // Down  → Up     (Y-axis)
            default -> r;
        };
    }

    private void probePosition() {
        try {
            Class<?> sc = getClass().getSuperclass();
            if (sc == null) return;
            for (String name : new String[]{"getBlockPosition","getPosition","getPos","position"}) {
                try {
                    java.lang.reflect.Method m = sc.getMethod(name);
                    Object r = m.invoke(this);
                    if (r instanceof Vector3i v && !(v.x == 0 && v.y == 0 && v.z == 0)) {
                        cachedPosition = v; positionResolved = true; return;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static volatile java.lang.reflect.Method entityRefsMethod;
    private static volatile boolean entityRefsMethodResolved;

    private void resolvePositionFromStore(Store<ChunkStore> store, Ref<ChunkStore> myRef) {
        try {
            int myIdx = myRef.getIndex();
            ChunkStore cs = store.getExternalData();
            var chunks = cs.getChunkIndexes();
            if (chunks == null || chunks.isEmpty()) return;
            for (long chunkIdx : chunks) {
                Ref<ChunkStore> colRef = cs.getChunkReference(chunkIdx);
                if (colRef == null) continue;
                BlockComponentChunk bcc = store.getComponent(colRef, BlockComponentChunk.getComponentType());
                if (bcc == null) continue;
                Map<?, ?> refs = entityRefsViaReflection(bcc);
                if (refs == null) continue;
                for (Map.Entry<?, ?> e : refs.entrySet()) {
                    if (!(e.getKey() instanceof Integer blockIndex)) continue;
                    if (!(e.getValue() instanceof Ref<?> ref)) continue;
                    if (ref.getIndex() != myIdx) continue;
                    int lx = ChunkUtil.xFromBlockInColumn(blockIndex);
                    int wy = ChunkUtil.yFromBlockInColumn(blockIndex);
                    int lz = ChunkUtil.zFromBlockInColumn(blockIndex);
                    int wx = ChunkUtil.worldCoordFromLocalCoord(ChunkUtil.xOfChunkIndex(chunkIdx), lx);
                    int wz = ChunkUtil.worldCoordFromLocalCoord(ChunkUtil.zOfChunkIndex(chunkIdx), lz);
                    cachedPosition = new Vector3i(wx, wy, wz);
                    positionResolved = true;
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> entityRefsViaReflection(BlockComponentChunk bcc) {
        try {
            if (!entityRefsMethodResolved) {
                synchronized (SmallGearState.class) {
                    if (!entityRefsMethodResolved) {
                        for (java.lang.reflect.Method m : bcc.getClass().getMethods()) {
                            if ("getEntityReferences".equals(m.getName()) && m.getParameterCount() == 0) {
                                m.setAccessible(true);
                                entityRefsMethod = m;
                                break;
                            }
                        }
                        entityRefsMethodResolved = true;
                    }
                }
            }
            if (entityRefsMethod == null) return null;
            Object r = entityRefsMethod.invoke(bcc);
            return r instanceof Map<?, ?> map ? map : null;
        } catch (Throwable ignored) { return null; }
    }
}
