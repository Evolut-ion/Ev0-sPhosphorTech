package com.Ev0sMods.PhosphorTech.blocks;

import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxNetwork;
import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxProvider;
import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxReceiver;
import com.Ev0sMods.PhosphorTech.ui.CrystallineCapacitorUIPage;
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
 * ECS component for the Crystalline Capacitor block.
 *
 * <p><b>Energy model:</b>
 * <ul>
 *   <li>Stores up to {@value #CF_CAPACITY} Crystalline Flux (CF).</li>
 *   <li>Passively receives CF from any adjacent
 *       {@link CrystallineFluxProvider} that pushes to the network.</li>
 *   <li>Outputs at most {@value #OUTPUT_RATE} CF per tick to adjacent
 *       {@link CrystallineFluxReceiver} nodes (wires and machines). This is
 *       the "exportable connection" requirement — if no adjacent receiver is
 *       registered, nothing is exported.</li>
 * </ul>
 *
 * <p>The {@link #outputBudget} field tracks how much CF this instance may still
 * export during the current tick. It is reset to {@value #OUTPUT_RATE} at the
 * start of each tick so that {@link #extractCF} correctly caps the per-tick
 * rate even if the network calls {@code extractCF} multiple times.
 *
 * <p>TODO(JOML-migration): {@link Vector3i} field types become
 * {@code org.joml.Vector3i}; field-access (.x/.y/.z) is identical.
 */
@SuppressWarnings({"unchecked", "removal"})
public class CrystallineCapacitorState
        implements Component<ChunkStore>, TickableBlockState,
                   CrystallineFluxProvider, CrystallineFluxReceiver {

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Maximum stored CF. */
    public static final int CF_CAPACITY  = 24_000;
    /** Maximum CF exported to adjacent receivers per tick. */
    public static final int OUTPUT_RATE  = 1_024;

    // ── Component registration ────────────────────────────────────────────────

    /** Set during plugin setup via {@code csr.registerComponent(...)}. */
    public static ComponentType<ChunkStore, CrystallineCapacitorState> COMPONENT_TYPE;

    // ── Serialised fields ─────────────────────────────────────────────────────

    /** Stored CF. Range: 0 – {@value #CF_CAPACITY}. */
    public int cfStored = 0;

    // ── Runtime-only state ────────────────────────────────────────────────────

    /** Remaining export budget for the current tick. Reset each tick. */
    private int outputBudget = OUTPUT_RATE;
    /** UI refresh throttle. */
    private int uiTick = 0;
    /** Set true by receiveCF to force an immediate UI update. */
    private boolean uiDirty = false;

        /** Set by the cleanup system the moment the block is removed -- tick() checks this first. */
    volatile boolean removed = false;

    private Vector3i cachedPosition = new Vector3i(0, 0, 0);
    private boolean  positionResolved   = false;
    private boolean  registeredInNetwork = false;

    // ── Animation ────────────────────────────────────────────────────────────

    /** Per-state visual animator — drives "Active" / "Off" block states. */
    private final BlockAnimator animator = new BlockAnimator();

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<CrystallineCapacitorState> CODEC =
            BuilderCodec.builder(CrystallineCapacitorState.class, CrystallineCapacitorState::new)
                .append(new KeyedCodec<>("CFStored", Codec.INTEGER, true),
                        (s, v) -> s.cfStored = v, s -> s.cfStored).add()
                .build();

    // ── Constructor ───────────────────────────────────────────────────────────

    public CrystallineCapacitorState() {}

    private CrystallineCapacitorState(CrystallineCapacitorState other) {
        this.cfStored = other.cfStored;
    }

    // ── Component ────────────────────────────────────────────────────────────

    @Override public CrystallineCapacitorState clone()       { return new CrystallineCapacitorState(this); }
    @Override public WorldChunk                 getChunk()   { return null; }
    @Override public Vector3i                   getPosition(){ return cachedPosition; }

    @Override
    public void invalidate() {
        // Network de-registration is handled by NetworkCleanupSystem.
        registeredInNetwork = false;
    }

    // ── CrystallineFluxProvider ───────────────────────────────────────────────

    @Override public long getCFStored()   { return cfStored; }
    @Override public long getCFCapacity() { return CF_CAPACITY; }

    /**
     * Extract CF from the capacitor's buffer.
     *
     * <p>Respects the per-tick {@link #outputBudget}: once the budget is
     * exhausted for this tick, this method returns 0. The budget is restored
     * at the start of the next tick.
     */
    @Override
    public long extractCF(long amount, boolean simulate) {
        if (outputBudget <= 0) return 0L;
        long actual = Math.min(amount, Math.min(cfStored, outputBudget));
        if (!simulate) {
            cfStored       -= (int) actual;
            outputBudget   -= (int) actual;
            if (actual > 0) uiDirty = true;
        }
        return actual;
    }

    // ── CrystallineFluxReceiver ───────────────────────────────────────────────

    @Override
    public long receiveCF(long amount, boolean simulate) {
        long room   = CF_CAPACITY - cfStored;
        long actual = Math.min(amount, room);
        if (!simulate && actual > 0) {
            cfStored += (int) actual;
            uiDirty = true;
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

        // Reset per-tick export budget.
        outputBudget = OUTPUT_RATE;

        // ── 1. Resolve position ───────────────────────────────────────────────
        if (!positionResolved) {
            probePosition();
            if (!positionResolved) resolvePositionFromStore(store, archetypeChunk.getReferenceTo(index));
            if (!positionResolved) return;
        }

        // ── 2. Register in CF network ─────────────────────────────────────────
        if (!registeredInNetwork) {
            CrystallineFluxNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
        }

        // ── Animation hold countdown ─────────────────────────────────────────
        animator.tick(world, cachedPosition);

        // ── 3. Export CF to adjacent receivers (up to OUTPUT_RATE per tick) ───
        if (cfStored > 0) {
            CrystallineFluxNetwork.pushFromProvider(cachedPosition, this);
        }

        // ── 4. Visual state update ───────────────────────────────────────────
        if (cfStored > 0) {
            animator.setState(world, cachedPosition, BlockAnimator.STATE_ACTIVE, 20);
        } else {
            animator.clear(world, cachedPosition);
        }

        // ── 5. UI refresh ─────────────────────────────────────────────────────
        uiTick++;
        boolean hasWatcher = CrystallineCapacitorUIPage.hasWatcher(cachedPosition);
        if (hasWatcher && (uiDirty || uiTick >= 20)) {
            uiTick   = 0;
            uiDirty  = false;
            CrystallineCapacitorUIPage.tickRefresh(this, store, cachedPosition);
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    /** CF fill percentage [0.0, 1.0]. */
    public float cfPct() {
        return CF_CAPACITY > 0 ? (float) cfStored / CF_CAPACITY : 0f;
    }

    /** Human-readable stored CF label, e.g. "12,288 / 24,000 CF". */
    public String cfLabel() {
        return String.format("%,d / %,d CF", cfStored, CF_CAPACITY);
    }

    // ── Position resolution (mirrors CrystalGeneratorState) ──────────────────

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
    private static volatile boolean                   entityRefsMethodResolved;

    private void resolvePositionFromStore(Store<ChunkStore> store, Ref<ChunkStore> myRef) {
        try {
            int myIdx  = myRef.getIndex();
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
                    cachedPosition   = new Vector3i(wx, wy, wz);
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
                synchronized (CrystallineCapacitorState.class) {
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
