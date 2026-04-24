package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Map;

import javax.annotation.Nonnull;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.compat.VectorCompat;
import com.Ev0sMods.PhosphorTech.fluid.FluidCapable;
import com.Ev0sMods.PhosphorTech.fluid.FluidNetwork;
import com.Ev0sMods.PhosphorTech.fluid.FluidType;
import com.Ev0sMods.PhosphorTech.fluid.PipeType;
import com.Ev0sMods.PhosphorTech.heat.HeatCapable;
import com.Ev0sMods.PhosphorTech.heat.HeatNetwork;
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
 * ECS component for all fluid pipe blocks (Wooden, Clay, Copper, Potin).
 *
 * <p>Configured by the JSON {@code "PipeType"} field (e.g. {@code "CLAY"}).
 * On every transfer interval the pipe pulls fluid from an adjacent source into
 * its internal buffer, then pushes the buffer contents to an adjacent sink.
 *
 * <p>Pipes auto-connect to any adjacent {@link FluidCapable} block that handles
 * the same fluid type — concept mirrors {@code WireState} auto-connection.
 */
@SuppressWarnings({"unchecked", "removal"})
public class FluidPipeState
        implements Component<ChunkStore>, TickableBlockState, FluidCapable, HeatCapable {

    // ── Component registration ────────────────────────────────────────────────

    public static ComponentType<ChunkStore, FluidPipeState> COMPONENT_TYPE;

    // ── Serialised fields ─────────────────────────────────────────────────────

    /** Pipe material — determines transfer rate and allowed fluid types. */
    public PipeType  pipeType     = PipeType.CLAY;
    /** Current fluid in the buffer, {@code null} when empty. */
    public FluidType bufferFluid  = null;
    /** Amount of fluid in the buffer, in mB. */
    public int       bufferAmount = 0;
    /** Current temperature of the fluid in this pipe segment, in °C. */
    public double    heatCelsius  = HeatCapable.AMBIENT_CELSIUS;

    // ── Runtime-only state ────────────────────────────────────────────────────

    private int      transferTimer       = 0;
        /** Set by the cleanup system the moment the block is removed -- tick() checks this first. */
    volatile boolean removed = false;

    private Vector3i cachedPosition      = new Vector3i(0, 0, 0);
    private boolean  positionResolved    = false;
    private boolean  registeredInNetwork = false;

    // ── Animation ────────────────────────────────────────────────────────────

    /** Drives fluid-texture block states (e.g. {@code "Fluid_WATER"}, {@code "Fluid_STEAM"}). */
    private final BlockAnimator animator = new BlockAnimator();
    /** Tracks which fluid variant is currently applied to the model; {@code null} = Off. */
    private FluidType appliedFluidVariant = null;
    /** 6-bit connection mask from the last {@link #syncConnectionModel} call; {@code -1} = unset. */
    private int connectionMask = -1;
    /**
     * The non-pipe neighbour this pipe last delivered fluid to (its downstream machine).
     * Used to block backflow: if a machine tries to push a different fluid type back into
     * this pipe from the downstream side, we refuse.
     */
    private Vector3i downstreamPos = null;

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<FluidPipeState> CODEC =
            BuilderCodec.builder(FluidPipeState.class, FluidPipeState::new)
                .append(new KeyedCodec<>("PipeType",     Codec.STRING,  true),
                        (s, v) -> s.pipeType    = parsePipeType(v),
                        s -> s.pipeType.name()).add()
                .append(new KeyedCodec<>("BufferFluid",  Codec.STRING,  true),
                        (s, v) -> s.bufferFluid = parseFluidType(v),
                        s -> s.bufferFluid != null ? s.bufferFluid.name() : null).add()
                .append(new KeyedCodec<>("BufferAmount", Codec.INTEGER, true),
                        (s, v) -> s.bufferAmount = v,
                        s -> s.bufferAmount).add()
                .append(new KeyedCodec<>("HeatCelsius", Codec.DOUBLE, true),
                        (s, v) -> s.heatCelsius = v,
                        s -> s.heatCelsius).add()
                .build();

    private static PipeType  parsePipeType (String v) { try { return PipeType .valueOf(v); } catch (Throwable ignored) { return PipeType.CLAY; } }
    private static FluidType parseFluidType(String v) { try { return FluidType.valueOf(v); } catch (Throwable ignored) { return null; } }

    // ── Constructors ──────────────────────────────────────────────────────────

    public FluidPipeState() {}

    private FluidPipeState(FluidPipeState other) {
        this.pipeType     = other.pipeType;
        this.bufferFluid  = other.bufferFluid;
        this.bufferAmount = other.bufferAmount;
        this.heatCelsius  = other.heatCelsius;
    }

    // ── Component ─────────────────────────────────────────────────────────────

    @Override public FluidPipeState clone()      { return new FluidPipeState(this); }
    @Override public WorldChunk     getChunk()   { return null; }
    @Override public Vector3i       getPosition(){ return cachedPosition; }

    @Override
    public void invalidate() {
        // Network de-registration is handled by NetworkCleanupSystem.
        registeredInNetwork = false;
    }

    // ── HeatCapable ───────────────────────────────────────────────────────────

    @Override public double getHeat()    { return heatCelsius; }
    @Override public double getMaxHeat() { return pipeType.getMaxHeatCelsius(); }
    @Override public void   setHeat(double c) {
        double cap = (bufferFluid == FluidType.WATER) ? 100.0 : getMaxHeat();
        heatCelsius = Math.max(HeatCapable.AMBIENT_CELSIUS, Math.min(c, cap));
    }

    // ── FluidCapable ──────────────────────────────────────────────────────────

    @Override
    public boolean canAcceptFluid(FluidType type) {
        if (!pipeType.getAllowedFluids().contains(type)) return false;
        if (bufferFluid != null && bufferFluid != type) return false; // buffer holds different type
        return bufferAmount < pipeType.getTransferAmount();
    }

    @Override
    public boolean canAcceptFluidFrom(FluidType type, Vector3i fromPos) {
        // Refuse fluid from the downstream side to prevent backflow.
        // e.g. a steam-import pipe won't accept water pushed back from the generator.
        if (downstreamPos != null
                && downstreamPos.x == fromPos.x
                && downstreamPos.y == fromPos.y
                && downstreamPos.z == fromPos.z) return false;
        return canAcceptFluid(type);
    }

    @Override
    public int acceptFluid(FluidType type, int amount, boolean simulate) {
        if (!pipeType.getAllowedFluids().contains(type)) return 0;
        if (bufferFluid != null && bufferFluid != type) return 0;
        int space  = pipeType.getTransferAmount() - bufferAmount;
        int actual = Math.min(amount, space);
        if (!simulate && actual > 0) {
            bufferFluid   = type;
            bufferAmount += actual;
            // Water cannot exceed 100°C — clamp existing heat when switching to water.
            if (type == FluidType.WATER && heatCelsius > 100.0) {
                heatCelsius = 100.0;
            }
        }
        return actual;
    }

    @Override
    public boolean canProvideFluid(FluidType type) {
        return bufferFluid == type && bufferAmount > 0;
    }

    @Override
    public int extractFluid(FluidType type, int amount, boolean simulate) {
        if (bufferFluid != type || bufferAmount <= 0) return 0;
        int actual = Math.min(amount, bufferAmount);
        if (!simulate && actual > 0) {
            bufferAmount -= actual;
            if (bufferAmount == 0) bufferFluid = null;
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

        // ── 1. Resolve position ───────────────────────────────────────────────
        if (!positionResolved) {
            probePosition();
            if (!positionResolved) resolvePositionFromStore(store, archetypeChunk.getReferenceTo(index));
            if (!positionResolved) return;
        }

        // ── 2. Register in fluid network and heat network ────────────────────
        if (!registeredInNetwork) {
            FluidNetwork.register(cachedPosition, this);
            HeatNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
        }
        // Passive cooling each tick
        HeatNetwork.tickCooling(cachedPosition);
        // If this pipe carries hot fluid (e.g. steam), radiate heat to adjacent nodes
        if (heatCelsius > HeatCapable.AMBIENT_CELSIUS && bufferAmount > 0) {
            HeatNetwork.registerProvider(cachedPosition);
            HeatNetwork.pushHeat(cachedPosition, heatCelsius);
            HeatNetwork.unregisterProvider(cachedPosition);
        }
        // ── Animation hold countdown ─────────────────────────────────────────
        animator.tick(world, cachedPosition);

        // ── Connection model (runs every tick, skips setBlock on no change) ─────
        connectionMask = syncConnectionModel(world);

        // ── 3. Transfer interval ────────────────────────────────────────────
        transferTimer++;
        if (transferTimer < pipeType.getTransferInterval()) return;
        transferTimer = 0;

        // ── 4. Push buffer to adjacent sink (gradient-aware for pipes) ──────
        // Pipes do NOT pull — source machines (steam gen, etc.) already push
        // into adjacent pipes on their own tick.  Pull caused oscillation
        // where pipes would pull from reservoirs and push right back.
        if (bufferAmount > 0 && bufferFluid != null) {
            pushToAdjacentGradient();
        }
        // ── 5. Sync fluid texture ──────────────────────────────────────────────
        syncFluidTexture(world);    }

    // ── Private helpers ───────────────────────────────────────────────────────
    /**
     * Scans all 6 neighbours and selects the correct connection-count model.
     *
     * <p>A neighbour is connected if it is a registered {@link FluidCapable} node
     * (pipe, machine, reservoir, etc.) in the {@link FluidNetwork}.
     *
     * @return The new 6-bit connection mask (use to update {@code connectionMask}).
     */
    private int syncConnectionModel(World world) {
        boolean[] conn = new boolean[6];
        for (int i = 0; i < 6; i++) {
            int[] o = BlockAnimator.FACE_OFFSETS[i];
            Vector3i adj = VectorCompat.vec3i(
                    cachedPosition.x + o[0],
                    cachedPosition.y + o[1],
                    cachedPosition.z + o[2]);
            FluidCapable neighbour = FluidNetwork.getAt(adj);
            conn[i] = (neighbour != null && neighbour != this);
        }
        return BlockAnimator.syncConnectionModel(world, cachedPosition, conn, connectionMask);
    }

    /**
     * Applies the correct fluid block-state variant when the buffer fluid changes.
     *
     * <p>State name convention: {@code "Fluid_WATER"}, {@code "Fluid_STEAM"}, etc.
     * (derived from {@code FluidType.name()}). When the buffer is empty the block
     * reverts to its default / "Off" state. Add matching entries to the block's
     * JSON state map before expecting the texture swap to take effect.
     */
    private void syncFluidTexture(World world) {
        if (bufferFluid == appliedFluidVariant) return; // nothing changed
        if (bufferFluid != null) {
            animator.setState(world, cachedPosition,
                    "Fluid_" + bufferFluid.name(), BlockAnimator.HOLD_INDEFINITE);
        } else {
            animator.clear(world, cachedPosition);
        }
        appliedFluidVariant = bufferFluid;
    }

    /**
     * Push fluid to adjacent blocks with gradient awareness:
     * - Non-pipe sinks: push freely (machines, reservoirs accept all they can).
     * - Pipe sinks: only push half the difference so fluid equalises without
     *   ping-ponging.  A pipe with 500 mB won't push to a neighbour with 500 mB.
     */
    private void pushToAdjacentGradient() {
        int[][] offsets = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int[] o : offsets) {
            if (bufferAmount <= 0 || bufferFluid == null) break;
            Vector3i adj = VectorCompat.vec3i(
                    cachedPosition.x + o[0],
                    cachedPosition.y + o[1],
                    cachedPosition.z + o[2]);
            FluidCapable sink = FluidNetwork.getAt(adj);
            if (sink == null || sink == this) continue;
            if (!sink.canAcceptFluidFrom(bufferFluid, cachedPosition)) continue;

            int budget;
            if (sink instanceof FluidPipeState other) {
                // Gradient: only push if we have MORE, and only push half the diff
                int diff = bufferAmount - other.bufferAmount;
                if (diff <= 0) continue;          // they have same or more — don't push
                budget = diff / 2;
                if (budget <= 0) budget = 1;      // at least 1 mB so it converges
            } else {
                // Non-pipe sink (machine/reservoir): push as much as possible
                budget = bufferAmount;
            }

            int canExtract = extractFluid(bufferFluid, budget, true);
            if (canExtract <= 0) break;
            int accepted = sink.acceptFluid(bufferFluid, canExtract, false);
            if (accepted > 0) {
                extractFluid(bufferFluid, accepted, false);
                // Record this non-pipe neighbour as downstream so we don't accept
                // a different fluid type pushed back from it (prevents backflow).
                if (!(sink instanceof FluidPipeState)) {
                    downstreamPos = adj;
                }
            }
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
                synchronized (FluidPipeState.class) {
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
