package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Map;

import javax.annotation.Nonnull;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.compat.VectorCompat;
import com.Ev0sMods.PhosphorTech.fluid.FluidCapable;
import com.Ev0sMods.PhosphorTech.fluid.FluidNetwork;
import com.Ev0sMods.PhosphorTech.fluid.FluidType;
import com.Ev0sMods.PhosphorTech.ui.GenericFluidTankUIPage;
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
 * ECS component for the Generic Fluid Tank block.
 *
 * <p>Stores up to {@value #CAPACITY} mB of <em>any single fluid type</em>.
 * Input is accepted from the top face only; output is pushed downward from the
 * bottom face only.  FIFO priority: once a fluid occupies the tank, a different
 * fluid type is rejected until the tank is fully drained.
 */
@SuppressWarnings({"unchecked", "removal"})
public class GenericFluidTankState
        implements Component<ChunkStore>, TickableBlockState, FluidCapable {

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Maximum fluid stored in milli-buckets. */
    public static final int CAPACITY = 16_000;

    // ── Component registration ────────────────────────────────────────────────

    public static ComponentType<ChunkStore, GenericFluidTankState> COMPONENT_TYPE;

    // ── Serialised fields ─────────────────────────────────────────────────────

    /** Current fluid type stored (null = empty). Serialised as ordinal name. */
    public String fluidTypeName = "";

    /** Stored fluid in mB. Range: 0 – {@value #CAPACITY}. */
    public int fluidMB = 0;

    // ── Runtime-only state ────────────────────────────────────────────────────

    private int     uiTick           = 0;
    private boolean uiDirty          = false;
    volatile boolean removed         = false;

    private Vector3i cachedPosition  = new Vector3i(0, 0, 0);
    private boolean  positionResolved   = false;
    private boolean  registeredInNetwork = false;

    private final BlockAnimator animator = new BlockAnimator();

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<GenericFluidTankState> CODEC =
            BuilderCodec.builder(GenericFluidTankState.class, GenericFluidTankState::new)
                .append(new KeyedCodec<>("FluidType", Codec.STRING, true),
                        (s, v) -> s.fluidTypeName = v == null ? "" : v, s -> s.fluidTypeName).add()
                .append(new KeyedCodec<>("FluidMB", Codec.INTEGER, true),
                        (s, v) -> s.fluidMB = v, s -> s.fluidMB).add()
                .build();

    // ── Constructors ──────────────────────────────────────────────────────────

    public GenericFluidTankState() {}

    private GenericFluidTankState(GenericFluidTankState o) {
        this.fluidTypeName = o.fluidTypeName;
        this.fluidMB       = o.fluidMB;
    }

    // ── Component ─────────────────────────────────────────────────────────────

    @Override public GenericFluidTankState clone()      { return new GenericFluidTankState(this); }
    @Override public WorldChunk            getChunk()   { return null; }
    @Override public Vector3i              getPosition(){ return cachedPosition; }

    @Override
    public void invalidate() {
        registeredInNetwork = false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns the stored fluid type, or {@code null} when empty. */
    public FluidType storedType() {
        if (fluidTypeName == null || fluidTypeName.isEmpty() || fluidMB <= 0) return null;
        try { return FluidType.valueOf(fluidTypeName); } catch (IllegalArgumentException e) { return null; }
    }

    // ── FluidCapable ──────────────────────────────────────────────────────────

    @Override
    public boolean canAcceptFluid(FluidType type) {
        if (type == null) return false;
        if (fluidMB >= CAPACITY) return false;
        // FIFO: reject a different fluid type until tank is empty
        FluidType existing = storedType();
        return existing == null || existing == type;
    }

    /**
     * Only accept fluid entering from above (top-face input).
     * Bottom face (y below) is output-only.
     */
    @Override
    public boolean canAcceptFluidFrom(FluidType type, Vector3i fromPos) {
        if (!canAcceptFluid(type)) return false;
        // fromPos must be strictly above us (y > cachedPosition.y)
        return fromPos.y > cachedPosition.y;
    }

    @Override
    public int acceptFluid(FluidType type, int amount, boolean simulate) {
        if (!canAcceptFluid(type)) return 0;
        int space  = CAPACITY - fluidMB;
        int actual = Math.min(amount, space);
        if (!simulate && actual > 0) {
            fluidMB       += actual;
            fluidTypeName  = type.name();
            uiDirty        = true;
        }
        return actual;
    }

    @Override
    public boolean canProvideFluid(FluidType type) {
        return type != null && type == storedType() && fluidMB > 0;
    }

    @Override
    public int extractFluid(FluidType type, int amount, boolean simulate) {
        if (!canProvideFluid(type)) return 0;
        int actual = Math.min(amount, fluidMB);
        if (!simulate && actual > 0) {
            fluidMB -= actual;
            if (fluidMB <= 0) {
                fluidMB       = 0;
                fluidTypeName = "";
            }
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

        if (!positionResolved) {
            probePosition();
            if (!positionResolved) resolvePositionFromStore(store, archetypeChunk.getReferenceTo(index));
            if (!positionResolved) return;
        }

        if (!registeredInNetwork) {
            FluidNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
        }

        animator.tick(world, cachedPosition);

        // Push stored fluid downward (bottom-face output only)
        FluidType current = storedType();
        if (current != null && fluidMB > 0) {
            Vector3i below = VectorCompat.vec3i(
                    cachedPosition.x, cachedPosition.y - 1, cachedPosition.z);
            FluidCapable sink = FluidNetwork.getAt(below);
            if (sink != null && sink != this && sink.canAcceptFluidFrom(current, cachedPosition)) {
                int canGive = extractFluid(current, fluidMB, true);
                if (canGive > 0) {
                    int accepted = sink.acceptFluid(current, canGive, false);
                    if (accepted > 0) extractFluid(current, accepted, false);
                }
            }
        }

        // Visual state based on fluid level
        if (fluidMB > 0) {
            int fillLevel = Math.min(10, (int) Math.ceil((double) fluidMB / CAPACITY * 10));
            animator.setState(world, cachedPosition, "FillLevel" + fillLevel, 20);
        } else {
            animator.clear(world, cachedPosition);
        }

        uiTick++;
        boolean hasWatcher = GenericFluidTankUIPage.hasWatcher(cachedPosition);
        if (hasWatcher && (uiDirty || uiTick >= 20)) {
            uiTick  = 0;
            uiDirty = false;
            GenericFluidTankUIPage.tickRefresh(this, store, cachedPosition);
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    public float fluidPct()    { return CAPACITY > 0 ? (float) fluidMB / CAPACITY : 0f; }
    public String fluidLabel() { return String.format("%,d / %,d mB", fluidMB, CAPACITY); }

    public String fluidDisplayName() {
        FluidType t = storedType();
        return t != null ? t.getDisplayName() : "Empty";
    }

    public String fluidColor() {
        FluidType t = storedType();
        return t != null ? t.getHexColor() : "#546e7a";
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
                synchronized (GenericFluidTankState.class) {
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
