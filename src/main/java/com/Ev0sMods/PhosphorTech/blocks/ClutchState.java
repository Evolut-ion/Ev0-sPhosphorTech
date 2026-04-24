package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Map;

import javax.annotation.Nonnull;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.mechanical.GearBlocker;
import com.Ev0sMods.PhosphorTech.mechanical.GearNetwork;
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
 * ECS component for the Gear Clutch block.
 *
 * <p>When <b>unlocked</b>, the clutch behaves identically to a {@link SmallGearState}:
 * it passes spin signals and acts as a J-conduit in the {@link GearNetwork}.
 *
 * <p>When <b>locked</b>, the clutch implements {@link GearBlocker#isGearBlocked()}
 * → {@code true}.  Both BFS passes in {@link GearNetwork} ({@code propagateFrom}
 * and {@code pushFromProvider}) stop at this node, isolating every machine on
 * the far side from its power source — like throwing a wrench in a cog.
 *
 * <p>The clutch is toggled via right-click ({@code ToggleClutchInteraction}).
 *
 * <p>Block-state variants expected in the item/block JSON:
 * <ul>
 *   <li>{@code "Off"}      – unlocked, not spinning</li>
 *   <li>{@code "Spin"}     – unlocked, spinning (normal phase)</li>
 *   <li>{@code "SpinAlt"}  – unlocked, spinning (alt phase)</li>
 *   <li>{@code "Locked"}   – locked, gear seized</li>
 * </ul>
 */
@SuppressWarnings({"unchecked", "removal"})
public class ClutchState
        implements Component<ChunkStore>, TickableBlockState,
                   SpinningGear, MechanicalCapable, GearBlocker {

    // ── Component registration ────────────────────────────────────────────────

    public static ComponentType<ChunkStore, ClutchState> COMPONENT_TYPE;

    // ── Serialised fields ─────────────────────────────────────────────────────

    /** {@code true} = clutch is locked; no power passes through. */
    public boolean locked = false;

    // ── Runtime-only state ────────────────────────────────────────────────────
    /** Set by the cleanup system the moment the block is removed — tick() checks this first. */
    volatile boolean removed = false;
    private int    spinTimer           = 0;
    private double currentSpeed        = 1.0;
    /** Last animation state applied — null forces re-apply on first tick. */
    private String lastRenderedState   = null;
    private volatile boolean neighborDirty = false;

    private Vector3i cachedPosition    = new Vector3i(0, 0, 0);
    private boolean  positionResolved  = false;
    private boolean  registeredInNetwork = false;

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<ClutchState> CODEC =
            BuilderCodec.builder(ClutchState.class, ClutchState::new)
                .append(new KeyedCodec<>("Locked", Codec.BOOLEAN, true),
                        (s, v) -> s.locked = v != null && v, s -> s.locked).add()
                .build();

    // ── Constructors ──────────────────────────────────────────────────────────

    public ClutchState() {}

    private ClutchState(ClutchState o) {
        this.locked             = o.locked;
        this.cachedPosition     = new Vector3i(o.cachedPosition.x, o.cachedPosition.y, o.cachedPosition.z);
        this.positionResolved   = o.positionResolved;
    }

    // ── Component ─────────────────────────────────────────────────────────────

    @Override public ClutchState clone()      { return new ClutchState(this); }
    @Override public WorldChunk  getChunk()   { return null; }
    @Override public Vector3i    getPosition(){ return cachedPosition; }
    @Override public void        invalidate() { registeredInNetwork = false; }

    // ── GearBlocker ───────────────────────────────────────────────────────────

    @Override
    public boolean isGearBlocked() { return locked; }

    // ── MechanicalCapable ─────────────────────────────────────────────────────

    @Override
    public void onNeighborGearChanged() { neighborDirty = true; }

    // ── SpinningGear ──────────────────────────────────────────────────────────

    private int spinDirection = 1;

    @Override
    public void receiveSpinSignal(double speed) {
        if (locked) return;     // locked clutch absorbs nothing
        spinTimer    = 3;
        currentSpeed = speed;
    }

    @Override
    public void receiveSpinSignal(double speed, int direction) {
        spinDirection = direction;
        receiveSpinSignal(speed);
    }

    @Override
    public int getSpinDirection() { return spinDirection; }

    // ── Tick ─────────────────────────────────────────────────────────────────

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

        // ── 3. When locked — freeze everything ───────────────────────────────
        if (locked) {
            spinTimer = 0;
            neighborDirty = false;
            if (!"Locked".equals(lastRenderedState)) {
                BlockAnimator.applyBlockState(world, cachedPosition, "Locked");
                lastRenderedState = "Locked";
            }
            return;
        }

        // ── 4. Unlocked — conduit role ────────────────────────────────────────
        if (spinTimer > 0) spinTimer--;

        // ── 5. Animation — transition block state for visual spin ─────────
        boolean spinning = spinTimer > 0;
        neighborDirty = false;
        String desiredState = spinning ? "Spin" : "Idle";
        if (!desiredState.equals(lastRenderedState)) {
            BlockAnimator.applyBlockState(world, cachedPosition, desiredState);
            lastRenderedState = desiredState;
        }
    }

    // ── Position helpers (same pattern as SmallGearState) ────────────────────

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
                synchronized (ClutchState.class) {
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
