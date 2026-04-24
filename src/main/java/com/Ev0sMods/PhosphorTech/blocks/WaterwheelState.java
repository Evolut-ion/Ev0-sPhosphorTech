package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Map;

import javax.annotation.Nonnull;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.mechanical.GearNetwork;
import com.Ev0sMods.PhosphorTech.mechanical.JouleProvider;
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
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/**
 * ECS component for the Waterwheel block.
 *
 * <p>Scans the 4 horizontal adjacent blocks every {@value #SCAN_INTERVAL} ticks.
 * For each adjacent block that contains any fluid (fluidId > 0) the wheel gains
 * +1 speed, for a maximum of 4.  Each speed unit produces {@value #J_PER_SPEED}
 * Joules per tick, pushed into the gear network.
 */
@SuppressWarnings({"unchecked", "removal"})
public class WaterwheelState
        implements Component<ChunkStore>, TickableBlockState,
                   JouleProvider, MechanicalCapable, SpinningGear, ShaftConnectable {

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Joules produced per speed unit per tick. */
    public static final double J_PER_SPEED    = 2.0;
    public static final int    SCAN_INTERVAL  = 20;

    // ── Component registration ────────────────────────────────────────────────

    public static ComponentType<ChunkStore, WaterwheelState> COMPONENT_TYPE;

    // ── Serialised fields ─────────────────────────────────────────────────────

    /** Cached fluid-neighbour count (0–4). Persisted so it survives reload. */
    public int fluidNeighbours = 0;

    // ── Runtime-only state ────────────────────────────────────────────────────

    private int scanTimer = 0;

    /**
     * Block rotation (0=N, 1=E, 2=S, 3=W). Refreshed from chunk each tick.
     * rotation 0/2 (N/S-facing): shaft runs along Z, water checked on ±X.
     * rotation 1/3 (E/W-facing): shaft runs along X, water checked on ±Z.
     */
    private int cachedRotation = 0;

    /** Whether the block is currently rendered as the _Spin variant. */
    private boolean lastRenderedSpin = false;

        /** Set by the cleanup system the moment the block is removed -- tick() checks this first. */
    volatile boolean removed = false;

    private Vector3i cachedPosition      = new Vector3i(0, 0, 0);
    private boolean  positionResolved    = false;
    private boolean  registeredInNetwork = false;

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<WaterwheelState> CODEC =
            BuilderCodec.builder(WaterwheelState.class, WaterwheelState::new)
                .append(new KeyedCodec<>("FluidNeighbours", Codec.INTEGER, true),
                        (s, v) -> s.fluidNeighbours = v, s -> s.fluidNeighbours).add()
                .build();

    // ── Constructor ───────────────────────────────────────────────────────────

    public WaterwheelState() {}

    private WaterwheelState(WaterwheelState o) {
        this.fluidNeighbours = o.fluidNeighbours;
    }

    // ── Component ─────────────────────────────────────────────────────────────

    @Override public WaterwheelState clone() { return new WaterwheelState(this); }
    @Override public WorldChunk   getChunk()    { return null; }
    @Override public Vector3i     getPosition() { return cachedPosition; }
    @Override public void         invalidate()  { registeredInNetwork = false; }

    // ── ShaftConnectable ──────────────────────────────────────────────────────

    /**
     * Returns the shaft axis for gear-network routing.
     * rotation 0/2 (facing N/S): wheel plane = XY, shaft axis = Z.
     * rotation 1/3 (facing E/W): wheel plane = ZY, shaft axis = X.
     */
    @Override
    public ShaftAxis getShaftAxis() {
        return (cachedRotation == 1 || cachedRotation == 3) ? ShaftAxis.X : ShaftAxis.Z;
    }

    // ── MechanicalCapable ─────────────────────────────────────────────────────

    @Override public void onNeighborGearChanged() {}

    // ── SpinningGear ──────────────────────────────────────────────────────────

    @Override
    public void receiveSpinSignal(double speed) {
        // Waterwheel drives the network; it does not receive spin from others.
    }

    // ── JouleProvider ─────────────────────────────────────────────────────────

    /** Speed is proportional to fluid neighbours. */
    @Override public double getSpeed()          { return Math.max(1.0, fluidNeighbours); }
    @Override public double getJoulesStored()   { return J_PER_SPEED * fluidNeighbours; }
    @Override public double getJoulesCapacity() { return J_PER_SPEED * 2; } // two-sided max

    @Override
    public double extractJoules(double amount, boolean simulate) {
        // Waterwheel generates J continuously; callers may extract up to J per tick.
        return Math.min(amount, J_PER_SPEED * fluidNeighbours);
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

        if (!positionResolved) {
            probePosition();
            if (!positionResolved) resolvePositionFromStore(store, archetypeChunk.getReferenceTo(index));
            if (!positionResolved) return;
        }

        if (!registeredInNetwork) {
            GearNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
        }

        // Refresh rotation from chunk every tick (cheap; avoids stale values after player rotates block)
        WorldChunk rotChunk = world.getChunkIfInMemory(
                ChunkUtil.indexChunkFromBlock(cachedPosition.x, cachedPosition.z));
        if (rotChunk != null) {
            cachedRotation = rotChunk.getRotationIndex(cachedPosition.x, cachedPosition.y, cachedPosition.z);
        }

        // Periodic fluid scan
        scanTimer++;
        if (scanTimer >= SCAN_INTERVAL) {
            scanTimer = 0;
            fluidNeighbours = countFluidNeighbours(world);
        }

        // Generate and push Joules
        if (fluidNeighbours > 0) {
            double joulesThisTick = J_PER_SPEED * fluidNeighbours;
            GearNetwork.propagateFrom(cachedPosition, getSpeed());
            GearNetwork.pushFromProvider(cachedPosition, this, joulesThisTick);
        }

        // Block animation — use block state animations (Spin / Idle defined in Waterwheel.json)
        boolean spinning = fluidNeighbours > 0;
        if (spinning && !lastRenderedSpin) {
            BlockAnimator.applyBlockState(world, cachedPosition, "Spin", cachedRotation);
            lastRenderedSpin = true;
        } else if (!spinning && lastRenderedSpin) {
            BlockAnimator.applyBlockState(world, cachedPosition, "Idle", cachedRotation);
            lastRenderedSpin = false;
        }
    }

    // ── Fluid scanning ────────────────────────────────────────────────────────

    /**
     * Counts fluid blocks on the 2 sides perpendicular to the waterwheel's shaft axis.
     * The shaft axis is derived from the block's rotation:
     *   rotation 0/2 (N/S-facing) → shaft on Z → check ±X
     *   rotation 1/3 (E/W-facing) → shaft on X → check ±Z
     * Maximum return value is 2.
     */
    private int countFluidNeighbours(World world) {
        int count = 0;
        int x = cachedPosition.x, y = cachedPosition.y, z = cachedPosition.z;
        int[][] offsets = isShaftOnZ() ? new int[][]{{1,0,0},{-1,0,0}}
                                       : new int[][]{{0,0,1},{0,0,-1}};
        for (int[] d : offsets) {
            if (getFluidIdAt(world, x + d[0], y + d[1], z + d[2]) > 0) count++;
        }
        return count;
    }

    /** True when the shaft runs along Z (block faces N or S). */
    private boolean isShaftOnZ() {
        return cachedRotation == 0 || cachedRotation == 2;
    }

    /** Returns the fluid ID at (x,y,z) or 0 if no fluid / chunk not loaded. */
    private static int getFluidIdAt(World world, int x, int y, int z) {
        try {
            WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z));
            if (chunk == null) return 0;
            Store<ChunkStore> fluidStore = world.getChunkStore().getStore();
            ChunkColumn column = fluidStore.getComponent(chunk.getReference(), ChunkColumn.getComponentType());
            if (column == null) return 0;
            Ref<ChunkStore> sectionRef = column.getSection(ChunkUtil.chunkCoordinate(y));
            if (sectionRef == null) return 0;
            FluidSection fluidSection = fluidStore.getComponent(sectionRef, FluidSection.getComponentType());
            if (fluidSection == null) return 0;
            return fluidSection.getFluidId(x, y, z);
        } catch (Throwable t) {
            System.out.println("[PhosphorTech] Waterwheel fluid detect error: " + t);
            return 0;
        }
    }

    // ── Position resolution ───────────────────────────────────────────────────

    private void probePosition() {
        try {
            Class<?> sc = getClass().getSuperclass();
            if (sc == null) return;
            for (String name : new String[]{"getBlockPosition", "getPosition", "getPos", "position"}) {
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
                synchronized (WaterwheelState.class) {
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
