package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Map;
import org.joml.Vector3i;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
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
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.Ev0sMods.PhosphorTech.blocks.BlockAnimator;
import com.Ev0sMods.PhosphorTech.mechanical.GearNetwork;
import com.Ev0sMods.PhosphorTech.mechanical.JouleProvider;
import com.Ev0sMods.PhosphorTech.mechanical.ShaftAxis;
import com.Ev0sMods.PhosphorTech.mechanical.ShaftConnectable;

/**
 * ECS component for the Windmill block.
 * Generates Joules (mechanical energy) for the GearNetwork based on Y position (higher = more output).
 */
public class WindmillState implements Component<ChunkStore>, TickableBlockState, JouleProvider, ShaftConnectable {

    /** Called when a player interacts with the block. Opens the Windmill UI. */
    public boolean onPlayerInteract(com.hypixel.hytale.server.core.universe.PlayerRef playerRef, com.hypixel.hytale.component.Store<?> store, org.joml.Vector3i pos) {
        com.Ev0sMods.PhosphorTech.ui.WindmillUIPage.openForced(playerRef, null, store, pos);
        return true;
    }
    @Override
    public WorldChunk getChunk() { return null; }
    public static ComponentType<ChunkStore, WindmillState> COMPONENT_TYPE;
    public static final BuilderCodec<WindmillState> CODEC =
        BuilderCodec.builder(WindmillState.class, WindmillState::new)
            .append(new KeyedCodec<>("JoulesStored", Codec.DOUBLE, true), (s, v) -> s.joulesStored = v, s -> s.joulesStored).add()
            .build();

    private Vector3i cachedPosition = new Vector3i(0, 0, 0);
    private boolean positionResolved = false;
    private boolean registeredInNetwork = false;
    /** Block rotation (0=N, 1=E, 2=S, 3=W). rotation 0/2 (N/S facing) → shaft on X axis, rotation 1/3 (E/W facing) → shaft on Z axis. */
    private int cachedRotation = 0;
    private double joulesStored = 0;
    private boolean lastRenderedActive = false;
    private static final double JOULES_PER_TICK_BASE = 0.04; // Example: 0.04 J/tick
    private static final double JOULES_PER_TICK_MAX = 0.16;
    private static final double SPEED = 1.0;

    @Override
    public ShaftAxis getShaftAxis() {
        return (cachedRotation == 0 || cachedRotation == 2) ? ShaftAxis.X : ShaftAxis.Z;
    }

    @Override
    public Vector3i getPosition() { return cachedPosition; }

    @Override
    public void invalidate() { registeredInNetwork = false; }

    @Override
    public void tick(float dt, int index, ArchetypeChunk<ChunkStore> archetypeChunk, Store<ChunkStore> store, CommandBuffer<ChunkStore> commandBuffer) {
        if (!positionResolved) {
            probePosition();
            if (!positionResolved) resolvePositionFromStore(store, archetypeChunk.getReferenceTo(index));
            if (!positionResolved) return;
        }
        if (!registeredInNetwork) {
            GearNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
        }
        // Refresh rotation from chunk each tick
        World world = store.getExternalData().getWorld();
        if (world != null) {
            WorldChunk rotChunk = world.getChunkIfInMemory(
                    ChunkUtil.indexChunkFromBlock(cachedPosition.x, cachedPosition.z));
            if (rotChunk != null) {
                cachedRotation = rotChunk.getRotationIndex(cachedPosition.x, cachedPosition.y, cachedPosition.z);
            }
        }
        // Generate Joules based on Y position (higher = more output)
        double jouleGen = Math.min(JOULES_PER_TICK_BASE + (cachedPosition.y / 128.0), JOULES_PER_TICK_MAX);
        joulesStored += jouleGen;
        // Switch to Active animation state while generating
        if (world != null && !lastRenderedActive) {
            BlockAnimator.applyBlockState(world, cachedPosition, BlockAnimator.STATE_ACTIVE);
            lastRenderedActive = true;
        }
        // Propagate spin signal to connected shafts/gears along the shaft axis
        GearNetwork.propagateFrom(cachedPosition, SPEED);
        GearNetwork.pushFromProvider(cachedPosition, this, jouleGen);
    }


    @Override
    public double getJoulesStored() { return joulesStored; }

    @Override
    public double getJoulesCapacity() { return 100.0; } // Example cap

    @Override
    public double getSpeed() { return SPEED; }

    @Override
    public double extractJoules(double amount, boolean simulate) {
        double extracted = Math.min(joulesStored, amount);
        if (!simulate) joulesStored -= extracted;
        return extracted;
    }

    public void generateJoules() {
        joulesStored += JOULES_PER_TICK_BASE;
    }

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
                } catch (Exception ignored) {}
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
            if (chunks.isEmpty()) return;
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
        } catch (Exception ignored) {}
    }

    private static Map<?, ?> entityRefsViaReflection(BlockComponentChunk bcc) {
        try {
            if (!entityRefsMethodResolved) {
                synchronized (WindmillState.class) {
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
        } catch (Exception ignored) { return null; }
    }

    @Override
    public WindmillState clone() {
        try {
            return (WindmillState) super.clone();
        } catch (Exception e) {
            throw new AssertionError();
        }
    }
}
