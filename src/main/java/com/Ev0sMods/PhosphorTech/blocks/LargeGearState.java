package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Map;

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
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;

/**
 * ECS component for the Large Gear (2×2) block.
 *
 * <p>Capacity: 4 J per tick at speed 1.  Propagates spin to connected nodes.
 *
 * <p>Animation: large gears rotate at half the angular frequency of small gears
 * to reflect the 2:1 gear ratio.  The JSON defines:
 * <ul>
 *   <li>{@code "Off"}      – idle</li>
 *   <li>{@code "Spin"}     – slow rotation (canonical phase)</li>
 *   <li>{@code "SpinAlt"}  – slow rotation (alternate phase / counter-rotate)</li>
 * </ul>
 */
// SuppressWarnings removed
public class LargeGearState
        implements Component<ChunkStore>, TickableBlockState,
                   SpinningGear, MechanicalCapable, GearConnectable {

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Joules this gear can pass per tick at speed 1. */
    public static final double J_PER_TICK = 4.0;

    // ── Component registration ────────────────────────────────────────────────

    public static ComponentType<ChunkStore, LargeGearState> COMPONENT_TYPE;

    // ── Serialised fields ─────────────────────────────────────────────────────

    public int spinTimer = 0;

    // ── Runtime-only state ─────────────────────────────────────────────────────

    /** Set by the cleanup system the moment the block is removed — tick() checks this first. */
    volatile boolean removed = false;

    private Vector3i cachedPosition     = new Vector3i(0, 0, 0);
    private boolean  positionResolved   = false;
    private boolean  registeredInNetwork = false;

    private double   currentSpeed       = 1.0;
    private int      spinDirection      = 1;

    /**
     * Rotation index read from chunk each tick (0-3 = NESW, 4 = Up, 5 = Down).
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
    boolean  lastRenderedSpin   = false;

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<LargeGearState> CODEC =
            BuilderCodec.builder(LargeGearState.class, LargeGearState::new)
                .append(new KeyedCodec<>("SpinTimer", Codec.INTEGER, true),
                        (s, v) -> s.spinTimer = v, s -> s.spinTimer).add()
                .build();

    // ── Constructor ───────────────────────────────────────────────────────────

    public LargeGearState() {}

    // ── Component ────────────────────────────────────────────────────────────

    @Override
    public WorldChunk     getChunk() { return null; }
    @Override
    public Vector3i       getPosition() { return cachedPosition; }
    @Override
    public void           invalidate()  { registeredInNetwork = false; }

    // ── MechanicalCapable ─────────────────────────────────────────────────────

    @Override
    public void onNeighborGearChanged() { /* neighborDirty removed */ }

    // ── GearConnectable ─────────────────────────────────────────────────────

    @Override
    public ShaftAxis getGearAxis() {
        if (cachedRotation == 4 || cachedRotation == 5) return ShaftAxis.Y;
        if (cachedRotation == 1 || cachedRotation == 3) return ShaftAxis.X;
        return ShaftAxis.Z; // rot 0 (N) or 2 (S)
    }

    // ── SpinningGear ──────────────────────────────────────────────────────────

    @Override
    public void receiveSpinSignal(double speed) {
        spinTimer    = 3;
        currentSpeed = speed;
        GearNetwork.syncAnimations(cachedPosition);
    }

    @Override
    public void receiveSpinSignal(double speed, int direction) {
        if (direction != spinDirection) {
            spinDirection = direction;
            lastRenderedSpin = false; // force block re-place with correct SpinBack/Spin state
        }
        receiveSpinSignal(speed);
        GearNetwork.syncAnimations(cachedPosition);
    }

    @Override
    public int getSpinDirection() { return spinDirection; }

    @Override
    public void resetAnimation() {
        // LargeGear uses Looping:true auto-animation; force a block re-place
        // so the engine restarts the loop from frame 0.
        lastRenderedSpin = false;
    }

    @Override
    public void stopSpin() {
        spinTimer = 0;
        GearNetwork.syncAnimations(cachedPosition);
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @Override
    public void tick(float dt, int index, ArchetypeChunk<ChunkStore> archetypeChunk, Store<ChunkStore> store, CommandBuffer<ChunkStore> commandBuffer) {

        if (removed) return;

        World world = store.getExternalData().getWorld();
        // if (world == null) return; // removed dead code

        if (!positionResolved) {
            probePosition();
            if (!positionResolved) resolvePositionFromStore(store, archetypeChunk.getReferenceTo(index));
            if (!positionResolved) return;
        }

        if (!registeredInNetwork) {
            GearNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
        }

        // Heartbeat tick for periodic sync
        GearNetwork.heartbeatTick();

        // Refresh rotation from chunk every tick
        com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk rotChunk =
                world.getChunkIfInMemory(
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

        if (spinTimer > 0) spinTimer--;

        // Animation — transition block state for visual spin.
        // Alternating gears get their rotation flipped 180° so the looping
        // CustomModelAnimation visually spins the opposite direction.
        boolean spinning = spinTimer > 0;
        if (spinning != lastRenderedSpin) {
            if (spinning) {
                int spinRot = detectSpinRotation();
                if (spinDirection < 0) {
                    BlockAnimator.applyBlockState(world, cachedPosition, "SpinBack", spinRot);
                } else {
                    BlockAnimator.applyBlockState(world, cachedPosition, "Spin", spinRot);
                }
            } else {
                BlockAnimator.applyBlockState(world, cachedPosition, "Idle", detectSpinRotation());
            }
            lastRenderedSpin = spinning;
        }
    }

    /**
     * Finds the rotation index that aligns this gear's face with its connected
     * shaft in the network.  Priority: Y-axis shaft → rot 4 (Up),
     * X-axis shaft → rot 1 (East), Z-axis shaft → rot 0 (North).
     * Falls back to placement rotation for standalone gears.
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
                } catch (Exception ignored) {}
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
            if (chunks.isEmpty()) return;
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
        } catch (Exception ignored) {}
    }

    // @SuppressWarnings("unchecked") removed
    private static Map<?, ?> entityRefsViaReflection(BlockComponentChunk bcc) {
        try {
            if (!entityRefsMethodResolved) {
                synchronized (LargeGearState.class) {
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
        } catch (Exception ignored) { return null; }
    }

    @Override
    public LargeGearState clone() {
        try {
            return (LargeGearState) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }
}
