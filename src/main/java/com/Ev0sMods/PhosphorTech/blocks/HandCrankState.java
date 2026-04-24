package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Map;

import javax.annotation.Nonnull;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.mechanical.GearNetwork;
import com.Ev0sMods.PhosphorTech.mechanical.JouleProvider;
import com.Ev0sMods.PhosphorTech.mechanical.MechanicalCapable;
import com.Ev0sMods.PhosphorTech.mechanical.SpinningGear;
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
 * ECS component for the Hand Crank block.
 *
 * <p>Provider block — no UI.  When a player right-clicks (interacts) the
 * {@code CrankHandleInteraction} sets {@link #activated} to {@code true},
 * which causes the crank to spin for exactly one full revolution
 * ({@value #REVOLUTION_TICKS} ticks), producing {@value #JOULES_PER_REVOLUTION} J
 * at speed 1.  The power is delivered to the connected gear chain as long as
 * the revolution is in progress.
 *
 * <p>Joules are delivered every {@value #RECEIVE_INTERVAL} ticks
 * (matching the "receive every 5 ticks" specification).
 *
 * <p>Animation states in JSON:
 * <ul>
 *   <li>{@code "Off"}  – idle</li>
 *   <li>{@code "Spin"} – turning (one revolution)</li>
 * </ul>
 */
@SuppressWarnings({"unchecked", "removal"})
public class HandCrankState
        implements Component<ChunkStore>, TickableBlockState,
                   JouleProvider, SpinningGear, MechanicalCapable {

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Ticks for one full revolution at speed 1 (30 TPS → 1 rev ≈ 1 s). */
    public static final int REVOLUTION_TICKS     = 30;

    /** Total Joules produced per manual revolution. */
    public static final double JOULES_PER_REVOLUTION = 4.0;  // 1 J/tick × 30 ticks → cap at 4 J buffer

    /** Deliver Joules every N ticks (5-tick cadence as specified). */
    public static final int RECEIVE_INTERVAL = 5;

    /** Speed is always 1 for the hand crank. */
    public static final double SPEED = 1.0;

    // ── Component registration ────────────────────────────────────────────────

    public static ComponentType<ChunkStore, HandCrankState> COMPONENT_TYPE;

    // ── Serialised fields ─────────────────────────────────────────────────────

    /** Remaining revolution ticks (0 = idle). */
    public int  revolutionTimer = 0;
    /** Joule buffer remaining in this revolution burst. */
    public double joulesStored  = 0.0;
    /** Whether a player just interacted (set externally by the interaction class). */
    public volatile boolean activated = false;

    // ── Runtime-only state ─────────────────────────────────────────────────────

    /** Set by the cleanup system the moment the block is removed — tick() checks this first. */
    volatile boolean removed = false;

    private Vector3i cachedPosition     = new Vector3i(0, 0, 0);
    private boolean  positionResolved   = false;
    private boolean  registeredInNetwork = false;
    private int      deliveryTimer      = 0;
    private boolean  lastRenderedSpin   = false;

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<HandCrankState> CODEC =
            BuilderCodec.builder(HandCrankState.class, HandCrankState::new)
                .append(new KeyedCodec<>("RevolutionTimer", Codec.INTEGER, true),
                        (s, v) -> s.revolutionTimer = v, s -> s.revolutionTimer).add()
                .append(new KeyedCodec<>("JoulesStored", Codec.DOUBLE, true),
                        (s, v) -> s.joulesStored = v, s -> s.joulesStored).add()
                .build();

    // ── Constructor ───────────────────────────────────────────────────────────

    public HandCrankState() {}

    private HandCrankState(HandCrankState o) {
        this.revolutionTimer = o.revolutionTimer;
        this.joulesStored    = o.joulesStored;
        this.cachedPosition  = new Vector3i(o.cachedPosition.x, o.cachedPosition.y, o.cachedPosition.z);
        this.positionResolved = o.positionResolved;
    }

    // ── Component ────────────────────────────────────────────────────────────

    @Override public HandCrankState clone()    { return new HandCrankState(this); }
    @Override public WorldChunk     getChunk() { return null; }
    @Override public Vector3i       getPosition() { return cachedPosition; }
    @Override public void           invalidate()  { registeredInNetwork = false; }

    // ── MechanicalCapable ─────────────────────────────────────────────────────

    @Override public void onNeighborGearChanged() { /* crank has no connection model */ }

    // ── SpinningGear — crank can be spun by adjacent gears too ───────────────

    @Override
    public void receiveSpinSignal(double speed) {
        // The crank does not propagate energy received from other gears; it only
        // produces power when the player activates it.
    }

    // ── JouleProvider ─────────────────────────────────────────────────────────

    @Override public double getJoulesStored()   { return joulesStored; }
    @Override public double getJoulesCapacity() { return JOULES_PER_REVOLUTION; }
    @Override public double getSpeed()          { return SPEED; }

    @Override
    public double extractJoules(double amount, boolean simulate) {
        if (revolutionTimer <= 0) return 0;
        double actual = Math.min(amount, joulesStored);
        if (!simulate) joulesStored -= actual;
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

        // ── 2. Register in gear network ───────────────────────────────────────
        if (!registeredInNetwork) {
            GearNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
        }

        // ── 3. Start revolution on player interaction ─────────────────────────
        if (activated) {
            activated      = false;
            revolutionTimer = REVOLUTION_TICKS;
            joulesStored   = JOULES_PER_REVOLUTION;
            deliveryTimer  = 0;
        }

        // ── 4. Revolution in progress ─────────────────────────────────────────
        if (revolutionTimer > 0) {
            revolutionTimer--;
            deliveryTimer++;

            // Propagate spin every tick so gear spinTimers never expire mid-revolution
            GearNetwork.propagateFrom(cachedPosition, SPEED);

            // Deliver Joules every RECEIVE_INTERVAL ticks
            if (deliveryTimer >= RECEIVE_INTERVAL) {
                deliveryTimer = 0;
                // Spread J evenly: one packet per delivery window
                double packet = Math.min(joulesStored,
                        JOULES_PER_REVOLUTION / (REVOLUTION_TICKS / (double) RECEIVE_INTERVAL));
                GearNetwork.pushFromProvider(cachedPosition, this, packet);
            }
        }

        // ── 5. Clear joules when revolution ends ──────────────────────────
        if (revolutionTimer <= 0 && joulesStored > 0) {
            joulesStored = 0;
        }

        // ── 6. Animation — transition block state for visual spin ─────────
        boolean spinning = revolutionTimer > 0;
        if (spinning != lastRenderedSpin) {
            if (spinning) {
                BlockAnimator.replaceBlock(world, cachedPosition, "HandCrank_Spin");
            } else {
                BlockAnimator.replaceBlock(world, cachedPosition, "HandCrank");
                // Revolution just ended — actively idle all connected consumers
                GearNetwork.stopConnectedFrom(cachedPosition);
            }
            lastRenderedSpin = spinning;
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
                synchronized (HandCrankState.class) {
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
