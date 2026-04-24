package com.Ev0sMods.PhosphorTech.blocks;

import javax.annotation.Nonnull;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.heat.HeatCapable;
import com.Ev0sMods.PhosphorTech.heat.HeatNetwork;
import com.Ev0sMods.PhosphorTech.mechanical.GearNetwork;
import com.Ev0sMods.PhosphorTech.mechanical.JouleReceiver;
import com.Ev0sMods.PhosphorTech.mechanical.MechanicalCapable;
import com.Ev0sMods.PhosphorTech.ui.MechanicalHeaterUIPage;
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
 * ECS component for the Mechanical Heater block.
 *
 * <p>Receives Joules from the gear network each tick. Every Joule consumed
 * raises the heater's temperature by {@value #HEAT_RISE_PER_JOULE}°C, up to
 * {@value #MAX_HEAT_CELSIUS}°C. Operates up to 800°C.
 *
 * <p>Block-state variants:
 * <ul>
 *   <li>{@code "Off"}    – no power</li>
 *   <li>{@code "Active"} – receiving joules</li>
 * </ul>
 */
@SuppressWarnings({"unchecked", "removal"})
public class MechanicalHeaterState
        implements Component<ChunkStore>, TickableBlockState,
                   JouleReceiver, MechanicalCapable, HeatCapable {

    // ── Constants ─────────────────────────────────────────────────────────────

    public static final double J_CAPACITY         = 50.0;
    public static final double J_PER_TICK         = 1.0;
    /** °C gained per Joule consumed. */
    public static final double HEAT_RISE_PER_JOULE = 8.0;
    public static final double MAX_HEAT_CELSIUS    = 800.0;
    public static final int    RECEIVE_INTERVAL    = 5;
    public static final int    STALL_TICKS         = 10;

    // ── Component registration ────────────────────────────────────────────────

    public static ComponentType<ChunkStore, MechanicalHeaterState> COMPONENT_TYPE;

    // ── Serialised fields ─────────────────────────────────────────────────────

    public double joulesStored = 0.0;
    public double heatCelsius  = HeatCapable.AMBIENT_CELSIUS;
    /** Direction this heater is pointing. Heat propagates into the block on this side. */
    public String facing       = "South";

    // ── Runtime-only state ────────────────────────────────────────────────────

    public  boolean isHeating         = false;
    public  boolean uiDirty           = false;
    public  int     uiTick            = 0;
    public  double  currentSpeed      = 0.0;
    public  int     stallTimer        = 0;

    volatile boolean removed = false;

    private Vector3i cachedPosition      = new Vector3i(0, 0, 0);
    private boolean  positionResolved    = false;
    private boolean  registeredInNetwork = false;

    private final BlockAnimator animator = new BlockAnimator();

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<MechanicalHeaterState> CODEC =
            BuilderCodec.builder(MechanicalHeaterState.class, MechanicalHeaterState::new)
                .append(new KeyedCodec<>("JoulesStored", Codec.DOUBLE, true),
                        (s, v) -> s.joulesStored = v, s -> s.joulesStored).add()
                .append(new KeyedCodec<>("HeatCelsius",  Codec.DOUBLE, true),
                        (s, v) -> s.heatCelsius  = v, s -> s.heatCelsius).add()
                .append(new KeyedCodec<>("Facing",       Codec.STRING, true),
                        (s, v) -> s.facing       = v == null ? "South" : v, s -> s.facing).add()
                .build();

    // ── Constructors ──────────────────────────────────────────────────────────

    public MechanicalHeaterState() {}
    private MechanicalHeaterState(MechanicalHeaterState o) {
        this.joulesStored = o.joulesStored;
        this.heatCelsius  = o.heatCelsius;
        this.facing       = o.facing;
    }

    // ── Component ─────────────────────────────────────────────────────────────

    @Override public MechanicalHeaterState clone()       { return new MechanicalHeaterState(this); }
    @Override public WorldChunk             getChunk()   { return null; }
    @Override public Vector3i               getPosition(){ return cachedPosition; }
    @Override public void                   invalidate() {
        if (positionResolved) HeatNetwork.unregisterProvider(cachedPosition);
        registeredInNetwork = false;
    }

    // ── JouleReceiver ─────────────────────────────────────────────────────────

    @Override public double getJoulesStored()    { return joulesStored; }
    @Override public double getJoulesCapacity()  { return J_CAPACITY; }

    @Override
    public double receiveJoules(double amount, double speed, boolean simulate) {
        double space  = J_CAPACITY - joulesStored;
        double actual = Math.min(amount, space);
        if (!simulate && actual > 0) { joulesStored += actual; uiDirty = true; }
        return actual;
    }

    // ── MechanicalCapable ─────────────────────────────────────────────────────

    @Override public void onNeighborGearChanged() { /* visuals refresh on next tick */ }

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
            GearNetwork.register(cachedPosition, this);
            HeatNetwork.register(cachedPosition, this);
            HeatNetwork.registerProvider(cachedPosition);
            registeredInNetwork = true;
        }

        // ── 3. Consume joules and raise heat ──────────────────────────────────
        // Joules are pushed into joulesStored via receiveJoules() by GearNetwork.pushFromProvider
        if (joulesStored >= J_PER_TICK && heatCelsius < MAX_HEAT_CELSIUS) {
            double jUsed    = Math.min(joulesStored, J_PER_TICK);
            joulesStored   -= jUsed;
            heatCelsius     = Math.min(MAX_HEAT_CELSIUS,
                                       heatCelsius + jUsed * HEAT_RISE_PER_JOULE);
            isHeating       = true;
            stallTimer      = 0;
            uiDirty         = true;
        } else {
            stallTimer++;
            if (stallTimer >= STALL_TICKS) {
                isHeating = false;
            }
        }

        // ── 4. Passive cooling ─────────────────────────────────────────────────
        HeatNetwork.tickCooling(cachedPosition);

        // ── 5. Push heat outward in the facing direction ─────────────────
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
        if (MechanicalHeaterUIPage.hasWatcher(cachedPosition) && (uiDirty || uiTick >= 20)) {
            uiTick  = 0;
            uiDirty = false;
            MechanicalHeaterUIPage.tickRefresh(this, store, cachedPosition);
        }
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
                synchronized (MechanicalHeaterState.class) {
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
