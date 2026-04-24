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
import com.Ev0sMods.PhosphorTech.fluid.FluidCapable;
import com.Ev0sMods.PhosphorTech.fluid.FluidNetwork;
import com.Ev0sMods.PhosphorTech.fluid.FluidType;

/**
 * ECS component for the Solar Boiler block.
 * Converts water to steam using sunlight, pushes steam downward.
 */
public class SolarBoilerState implements Component<ChunkStore>, TickableBlockState, FluidCapable {

    /** Called when a player interacts with the block. Opens the Solar Boiler UI. */
    public boolean onPlayerInteract(com.hypixel.hytale.server.core.universe.PlayerRef playerRef, com.hypixel.hytale.component.Store<?> store, org.joml.Vector3i pos) {
        com.Ev0sMods.PhosphorTech.ui.SolarBoilerUIPage.openForced(playerRef, null, (Store)store, pos);
        return true;
    }
    public static ComponentType<ChunkStore, SolarBoilerState> COMPONENT_TYPE;
    public static final BuilderCodec<SolarBoilerState> CODEC =
        BuilderCodec.builder(SolarBoilerState.class, SolarBoilerState::new)
            .append(new KeyedCodec<>("WaterMB", Codec.INTEGER, true), (s, v) -> s.waterMB = v, s -> s.waterMB).add()
            .append(new KeyedCodec<>("SteamMB", Codec.INTEGER, true), (s, v) -> s.steamMB = v, s -> s.steamMB).add()
            .build();

    /** Set by the cleanup system the moment the block is removed from the world. */
    volatile boolean removed = false;
    private Vector3i cachedPosition = new Vector3i(0, 0, 0);
    private boolean positionResolved = false;
    private boolean registeredInNetwork = false;
    private int waterMB = 0;
    private int steamMB = 0;
    private int tickCounter = 0;
    private static final int CAPACITY = 4000;
    private static final int WATER_PER_CYCLE = 250;
    private static final int STEAM_PER_CYCLE = 250;
    private static final int GENERATE_INTERVAL = 10; // ticks between conversions

    @Override
    public Vector3i getPosition() { return cachedPosition; }

    @Override
    public WorldChunk getChunk() { return null; }

    @Override
    public void invalidate() { registeredInNetwork = false; }
@Override
    public void tick(float dt, int index, ArchetypeChunk<ChunkStore> archetypeChunk, Store<ChunkStore> store, CommandBuffer<ChunkStore> commandBuffer) {
        if (removed) return;
        if (!positionResolved) {
            probePosition();
            if (!positionResolved) resolvePositionFromStore(store, archetypeChunk.getReferenceTo(index));
            if (!positionResolved) return;
        }
        if (!registeredInNetwork) {
            FluidNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
        }

        // Push steam to adjacent blocks every 5 ticks
        tickCounter++;
        if (tickCounter >= 5) {
            tickCounter = 0;
            if (steamMB > 0) {
                FluidNetwork.pushToAdjacent(FluidType.STEAM, cachedPosition, this, steamMB);
            }
        }

        // Sunlight check: simple version, y > 120 = exposed
        if (cachedPosition.y > 120) {
            // Convert water to steam every GENERATE_INTERVAL ticks
            if (waterMB >= WATER_PER_CYCLE && steamMB + STEAM_PER_CYCLE <= CAPACITY) {
                if (tickCounter % GENERATE_INTERVAL == 0) {
                    waterMB -= WATER_PER_CYCLE;
                    steamMB += STEAM_PER_CYCLE;
                }
            }
        }
    }
    // UI helpers
    public int getWaterMB() { return waterMB; }
    public int getSteamMB() { return steamMB; }
    public int getCapacity() { return CAPACITY; }

    // FluidCapable implementation
    // FluidCapable implementation
    @Override
    public boolean canAcceptFluid(FluidType type) {
        return type != null && type == FluidType.WATER && waterMB < CAPACITY;
    }

    @Override
    public int acceptFluid(FluidType type, int amount, boolean simulate) {
        if (!canAcceptFluid(type)) return 0;
        int accepted = Math.min(amount, CAPACITY - waterMB);
        if (!simulate && accepted > 0) {
            waterMB += accepted;
        }
        return accepted;
    }

    @Override
    public boolean canProvideFluid(FluidType type) {
        return type != null && type == FluidType.STEAM && steamMB > 0;
    }

    @Override
    public int extractFluid(FluidType type, int amount, boolean simulate) {
        if (!canProvideFluid(type)) return 0;
        int extracted = Math.min(amount, steamMB);
        if (!simulate && extracted > 0) {
            steamMB -= extracted;
        }
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
                synchronized (SolarBoilerState.class) {
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

    @Override
    public SolarBoilerState clone() {
        try {
            return (SolarBoilerState) super.clone();
        } catch (Exception e) {
            throw new AssertionError();
        }
    }
}


