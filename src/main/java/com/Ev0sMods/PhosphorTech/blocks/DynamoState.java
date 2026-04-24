package com.Ev0sMods.PhosphorTech.blocks;

import javax.annotation.Nonnull;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxNetwork;
import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxProvider;
import com.Ev0sMods.PhosphorTech.mechanical.GearNetwork;
import com.Ev0sMods.PhosphorTech.mechanical.JouleReceiver;
import com.Ev0sMods.PhosphorTech.mechanical.MechanicalCapable;
import com.Ev0sMods.PhosphorTech.ui.DynamoUIPage;
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
 * ECS component for the Dynamo block.
 *
 * <p>Receives Joules from the gear network each tick and converts them into
 * Crystalline Flux at a rate of {@value #CF_PER_JOULE} CF per Joule.
 * The generated CF is stored in an internal buffer and pushed out to adjacent
 * CF receivers each tick.
 *
 * <p>Block-state variants:
 * <ul>
 *   <li>{@code "Off"}    – no power</li>
 *   <li>{@code "Active"} – converting joules to CF</li>
 * </ul>
 */
@SuppressWarnings({"unchecked", "removal"})
public class DynamoState
        implements Component<ChunkStore>, TickableBlockState,
                   JouleReceiver, MechanicalCapable, CrystallineFluxProvider {

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Joule input buffer capacity. */
    public static final double J_CAPACITY       = 50.0;
    /** Joules consumed per tick when converting. */
    public static final double J_PER_TICK       = 1.0;
    /** CF produced per Joule consumed. */
    public static final long   CF_PER_JOULE     = 50L;
    /** Maximum CF the internal output buffer can hold. */
    public static final long   CF_MAX_STORED    = 100_000L;
    /** Ticks of inactivity before the "Active" state clears. */
    public static final int    STALL_TICKS      = 10;

    // ── Component registration ────────────────────────────────────────────────

    public static ComponentType<ChunkStore, DynamoState> COMPONENT_TYPE;

    // ── Serialised fields ─────────────────────────────────────────────────────

    public double joulesStored = 0.0;
    public long   cfStored     = 0L;

    // ── Runtime-only state ────────────────────────────────────────────────────

    public  boolean isConverting = false;
    public  boolean uiDirty      = false;
    public  int     uiTick       = 0;
    public  int     stallTimer   = 0;

    volatile boolean removed = false;

    private Vector3i cachedPosition      = new Vector3i(0, 0, 0);
    private boolean  positionResolved    = false;
    private boolean  registeredInNetwork = false;

    private final BlockAnimator animator = new BlockAnimator();

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<DynamoState> CODEC =
            BuilderCodec.builder(DynamoState.class, DynamoState::new)
                .append(new KeyedCodec<>("JoulesStored", Codec.DOUBLE, true),
                        (s, v) -> s.joulesStored = v, s -> s.joulesStored).add()
                .append(new KeyedCodec<>("CFStored",     Codec.LONG,   true),
                        (s, v) -> s.cfStored     = v == null ? 0L : v, s -> s.cfStored).add()
                .build();

    // ── Constructors ──────────────────────────────────────────────────────────

    public DynamoState() {}
    private DynamoState(DynamoState o) {
        this.joulesStored = o.joulesStored;
        this.cfStored     = o.cfStored;
    }

    // ── Component ─────────────────────────────────────────────────────────────

    @Override public DynamoState clone()       { return new DynamoState(this); }
    @Override public WorldChunk  getChunk()    { return null; }
    @Override public Vector3i    getPosition() { return cachedPosition; }
    @Override public void        invalidate()  {
        CrystallineFluxNetwork.unregisterExact(cachedPosition, this);
        registeredInNetwork = false;
    }

    // ── JouleReceiver ─────────────────────────────────────────────────────────

    @Override public double getJoulesStored()   { return joulesStored; }
    @Override public double getJoulesCapacity() { return J_CAPACITY; }

    @Override
    public double receiveJoules(double amount, double speed, boolean simulate) {
        double space  = J_CAPACITY - joulesStored;
        double actual = Math.min(amount, space);
        if (!simulate && actual > 0) { joulesStored += actual; uiDirty = true; }
        return actual;
    }

    // ── MechanicalCapable ─────────────────────────────────────────────────────

    @Override public void onNeighborGearChanged() { /* visual refresh on next tick */ }

    // ── CrystallineFluxProvider ───────────────────────────────────────────────

    @Override public long getCFStored()   { return cfStored; }
    @Override public long getCFCapacity() { return CF_MAX_STORED; }

    @Override
    public long extractCF(long amount, boolean simulate) {
        long actual = Math.min(amount, cfStored);
        if (!simulate) cfStored -= actual;
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

        // ── 2. Register in networks ───────────────────────────────────────────
        if (!registeredInNetwork) {
            GearNetwork.register(cachedPosition, this);
            CrystallineFluxNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
        }

        // ── 3. Convert joules → CF ────────────────────────────────────────────
        if (joulesStored >= J_PER_TICK && cfStored < CF_MAX_STORED) {
            double jUsed  = Math.min(joulesStored, J_PER_TICK);
            joulesStored -= jUsed;
            long cfGained = (long) (jUsed * CF_PER_JOULE);
            cfStored      = Math.min(CF_MAX_STORED, cfStored + cfGained);
            isConverting  = true;
            stallTimer    = 0;
            uiDirty       = true;
        } else {
            stallTimer++;
            if (stallTimer >= STALL_TICKS) {
                isConverting = false;
            }
        }

        // ── 4. Push CF to adjacent receivers ──────────────────────────────────
        if (cfStored > 0) {
            CrystallineFluxNetwork.pushFromProvider(cachedPosition, this);
        }

        // ── 5. Animation ──────────────────────────────────────────────────────
        animator.tick(world, cachedPosition);
        if (isConverting) {
            animator.setState(world, cachedPosition, BlockAnimator.STATE_ACTIVE, BlockAnimator.HOLD_INDEFINITE);
        } else {
            animator.clear(world, cachedPosition);
        }

        // ── 6. UI refresh ─────────────────────────────────────────────────────
        uiTick++;
        if (DynamoUIPage.hasWatcher(cachedPosition) && (uiDirty || uiTick >= 20)) {
            uiTick  = 0;
            uiDirty = false;
            DynamoUIPage.tickRefresh(this, store, cachedPosition);
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
                java.util.Map<?, ?> refs = entityRefsViaReflection(bcc);
                if (refs == null) continue;
                for (java.util.Map.Entry<?, ?> e : refs.entrySet()) {
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
    private static java.util.Map<?, ?> entityRefsViaReflection(BlockComponentChunk bcc) {
        try {
            if (!entityRefsMethodResolved) {
                synchronized (DynamoState.class) {
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
            return r instanceof java.util.Map<?, ?> map ? map : null;
        } catch (Throwable ignored) { return null; }
    }
}
