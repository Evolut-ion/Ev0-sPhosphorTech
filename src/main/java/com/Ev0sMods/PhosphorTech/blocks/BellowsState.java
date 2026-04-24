package com.Ev0sMods.PhosphorTech.blocks;

import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import org.joml.Vector3i;

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
 * ECS component for the Bellows block.
 *
 * <p>Players right-click the Bellows with the {@code PumpBellows} interaction to
 * activate it.  Each pump fires {@value #HEAT_BOOST_PER_PUMP}°C of heat into adjacent
 * {@link HeatCapable} blocks via {@link HeatNetwork#pushHeat}.  The boost persists for
 * {@value #PUMP_TICKS} ticks, after which the bellows returns to idle.
 *
 * <p>The Bellows itself does not store heat — it is a transient actuator.
 */
@SuppressWarnings({"unchecked", "removal"})
public class BellowsState
        implements Component<ChunkStore>, TickableBlockState {

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Ticks the boost lasts after each activation. */
    public static final int    PUMP_TICKS          = 10;
    /** Heat added to the network per pump (°C). */
    public static final double HEAT_BOOST_PER_PUMP  = 5.0;

    /**
     * Block-type ID substrings that are valid bellows targets.
     * The bellows only injects heat when its facing block matches one of these.
     */
    public static final java.util.Set<String> VALID_BELLOWS_TARGETS =
            java.util.Set.of("AlloySmelter", "CrystalGenerator", "SteamGenerator", "CokeOven");

    // ── Component registration ────────────────────────────────────────────────

    public static ComponentType<ChunkStore, BellowsState> COMPONENT_TYPE;

    // ── Static lookup registry ────────────────────────────────────────────────

    public static final ConcurrentHashMap<String, BellowsState> REGISTRY = new ConcurrentHashMap<>();

    private static String posKey(Vector3i p) { return p.x + "," + p.y + "," + p.z; }

    // ── Serialised fields ─────────────────────────────────────────────────────

    public int    pumpTimer = 0;
    /** Direction the bellows is pointing. Determines which block it heats. */
    public String facing    = "South";

    // ── Runtime-only state ────────────────────────────────────────────────────

    /** Set to {@code true} by {@link com.Ev0sMods.PhosphorTech.interactions.PumpBellowsInteraction}. */
    public volatile boolean activated = false;

    volatile boolean removed = false;

    private Vector3i cachedPosition   = new Vector3i(0, 0, 0);
    private boolean  positionResolved = false;

    private final BlockAnimator animator = new BlockAnimator();

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<BellowsState> CODEC =
            BuilderCodec.builder(BellowsState.class, BellowsState::new)
                .append(new KeyedCodec<>("PumpTimer", Codec.LONG, true),
                        (s, v) -> s.pumpTimer = v == null ? 0 : (int)(long) v, s -> (long) s.pumpTimer).add()
                .append(new KeyedCodec<>("Facing", Codec.STRING, true),
                        (s, v) -> s.facing = v == null ? "South" : v, s -> s.facing).add()
                .build();

    // ── Constructors ──────────────────────────────────────────────────────────

    public BellowsState() {}
    private BellowsState(BellowsState o) {
        this.pumpTimer = o.pumpTimer;
        this.facing    = o.facing;
    }

    // ── Component ─────────────────────────────────────────────────────────────

    @Override public BellowsState clone()       { return new BellowsState(this); }
    @Override public WorldChunk   getChunk()    { return null; }
    @Override public Vector3i     getPosition() { return cachedPosition; }
    @Override public void         invalidate()  { if (positionResolved) REGISTRY.remove(posKey(cachedPosition), this); }

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
            REGISTRY.put(posKey(cachedPosition), this);
        }

        // ── 2. Handle activation ──────────────────────────────────────────────
        if (activated) {
            activated = false;
            pumpTimer = PUMP_TICKS;
            // Immediately push heat boost to valid facing block
            pushHeatBoost(world);
        }

        // ── 3. Tick down pump ─────────────────────────────────────────────────
        if (pumpTimer > 0) {
            pumpTimer--;
            // Continue pushing heat each tick while the bellows is pumping
            pushHeatBoost(world);
        }

        // ── 4. Animation ──────────────────────────────────────────────────────
        animator.tick(world, cachedPosition);
        if (pumpTimer > 0) {
            animator.setState(world, cachedPosition, BlockAnimator.STATE_ACTIVE, BlockAnimator.HOLD_INDEFINITE);
        } else {
            animator.clear(world, cachedPosition);
        }
    }

    // ── Push heat to the facing furnace block ─────────────────────────────────────

    /**
     * Checks that the bellows is facing a valid furnace-type machine and, if so,
     * injects {@value #HEAT_BOOST_PER_PUMP}°C into the first registered
     * {@link com.Ev0sMods.PhosphorTech.heat.HeatCapable} node on the facing side
     * (usually a pipe adjacent to the furnace).  Heat then propagates normally
     * through the network.
     *
     * <p>Validation uses the block-type ID at the facing position so that any
     * machine ID containing "AlloySmelter", "CrystalGenerator", "SteamGenerator",
     * or "CokeOven" qualifies (state-variant suffixes are accepted automatically).
     */
    private void pushHeatBoost(World world) {
        if (cachedPosition == null) return;

        // Compute the target position (the single block this bellows faces).
        com.Ev0sMods.PhosphorTech.heat.HeatNetwork heat = null; // namespace ref
        org.joml.Vector3i delta  = com.Ev0sMods.PhosphorTech.heat.HeatNetwork.facingDelta(facing);
        org.joml.Vector3i target = new org.joml.Vector3i(
                cachedPosition.x + delta.x,
                cachedPosition.y + delta.y,
                cachedPosition.z + delta.z);

        // ── Validate that the facing block is a supported furnace type. ──────
        boolean validTarget = false;
        try {
            var chunk = world.getChunkIfInMemory(
                    com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(target.x, target.z));
            if (chunk != null) {
                var bt = chunk.getBlockType(target.x, target.y, target.z);
                if (bt != null) {
                    String id = String.valueOf(bt.getId());
                    for (String t : VALID_BELLOWS_TARGETS) {
                        if (id.contains(t)) { validTarget = true; break; }
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (!validTarget) return;

        // ── Inject heat into any HeatCapable node on the facing side. ───────
        // Check the target position directly first (e.g. a pipe touching the furnace)
        // then fall back to all other neighbours so heat still enters the network
        // even when pipes are on the sides rather than between bellows and furnace.
        boolean injected = injectAt(target);
        if (!injected) {
            int[][] offsets = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
            for (int[] o : offsets) {
                org.joml.Vector3i adj = new org.joml.Vector3i(
                        cachedPosition.x + o[0],
                        cachedPosition.y + o[1],
                        cachedPosition.z + o[2]);
                if (adj.equals(target)) continue; // skip the furnace face itself
                if (injectAt(adj)) break;          // inject into first found node
            }
        }
    }

    /** Injects {@value #HEAT_BOOST_PER_PUMP}°C into the node at {@code pos}.
     *  Returns {@code true} if a node was found. */
    private boolean injectAt(org.joml.Vector3i pos) {
        com.Ev0sMods.PhosphorTech.heat.HeatCapable hc =
                com.Ev0sMods.PhosphorTech.heat.HeatNetwork.getAt(pos);
        if (hc == null) return false;
        hc.setHeat(Math.min(hc.getMaxHeat(), hc.getHeat() + HEAT_BOOST_PER_PUMP));
        return true;
    }

    // ── Position resolution ───────────────────────────────────────────────────

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
                synchronized (BellowsState.class) {
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
