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
// import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxNetwork;
import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxProvider;

/**
 * ECS component for the Solar Panel block.
 * Generates Crystalline Flux (CF) when exposed to sunlight.
 */
public class SolarPanelState implements Component<ChunkStore>, TickableBlockState, CrystallineFluxProvider {

    /** Called when a player interacts with the block. Opens the Solar Panel UI. */
    public boolean onPlayerInteract(com.hypixel.hytale.server.core.universe.PlayerRef playerRef, com.hypixel.hytale.component.Store<?> store, org.joml.Vector3i pos) {
        com.Ev0sMods.PhosphorTech.ui.SolarPanelUIPage.openForced(playerRef, null, store, pos);
        return true;
    }
    @Override
    public WorldChunk getChunk() { return null; }
    public static ComponentType<ChunkStore, SolarPanelState> COMPONENT_TYPE;
    public static final BuilderCodec<SolarPanelState> CODEC =
        BuilderCodec.builder(SolarPanelState.class, SolarPanelState::new)
            .append(new KeyedCodec<>("CFStored", Codec.LONG, true), (s, v) -> s.cfStored = v, s -> s.cfStored).add()
            .build();

    private Vector3i cachedPosition = new Vector3i(0, 0, 0);
    private boolean positionResolved = false;
    private boolean registeredInNetwork = false;
    private long cfStored = 0;
    private static final long CF_PER_TICK = 8;

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
            CrystallineFluxNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
        }
        // Check for sunlight (simple version: y > 120 = exposed)
        if (cachedPosition.y > 120) {
            cfStored += CF_PER_TICK;
        }
    }


    @Override
    public long getCFStored() { return cfStored; }

    @Override
    public long getCFCapacity() { return Long.MAX_VALUE; } // Or a specific value if you want a cap

    @Override
    public long extractCF(long maxExtract, boolean simulate) {
        long extracted = Math.min(cfStored, maxExtract);
        if (!simulate) cfStored -= extracted;
        return extracted;
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
                synchronized (SolarPanelState.class) {
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
    public SolarPanelState clone() {
        try {
            return (SolarPanelState) super.clone();
        } catch (Exception e) {
            throw new AssertionError();
        }
    }

    public void generateCF() {
        cfStored += CF_PER_TICK;
    }
}
