package com.Ev0sMods.PhosphorTech.blocks;

import com.Ev0sMods.PhosphorTech.compat.VectorCompat;
import com.Ev0sMods.PhosphorTech.fluid.FluidCapable;
import com.Ev0sMods.PhosphorTech.fluid.FluidNetwork;
import com.Ev0sMods.PhosphorTech.fluid.FluidType;
import com.Ev0sMods.PhosphorTech.ui.WaterTankUIPage;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.util.ChunkUtil;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * ECS component for the Water Tank block.
 *
 * <p>Stores up to {@value #WATER_CAPACITY} mB of water. Receives water from
 * adjacent pipes and outputs it downward via the bottom face only.
 * Acts as both source and sink for {@link FluidType#WATER}.
 */
@SuppressWarnings({"unchecked", "removal"})
public class WaterTankState
        implements Component<ChunkStore>, TickableBlockState, FluidCapable {

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Maximum water stored in milli-buckets. */
    public static final int WATER_CAPACITY = 10_000;

    // ── Component registration ────────────────────────────────────────────────

    public static ComponentType<ChunkStore, WaterTankState> COMPONENT_TYPE;

    // ── Serialised fields ─────────────────────────────────────────────────────

    /** Stored water in mB. Range: 0 – {@value #WATER_CAPACITY}. */
    public int waterMB = 0;

    // ── Runtime-only state ────────────────────────────────────────────────────

    private int     uiTick          = 0;
    private boolean uiDirty         = false;
        /** Set by the cleanup system the moment the block is removed -- tick() checks this first. */
    volatile boolean removed = false;

    private Vector3i cachedPosition = new Vector3i(0, 0, 0);
    private boolean  positionResolved   = false;
    private boolean  registeredInNetwork = false;

    // ── Animation ────────────────────────────────────────────────────────────

    /** Per-state visual animator — drives "Active" / "Off" block states. */
    private final BlockAnimator animator = new BlockAnimator();

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<WaterTankState> CODEC =
            BuilderCodec.builder(WaterTankState.class, WaterTankState::new)
                .append(new KeyedCodec<>("WaterMB", Codec.INTEGER, true),
                        (s, v) -> s.waterMB = v, s -> s.waterMB).add()
                .build();

    // ── Constructors ──────────────────────────────────────────────────────────

    public WaterTankState() {}

    private WaterTankState(WaterTankState other) {
        this.waterMB = other.waterMB;
    }

    // ── Component ─────────────────────────────────────────────────────────────

    @Override public WaterTankState clone()      { return new WaterTankState(this); }
    @Override public WorldChunk     getChunk()   { return null; }
    @Override public Vector3i       getPosition(){ return cachedPosition; }

    @Override
    public void invalidate() {
        registeredInNetwork = false;
    }

    // ── FluidCapable ──────────────────────────────────────────────────────────

    @Override
    public boolean canAcceptFluid(FluidType type) {
        return type == FluidType.WATER && waterMB < WATER_CAPACITY;
    }

    /** Reject water entering from below — the bottom face is output-only. */
    @Override
    public boolean canAcceptFluidFrom(FluidType type, Vector3i fromPos) {
        if (!canAcceptFluid(type)) return false;
        // fromPos.y < cachedPosition.y means the sender is directly below us
        return fromPos.y >= cachedPosition.y;
    }

    @Override
    public int acceptFluid(FluidType type, int amount, boolean simulate) {
        if (type != FluidType.WATER) return 0;
        int space  = WATER_CAPACITY - waterMB;
        int actual = Math.min(amount, space);
        if (!simulate && actual > 0) {
            waterMB += actual;
            uiDirty  = true;
        }
        return actual;
    }

    @Override
    public boolean canProvideFluid(FluidType type) {
        return type == FluidType.WATER && waterMB > 0;
    }

    @Override
    public int extractFluid(FluidType type, int amount, boolean simulate) {
        if (type != FluidType.WATER) return 0;
        int actual = Math.min(amount, waterMB);
        if (!simulate && actual > 0) {
            waterMB -= actual;
            uiDirty  = true;
        }
        return actual;
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
            FluidNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
        }

        // ── Animation hold countdown ─────────────────────────────────────────
        animator.tick(world, cachedPosition);

        // Push water downward only (bottom face = output).
        if (waterMB > 0) {
            Vector3i below = VectorCompat.vec3i(
                    cachedPosition.x, cachedPosition.y - 1, cachedPosition.z);
            FluidCapable sink = FluidNetwork.getAt(below);
            if (sink != null && sink != this && sink.canAcceptFluidFrom(FluidType.WATER, cachedPosition)) {
                int canGive = extractFluid(FluidType.WATER, waterMB, true);
                if (canGive > 0) {
                    int accepted = sink.acceptFluid(FluidType.WATER, canGive, false);
                    if (accepted > 0) extractFluid(FluidType.WATER, accepted, false);
                }
            }
        }

        // ── Visual state update ──────────────────────────────────────────────
        if (waterMB > 0) {
            animator.setState(world, cachedPosition, BlockAnimator.STATE_ACTIVE, 20);
        } else {
            animator.clear(world, cachedPosition);
        }

        uiTick++;
        boolean hasWatcher = WaterTankUIPage.hasWatcher(cachedPosition);
        if (hasWatcher && (uiDirty || uiTick >= 20)) {
            uiTick  = 0;
            uiDirty = false;
            WaterTankUIPage.tickRefresh(this, store, cachedPosition);
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    public float waterPct()    { return WATER_CAPACITY > 0 ? (float) waterMB / WATER_CAPACITY : 0f; }
    public String waterLabel() { return String.format("%,d / %,d mB", waterMB, WATER_CAPACITY); }

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
                        cachedPosition   = v;
                        positionResolved = true;
                        return;
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
                    cachedPosition   = VectorCompat.vec3i(wx, wy, wz);
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
                synchronized (WaterTankState.class) {
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
