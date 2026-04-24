package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Map;

import javax.annotation.Nonnull;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.compat.VectorCompat;
import com.Ev0sMods.PhosphorTech.fluid.FluidCapable;
import com.Ev0sMods.PhosphorTech.fluid.FluidNetwork;
import com.Ev0sMods.PhosphorTech.fluid.FluidType;
import com.Ev0sMods.PhosphorTech.ui.SteamReservoirUIPage;
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
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/**
 * ECS component for the Steam Reservoir block.
 *
 * <p>Stores up to {@value #STEAM_CAPACITY} mB of steam. Receives steam from
 * adjacent pipes or the Crystal Generator and outputs it to adjacent pipes or
 * other fluid-capable machines.  Acts as both source and sink for
 * {@link FluidType#STEAM}.
 */
@SuppressWarnings({"unchecked", "removal"})
public class SteamReservoirState
        implements Component<ChunkStore>, TickableBlockState, FluidCapable {

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Maximum steam stored in milli-buckets. */
    public static final int STEAM_CAPACITY = 10_000;

    // ── Component registration ────────────────────────────────────────────────

    public static ComponentType<ChunkStore, SteamReservoirState> COMPONENT_TYPE;

    // ── Serialised fields ─────────────────────────────────────────────────────

    /** Stored steam in mB. Range: 0 – {@value #STEAM_CAPACITY}. */
    public int steamMB = 0;

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

    public static final BuilderCodec<SteamReservoirState> CODEC =
            BuilderCodec.builder(SteamReservoirState.class, SteamReservoirState::new)
                .append(new KeyedCodec<>("SteamMB", Codec.INTEGER, true),
                        (s, v) -> s.steamMB = v, s -> s.steamMB).add()
                .build();

    // ── Constructors ──────────────────────────────────────────────────────────

    public SteamReservoirState() {}

    private SteamReservoirState(SteamReservoirState other) {
        this.steamMB = other.steamMB;
    }

    // ── Component ─────────────────────────────────────────────────────────────

    @Override public SteamReservoirState clone()      { return new SteamReservoirState(this); }
    @Override public WorldChunk          getChunk()   { return null; }
    @Override public Vector3i            getPosition(){ return cachedPosition; }

    @Override
    public void invalidate() {
        // Network de-registration is handled by NetworkCleanupSystem.
        registeredInNetwork = false;
    }

    // ── FluidCapable ──────────────────────────────────────────────────────────

    @Override
    public boolean canAcceptFluid(FluidType type) {
        return type == FluidType.STEAM && steamMB < STEAM_CAPACITY;
    }

    /** Reject steam entering from below — the bottom face is output-only. */
    @Override
    public boolean canAcceptFluidFrom(FluidType type, Vector3i fromPos) {
        if (!canAcceptFluid(type)) return false;
        // fromPos.y < cachedPosition.y means the sender is directly below us
        return fromPos.y >= cachedPosition.y;
    }

    @Override
    public int acceptFluid(FluidType type, int amount, boolean simulate) {
        if (type != FluidType.STEAM) return 0;
        int space  = STEAM_CAPACITY - steamMB;
        int actual = Math.min(amount, space);
        if (!simulate && actual > 0) {
            steamMB += actual;
            uiDirty  = true;
        }
        return actual;
    }

    @Override
    public boolean canProvideFluid(FluidType type) {
        return type == FluidType.STEAM && steamMB > 0;
    }

    @Override
    public int extractFluid(FluidType type, int amount, boolean simulate) {
        if (type != FluidType.STEAM) return 0;
        int actual = Math.min(amount, steamMB);
        if (!simulate && actual > 0) {
            steamMB -= actual;
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

        // Push excess steam downward only (bottom face = output).
        if (steamMB > 0) {
            Vector3i below = VectorCompat.vec3i(
                    cachedPosition.x, cachedPosition.y - 1, cachedPosition.z);
            FluidCapable sink = FluidNetwork.getAt(below);
            if (sink != null && sink != this && sink.canAcceptFluidFrom(FluidType.STEAM, cachedPosition)) {
                int canGive = extractFluid(FluidType.STEAM, steamMB, true);
                if (canGive > 0) {
                    int accepted = sink.acceptFluid(FluidType.STEAM, canGive, false);
                    if (accepted > 0) extractFluid(FluidType.STEAM, accepted, false);
                }
            }
        }

        // ── Visual state update: animate fill levels ─────────────────────────
        if (steamMB > 0) {
            int fillLevel = Math.min(10, (int) Math.ceil((double) steamMB / STEAM_CAPACITY * 10));
            animator.setState(world, cachedPosition, "FillLevel" + fillLevel, 20);
        } else {
            animator.clear(world, cachedPosition);
        }

        uiTick++;
        boolean hasWatcher = SteamReservoirUIPage.hasWatcher(cachedPosition);
        if (hasWatcher && (uiDirty || uiTick >= 20)) {
            uiTick  = 0;
            uiDirty = false;
            SteamReservoirUIPage.tickRefresh(this, store, cachedPosition);
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    public float steamPct()    { return STEAM_CAPACITY > 0 ? (float) steamMB / STEAM_CAPACITY : 0f; }
    public String steamLabel() { return String.format("%,d / %,d mB", steamMB, STEAM_CAPACITY); }

    // ── Position resolution (copied pattern from CrystallineCapacitorState) ──

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
                synchronized (SteamReservoirState.class) {
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
