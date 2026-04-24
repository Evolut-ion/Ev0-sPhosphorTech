package com.Ev0sMods.PhosphorTech.blocks;

import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxNetwork;
import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxConnectable;
import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxProvider;
import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxReceiver;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
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
 * ECS component for CF-conducting wire blocks.
 *
 * <p>Wire tiers and their per-tick transfer rates:
 * <table>
 *   <tr><th>Tier</th><th>Material</th><th>CF / tick</th></tr>
 *   <tr><td>1</td><td>Copper</td><td>128</td></tr>
 *   <tr><td>2</td><td>Iron</td><td>256</td></tr>
 *   <tr><td>3</td><td>Thorium</td><td>384</td></tr>
 *   <tr><td>4</td><td>Cobalt</td><td>512</td></tr>
 *   <tr><td>5</td><td>Adamantite</td><td>640</td></tr>
 * </table>
 *
 * <p>Each tier registers exactly one {@code WireState} component type in the
 * plugin, configured via the {@code MaxTransfer} JSON field in the block's
 * {@code BlockEntity.Components.WireState} object.
 *
 * <p>TODO(JOML-migration): {@link Vector3i} field types become
 * {@code org.joml.Vector3i}; field-access syntax is identical.
 */
@SuppressWarnings("unchecked")
public class WireState
        implements Component<ChunkStore>, TickableBlockState,
                   CrystallineFluxProvider, CrystallineFluxReceiver,
                   CrystallineFluxConnectable {

    // ── Tier constants (CF per tick) ──────────────────────────────────────────

    public static final int COPPER_TRANSFER      = 128;
    public static final int IRON_TRANSFER        = 256;
    public static final int THORIUM_TRANSFER     = 384;
    public static final int COBALT_TRANSFER      = 512;
    public static final int ADAMANTITE_TRANSFER  = 640;

    /** Small internal buffer — capped at one full tick of the highest-tier wire. */
    private static final int WIRE_BUFFER_CAP = ADAMANTITE_TRANSFER * 2;

    // ── Component registration ────────────────────────────────────────────────

    /** Set during plugin setup. Single component type shared by all wire tiers. */
    public static ComponentType<ChunkStore, WireState> COMPONENT_TYPE;

    // ── Serialised fields ─────────────────────────────────────────────────────

    /** CF / tick transfer rate. Set from JSON ({@code MaxTransfer}). */
    public int maxTransfer = COPPER_TRANSFER;
    /** In-transit CF buffer. */
    public int cfBuffer = 0;

    // ── Runtime state ─────────────────────────────────────────────────────────

        /** Set by the cleanup system the moment the block is removed -- tick() checks this first. */
    volatile boolean removed = false;

    private Vector3i cachedPosition = new Vector3i(0, 0, 0);
    private boolean positionResolved = false;
    private boolean registeredInNetwork = false;

    // ── Animation ────────────────────────────────────────────────────────────

    /** Per-state visual animator — drives "Active" / "Off" block states. */
    private final BlockAnimator animator = new BlockAnimator();
    /** 6-bit connection mask from the last {@link #syncConnectionModel} call; {@code -1} = unset. */
    private int connectionMask = -1;
    /** Set by {@link #onNeighborCFChanged()} to force an immediate connection-model sync. */
    private volatile boolean connectionDirty = false;

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<WireState> CODEC =
            BuilderCodec.builder(WireState.class, WireState::new)
                .append(new KeyedCodec<>("MaxTransfer", Codec.INTEGER, true),
                        (s, v) -> s.maxTransfer = v, s -> s.maxTransfer).add()
                .append(new KeyedCodec<>("CFBuffer",    Codec.INTEGER, true),
                        (s, v) -> s.cfBuffer    = v, s -> s.cfBuffer).add()
                .build();

    // ── Constructor ───────────────────────────────────────────────────────────

    public WireState() {}

    private WireState(WireState other) {
        this.maxTransfer      = other.maxTransfer;
        this.cfBuffer         = other.cfBuffer;
        // Copy resolved position so NetworkCleanupSystem can unregister by
        // the correct position if the clone is immediately removed.
        // registeredInNetwork stays false so the clone re-registers on its
        // first tick.
        this.cachedPosition   = new Vector3i(other.cachedPosition.x, other.cachedPosition.y, other.cachedPosition.z);
        this.positionResolved = other.positionResolved;
    }

    // ── Component ────────────────────────────────────────────────────────────

    @Override public WireState   clone()      { return new WireState(this); }
    @Override public WorldChunk  getChunk()   { return null; }
    @Override public Vector3i    getPosition(){ return cachedPosition; }
    @Override public void        invalidate() {
        // Network de-registration is handled by NetworkCleanupSystem
        // (HolderSystem.onEntityRemoved) which is called by the ECS on removal.
        // This method is retained to satisfy the TickableBlockState interface.
        registeredInNetwork = false;
    }

    // ── CrystallineFluxConnectable ────────────────────────────────────────────

    @Override
    public void onNeighborCFChanged() {
        // Force connection-model refresh on the next tick (or current tick if
        // this is called mid-tick by the notifyNeighbors pass).
        connectionDirty = true;
    }

    // ── CrystallineFluxProvider ───────────────────────────────────────────────

    @Override public long getCFStored()   { return cfBuffer; }
    @Override public long getCFCapacity() { return WIRE_BUFFER_CAP; }

    @Override
    public long extractCF(long amount, boolean simulate) {
        long actual = Math.min(amount, cfBuffer);
        if (!simulate) cfBuffer -= (int) actual;
        return actual;
    }

    // ── CrystallineFluxReceiver ───────────────────────────────────────────────

    @Override
    public long receiveCF(long amount, boolean simulate) {
        long room   = WIRE_BUFFER_CAP - cfBuffer;
        long actual = Math.min(amount, room);
        if (!simulate) cfBuffer += (int) actual;
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
        // ── 1. Resolve position ───────────────────────────────────────────────
        if (!positionResolved) {
            probePosition();
            if (!positionResolved) resolvePositionFromStore(store, archetypeChunk.getReferenceTo(index));
            if (!positionResolved) return;
        }

        // ── 2. Register in CF network once position is known ──────────────────
        if (!registeredInNetwork) {
            CrystallineFluxNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
        }

        // ── 3. Transfer CF through the network ───────────────────────────────
        CrystallineFluxNetwork.wireTransfer(cachedPosition, maxTransfer);

        // ── 4. Connection model (skips setBlock when unchanged) ──────────────
        if (connectionDirty) { connectionMask = -1; connectionDirty = false; }
        connectionMask = syncConnectionModel(world);

        // ── 5. Animation ───────────────────────────────────────────────────────────
        animator.tick(world, cachedPosition);
        if (cfBuffer > 0) {
            animator.setState(world, cachedPosition, BlockAnimator.STATE_ACTIVE, BlockAnimator.HOLD_INDEFINITE);
        } else {
            animator.clear(world, cachedPosition);
        }    }

    // ── Position resolution (identical pattern to CrystalGeneratorState) ─────
    /**
     * Scans all 6 neighbours and selects the correct connection-count model.
     *
     * <p>A neighbour is connected if it is a registered CF node (wire, provider,
     * receiver) in the {@link CrystallineFluxNetwork}.
     *
     * @return The new 6-bit connection mask (use to update {@code connectionMask}).
     */
    private int syncConnectionModel(World world) {
        boolean[] conn = new boolean[6];
        for (int i = 0; i < 6; i++) {
            int[] o = BlockAnimator.FACE_OFFSETS[i];
            Vector3i adj = new Vector3i(
                    cachedPosition.x + o[0],
                    cachedPosition.y + o[1],
                    cachedPosition.z + o[2]);
            Object neighbour = CrystallineFluxNetwork.getAt(adj);
            if (neighbour != null && neighbour != this) {
                conn[i] = true;
            } else if (neighbour == null) {
                // Neighbor chunk may be loaded but not yet ticked (and therefore
                // not registered in the CF network). Fall back to a block-type
                // check so visuals are correct across chunk/simulation boundaries.
                try {
                    WorldChunk adjChunk = world.getChunkIfInMemory(
                            ChunkUtil.indexChunkFromBlock(adj.x, adj.z));
                    com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType bt =
                            adjChunk != null ? adjChunk.getBlockType(adj.x, adj.y, adj.z) : null;
                    conn[i] = bt != null && CrystallineFluxNetwork.isConnectableType(bt.getId());
                } catch (Throwable ignored) {
                    conn[i] = false;
                }
            }
        }
        return BlockAnimator.syncConnectionModel(world, cachedPosition, conn, connectionMask);
    }
    private void probePosition() {
        try {
            Class<?> sc = getClass().getSuperclass();
            if (sc == null) return;
            for (String name : new String[]{"getBlockPosition", "getPosition", "getPos"}) {
                try {
                    java.lang.reflect.Method m = sc.getMethod(name);
                    Object r = m.invoke(this);
                    if (r instanceof Vector3i v && !(v.x == 0 && v.y == 0 && v.z == 0)) {
                        cachedPosition = v;
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
                synchronized (WireState.class) {
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
