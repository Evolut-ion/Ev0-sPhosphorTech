package com.Ev0sMods.PhosphorTech.blocks;

import com.Ev0sMods.PhosphorTech.compat.VectorCompat;
import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxNetwork;
import com.Ev0sMods.PhosphorTech.ui.PumpUIPage;
import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxReceiver;
import com.Ev0sMods.PhosphorTech.fluid.FluidCapable;
import com.Ev0sMods.PhosphorTech.fluid.FluidNetwork;
import com.Ev0sMods.PhosphorTech.fluid.FluidType;
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
 * ECS component for the Fluid Pump block.
 *
 * <p>Every {@value #PUMP_INTERVAL} ticks the pump scans the block directly
 * below it for a world fluid.  If fluid is recognised and the pump holds
 * at least {@value #CF_COST} CF, it draws {@value #PUMP_AMOUNT} mB into its
 * internal buffer (max {@value #PUMP_BUFFER} mB) and deducts the CF cost.
 *
 * <p>The buffer is pushed to adjacent {@link FluidCapable} blocks (pipes,
 * tanks, etc.) on every tick.  The pump is output-only: pipes and machines
 * <em>cannot</em> push fluid into it.
 */
@SuppressWarnings({"unchecked", "removal"})
public class PumpState
        implements Component<ChunkStore>, TickableBlockState, FluidCapable, CrystallineFluxReceiver {

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Maximum fluid buffer in mB. */
    public static final int  PUMP_BUFFER   = 5_000;
    /** Server ticks between pump events. */
    public static final int  PUMP_INTERVAL = 150;
    /** mB of fluid drawn per pump event. */
    public static final int  PUMP_AMOUNT   = 100;
    /** CF consumed per pump event. */
    public static final long CF_COST       = 5_000L;
    /** Maximum CF this pump can store. */
    public static final long CF_CAPACITY   = 50_000L;

    // ── Component registration ────────────────────────────────────────────────

    public static ComponentType<ChunkStore, PumpState> COMPONENT_TYPE;

    // ── Serialised fields ─────────────────────────────────────────────────────

    public long   cfStored       = 0L;
    public int    bufferMB       = 0;
    /** {@link FluidType} name of the buffered fluid, or {@code null} when empty. */
    public String bufferFluidKey = null;

    // ── Runtime-only state ────────────────────────────────────────────────────

    /** Set by the cleanup system the moment the block is removed -- tick() checks this first. */
    volatile boolean removed = false;

    private FluidType bufferFluid       = null;
    private int       pumpTimer         = 0;
    private int       uiTick            = 0;
    private boolean   uiDirty           = false;
    private Vector3i  cachedPosition    = new Vector3i(0, 0, 0);
    private boolean   positionResolved  = false;
    private boolean   registeredCF      = false;
    private boolean   registeredFluid   = false;
    private final BlockAnimator animator = new BlockAnimator();

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<PumpState> CODEC =
            BuilderCodec.builder(PumpState.class, PumpState::new)
                .append(new KeyedCodec<>("CfStored",      Codec.LONG,    true),
                        (s, v) -> s.cfStored = v, s -> s.cfStored).add()
                .append(new KeyedCodec<>("BufferMB",      Codec.INTEGER, true),
                        (s, v) -> s.bufferMB = v, s -> s.bufferMB).add()
                .append(new KeyedCodec<>("BufferFluidKey", Codec.STRING, true),
                        (s, v) -> { s.bufferFluidKey = v; s.bufferFluid = parseFluid(v); },
                        s -> s.bufferFluidKey).add()
                .build();

    private static FluidType parseFluid(String v) {
        if (v == null) return null;
        try { return FluidType.valueOf(v); } catch (Throwable ignored) { return null; }
    }

    // ── Constructors ──────────────────────────────────────────────────────────

    public PumpState() {}

    private PumpState(PumpState other) {
        this.cfStored       = other.cfStored;
        this.bufferMB       = other.bufferMB;
        this.bufferFluidKey = other.bufferFluidKey;
        this.bufferFluid    = other.bufferFluid;
    }

    // ── Component ─────────────────────────────────────────────────────────────

    @Override public PumpState  clone()       { return new PumpState(this); }
    @Override public WorldChunk getChunk()    { return null; }
    @Override public Vector3i   getPosition() { return cachedPosition; }

    @Override
    public void invalidate() {
        registeredCF    = false;
        registeredFluid = false;
    }

    // ── FluidCapable — output only ────────────────────────────────────────────

    /** The pump cannot be pushed into — it is a source only. */
    @Override public boolean canAcceptFluid(FluidType type)                       { return false; }
    @Override public boolean canAcceptFluidFrom(FluidType type, Vector3i fromPos) { return false; }
    @Override public int     acceptFluid(FluidType type, int amount, boolean sim) { return 0; }

    @Override
    public boolean canProvideFluid(FluidType type) {
        return bufferFluid == type && bufferMB > 0;
    }

    @Override
    public int extractFluid(FluidType type, int amount, boolean simulate) {
        if (bufferFluid != type || bufferMB <= 0) return 0;
        int actual = Math.min(amount, bufferMB);
        if (!simulate && actual > 0) {
            bufferMB -= actual;
            if (bufferMB == 0) { bufferFluid = null; bufferFluidKey = null; }
        }
        return actual;
    }

    // ── CrystallineFluxReceiver ───────────────────────────────────────────────

    @Override public long getCFStored()   { return cfStored; }
    @Override public long getCFCapacity() { return CF_CAPACITY; }

    @Override
    public long receiveCF(long amount, boolean simulate) {
        long space  = CF_CAPACITY - cfStored;
        long actual = Math.min(amount, space);
        if (!simulate && actual > 0) { cfStored += actual; uiDirty = true; }
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

        // ── 2. Register in CF and fluid networks ──────────────────────────────
        if (!registeredCF) {
            CrystallineFluxNetwork.register(cachedPosition, this);
            registeredCF = true;
        }
        if (!registeredFluid) {
            FluidNetwork.register(cachedPosition, this);
            registeredFluid = true;
        }

        // ── 3. Animation countdown ────────────────────────────────────────────
        animator.tick(world, cachedPosition);

        // ── 4. Pump cycle ─────────────────────────────────────────────────────
        pumpTimer++;
        if (pumpTimer >= PUMP_INTERVAL) {
            pumpTimer = 0;
            if (cfStored >= CF_COST) {
                int bx = cachedPosition.x;
                int by = cachedPosition.y - 1;
                int bz = cachedPosition.z;
                FluidType detected = detectFluidAt(world, bx, by, bz);
                if (detected != null) {
                    boolean compatible = (bufferFluid == null || bufferFluid == detected);
                    int space = PUMP_BUFFER - bufferMB;
                    if (compatible && space >= PUMP_AMOUNT) {
                        cfStored       -= CF_COST;
                        bufferMB       += PUMP_AMOUNT;
                        bufferFluid     = detected;
                        bufferFluidKey  = detected.name();
                    }
                }
            }
        }

        // ── 5. Push buffer to adjacent pipes / machines ───────────────────────
        if (bufferMB > 0 && bufferFluid != null) {
            FluidNetwork.pushToAdjacent(bufferFluid, cachedPosition, this, bufferMB);
        }

        // ── 6. UI refresh ────────────────────────────────────────────────────
        uiTick++;
        if (PumpUIPage.hasWatcher(cachedPosition) && (uiDirty || uiTick >= 20)) {
            uiTick  = 0;
            uiDirty = false;
            PumpUIPage.tickRefresh(this, store, cachedPosition);
        }

        // ── 7. Visual state ───────────────────────────────────────────────
        if (bufferMB > 0 || cfStored > 0) {
            animator.setState(world, cachedPosition, BlockAnimator.STATE_ACTIVE, 20);
        } else {
            animator.clear(world, cachedPosition);
        }
    }

    // ── Fluid detection helpers ───────────────────────────────────────────────

    /**
     * Returns the {@link FluidType} of the world fluid at {@code (x, y, z)},
     * or {@code null} if no recognised fluid is present.
     */
    private static FluidType detectFluidAt(World world, int x, int y, int z) {
        try {
            WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z));
            if (chunk == null) return null;
            int fluidId = getFluidIdReflect(chunk, x, y, z);
            return fluidIdToType(fluidId);
        } catch (Throwable ignored) {}
        return null;
    }

    /** Calls {@code WorldChunk.getFluidId(x, y, z)} via reflection (same approach as EngineCompat). */
    private static int getFluidIdReflect(WorldChunk chunk, int x, int y, int z) {
        try {
            for (java.lang.reflect.Method m : chunk.getClass().getMethods()) {
                String n = m.getName();
                if (n.equals("getFluidId") || n.equals("fluidIdAt")) {
                    Object r = m.invoke(chunk, x, y, z);
                    if (r instanceof Number) return ((Number) r).intValue();
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    /**
     * Maps Hytale world fluid IDs to {@link FluidType}.
     * ID assignments observed from HopperProcessor bucket logic:
     * 7 = Water, 6 = Lava, 3 = Tar, 5 = Green_Slime(SAP), 4 = Poison(CREOSOTE).
     */
    private static FluidType fluidIdToType(int id) {
        return switch (id) {
            case 7 -> FluidType.WATER;
            case 6 -> FluidType.LAVA;
            case 3 -> FluidType.TAR;
            case 5 -> FluidType.SAP;
            case 4 -> FluidType.CREOSOTE;
            default -> null;
        };
    }

    // ── Position resolution (same pattern as WaterTankState) ─────────────────

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
                synchronized (PumpState.class) {
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
