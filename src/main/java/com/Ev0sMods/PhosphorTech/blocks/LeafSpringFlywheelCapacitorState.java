package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Map;

import javax.annotation.Nonnull;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.mechanical.GearNetwork;
import com.Ev0sMods.PhosphorTech.mechanical.JouleProvider;
import com.Ev0sMods.PhosphorTech.mechanical.JouleReceiver;
import com.Ev0sMods.PhosphorTech.mechanical.MechanicalCapable;
import com.Ev0sMods.PhosphorTech.mechanical.SpinningGear;
import com.Ev0sMods.PhosphorTech.ui.LeafSpringFlywheelCapacitorUIPage;
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
 * ECS component for the Leaf Spring Flywheel Capacitor block.
 *
 * <p>Stores up to {@value #J_CAPACITY} Joules received from the gear network.
 * Acts as a pure buffer — it accepts J from any adjacent gear provider but
 * does not drive other machines itself.
 */
@SuppressWarnings({"unchecked", "removal"})
public class LeafSpringFlywheelCapacitorState
        implements Component<ChunkStore>, TickableBlockState,
                   JouleProvider, JouleReceiver, MechanicalCapable, SpinningGear {

    // ── Constants ─────────────────────────────────────────────────────────────

    public static final double J_CAPACITY = 300.0;

    // ── Component registration ────────────────────────────────────────────────

    public static ComponentType<ChunkStore, LeafSpringFlywheelCapacitorState> COMPONENT_TYPE;

    // ── Serialised fields ─────────────────────────────────────────────────────

    public double joulesStored = 0.0;

    // ── Runtime-only state ────────────────────────────────────────────────────

    public double currentSpeed = 0.0;
    private int    stallTimer  = 0;
    private static final int STALL_TICKS = 10;

    public int     uiTick  = 0;
    public boolean uiDirty = false;

    private final BlockAnimator animator = new BlockAnimator();

        /** Set by the cleanup system the moment the block is removed -- tick() checks this first. */
    volatile boolean removed = false;

    private Vector3i cachedPosition      = new Vector3i(0, 0, 0);
    private boolean  positionResolved    = false;
    private boolean  registeredInNetwork = false;

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<LeafSpringFlywheelCapacitorState> CODEC =
            BuilderCodec.builder(LeafSpringFlywheelCapacitorState.class,
                                 LeafSpringFlywheelCapacitorState::new)
                .append(new KeyedCodec<>("JoulesStored", Codec.DOUBLE, true),
                        (s, v) -> s.joulesStored = v, s -> s.joulesStored).add()
                .build();

    // ── Constructor ───────────────────────────────────────────────────────────

    public LeafSpringFlywheelCapacitorState() {}

    private LeafSpringFlywheelCapacitorState(LeafSpringFlywheelCapacitorState o) {
        this.joulesStored = o.joulesStored;
    }

    // ── Component ────────────────────────────────────────────────────────────

    @Override public LeafSpringFlywheelCapacitorState clone() { return new LeafSpringFlywheelCapacitorState(this); }
    @Override public WorldChunk   getChunk()    { return null; }
    @Override public Vector3i     getPosition() { return cachedPosition; }
    @Override public void         invalidate()  { registeredInNetwork = false; }

    // ── MechanicalCapable ─────────────────────────────────────────────────────

    @Override public void onNeighborGearChanged() {}

    // ── SpinningGear ──────────────────────────────────────────────────────────

    @Override
    public void receiveSpinSignal(double speed) {
        currentSpeed = speed;
        stallTimer   = 0;
        uiDirty      = true;
    }

    @Override
    public void stopSpin() {
        currentSpeed = 0.0;
        stallTimer   = STALL_TICKS + 1;  // immediately stalled
    }
    // ── JouleProvider ─────────────────────────────────────────────────────

    @Override public double getSpeed() { return currentSpeed > 0 ? currentSpeed : 1.0; }

    @Override
    public double extractJoules(double amount, boolean simulate) {
        double actual = Math.min(amount, joulesStored);
        if (!simulate && actual > 0) {
            joulesStored -= actual;
            uiDirty       = true;
        }
        return actual;
    }
    // ── JouleReceiver ─────────────────────────────────────────────────────────

    @Override public double getJoulesStored()   { return joulesStored; }
    @Override public double getJoulesCapacity() { return J_CAPACITY; }

    @Override
    public double receiveJoules(double amount, double speed, boolean simulate) {
        double space  = J_CAPACITY - joulesStored;
        double actual = Math.min(amount, space);
        if (!simulate && actual > 0) {
            joulesStored += actual;
            uiDirty       = true;
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
            GearNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
        }

        stallTimer++;
        if (stallTimer > STALL_TICKS) {
            currentSpeed = 0.0;
        }

        // Push stored J into adjacent receivers when we have stored energy
        if (joulesStored > 0) {
            GearNetwork.pushFromProvider(cachedPosition, this, joulesStored);
        }

        animator.tick(world, cachedPosition);

        if (joulesStored > 0 && currentSpeed > 0) {
            animator.setState(world, cachedPosition, BlockAnimator.STATE_ACTIVE, 20);
        }
        // When idle, don't clear — let the hold timer in animator.tick() expire
        // naturally so the animation pauses rather than snapping to Off.

        uiTick++;
        boolean hasWatcher = LeafSpringFlywheelCapacitorUIPage.hasWatcher(cachedPosition);
        if (hasWatcher && (uiDirty || uiTick >= 20)) {
            uiTick  = 0;
            uiDirty = false;
            LeafSpringFlywheelCapacitorUIPage.tickRefresh(this, store, cachedPosition);
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
                synchronized (LeafSpringFlywheelCapacitorState.class) {
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
