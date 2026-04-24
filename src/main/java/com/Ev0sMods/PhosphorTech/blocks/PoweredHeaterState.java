package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Map;

import javax.annotation.Nonnull;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxNetwork;
import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxReceiver;
import com.Ev0sMods.PhosphorTech.heat.HeatCapable;
import com.Ev0sMods.PhosphorTech.heat.HeatNetwork;
import com.Ev0sMods.PhosphorTech.ui.PoweredHeaterUIPage;
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
 * ECS component for the Powered Heater block.
 *
 * <p>Consumes {@link #CF_PER_TICK} Crystalline Flux every tick while active
 * and pushes heat into adjacent {@link HeatCapable} blocks via
 * {@link HeatNetwork}.  Operates up to {@value #MAX_HEAT_CELSIUS}°C.
 *
 * <p>Block-state variants expected in JSON:
 * <ul>
 *   <li>{@code "Off"}    – no CF, idle</li>
 *   <li>{@code "Active"} – heating</li>
 * </ul>
 */
@SuppressWarnings({"unchecked", "removal"})
public class PoweredHeaterState
        implements Component<ChunkStore>, TickableBlockState,
                   CrystallineFluxReceiver, HeatCapable {

    // ── Constants ─────────────────────────────────────────────────────────────

    public static final long   CF_CAPACITY      = 5_000L;
    public static final long   CF_PER_TICK      = 10L;
    /** Rate of temperature rise per tick while actively consuming CF. °C/tick. */
    public static final double HEAT_RISE_PER_TICK = 2.0;
    public static final double MAX_HEAT_CELSIUS  = 1_200.0;

    // ── Component registration ────────────────────────────────────────────────

    public static ComponentType<ChunkStore, PoweredHeaterState> COMPONENT_TYPE;

    // ── Serialised fields ─────────────────────────────────────────────────────

    public long   cfStored      = 0L;
    public double heatCelsius   = HeatCapable.AMBIENT_CELSIUS;
    /** Direction this heater is facing. Heat propagates into the block on this side. */
    public String facing        = "South";

    // ── Runtime-only state ────────────────────────────────────────────────────

    public boolean isHeating    = false;
    public boolean uiDirty      = false;
    public int     uiTick       = 0;

    volatile boolean removed = false;

    private Vector3i cachedPosition      = new Vector3i(0, 0, 0);
    private boolean  positionResolved    = false;
    private boolean  registeredInNetwork = false;

    final BlockAnimator animator = new BlockAnimator();

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<PoweredHeaterState> CODEC =
            BuilderCodec.builder(PoweredHeaterState.class, PoweredHeaterState::new)
                .append(new KeyedCodec<>("CFStored",    Codec.LONG,   true),
                        (s, v) -> s.cfStored    = v, s -> s.cfStored).add()
                .append(new KeyedCodec<>("HeatCelsius", Codec.DOUBLE, true),
                        (s, v) -> s.heatCelsius = v, s -> s.heatCelsius).add()
                .append(new KeyedCodec<>("Facing",      Codec.STRING, true),
                        (s, v) -> s.facing      = v == null ? "South" : v, s -> s.facing).add()
                .build();

    // ── Constructors ──────────────────────────────────────────────────────────

    public PoweredHeaterState() {}
    private PoweredHeaterState(PoweredHeaterState o) {
        this.cfStored    = o.cfStored;
        this.heatCelsius = o.heatCelsius;
        this.facing      = o.facing;
    }

    // ── Component ─────────────────────────────────────────────────────────────

    @Override public PoweredHeaterState clone()      { return new PoweredHeaterState(this); }
    @Override public WorldChunk          getChunk()  { return null; }
    @Override public Vector3i            getPosition(){ return cachedPosition; }
    @Override public void                invalidate() {
        if (positionResolved) HeatNetwork.unregisterProvider(cachedPosition);
        registeredInNetwork = false;
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

    // ── HeatCapable ───────────────────────────────────────────────────────────

    @Override public double getHeat()    { return heatCelsius; }
    @Override public double getMaxHeat() { return MAX_HEAT_CELSIUS; }
    @Override public void   setHeat(double c) {
        heatCelsius = Math.max(HeatCapable.AMBIENT_CELSIUS, Math.min(c, MAX_HEAT_CELSIUS));
        uiDirty = true;
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
            CrystallineFluxNetwork.register(cachedPosition, this);
            HeatNetwork.register(cachedPosition, this);
            HeatNetwork.registerProvider(cachedPosition);
            registeredInNetwork = true;
        }

        // ── 3. Consume CF and raise heat ──────────────────────────────────────
        if (cfStored >= CF_PER_TICK && heatCelsius < MAX_HEAT_CELSIUS) {
            cfStored  -= CF_PER_TICK;
            heatCelsius = Math.min(MAX_HEAT_CELSIUS, heatCelsius + HEAT_RISE_PER_TICK);
            isHeating = true;
            uiDirty   = true;
        } else {
            isHeating = false;
        }

        // ── 4. Passive cooling ─────────────────────────────────────────────────
        HeatNetwork.tickCooling(cachedPosition);

        // ── 5. Push heat into the block this heater faces ─────────────────
        if (heatCelsius > HeatCapable.AMBIENT_CELSIUS) {
            HeatNetwork.pushHeat(cachedPosition, heatCelsius);
        }

        // ── 6. Animation ──────────────────────────────────────────────────────
        animator.tick(world, cachedPosition);
        if (isHeating) {
            animator.setState(world, cachedPosition, BlockAnimator.STATE_ACTIVE, BlockAnimator.HOLD_INDEFINITE);
        } else {
            animator.clear(world, cachedPosition);
        }

        // ── 7. UI refresh ─────────────────────────────────────────────────────
        uiTick++;
        if (PoweredHeaterUIPage.hasWatcher(cachedPosition) && (uiDirty || uiTick >= 20)) {
            uiTick  = 0;
            uiDirty = false;
            PoweredHeaterUIPage.tickRefresh(this, store, cachedPosition);
        }
    }

    // ── Position resolution (standard pattern) ────────────────────────────────

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
                synchronized (PoweredHeaterState.class) {
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
