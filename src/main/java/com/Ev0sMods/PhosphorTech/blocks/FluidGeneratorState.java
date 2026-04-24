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
import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxNetwork;
import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxProvider;
import com.Ev0sMods.PhosphorTech.ui.FluidGeneratorUIPage;

/**
 * ECS component for the Fluid Generator block.
 * Consumes fluid (e.g., lava, creosote) to generate Crystalline Flux (CF).
 */
public class FluidGeneratorState implements Component<ChunkStore>, TickableBlockState, FluidCapable, CrystallineFluxProvider {

    /** Called when a player interacts with the block. Opens the Fluid Generator UI. */
    public boolean onPlayerInteract(com.hypixel.hytale.server.core.universe.PlayerRef playerRef, com.hypixel.hytale.component.Store<?> store, org.joml.Vector3i pos) {
        com.Ev0sMods.PhosphorTech.ui.FluidGeneratorUIPage.openForced(playerRef, null, store, pos);
        return true;
    }
    public static ComponentType<ChunkStore, FluidGeneratorState> COMPONENT_TYPE;
    public static final BuilderCodec<FluidGeneratorState> CODEC =
        BuilderCodec.builder(FluidGeneratorState.class, FluidGeneratorState::new)
            .append(new KeyedCodec<>("CFStored", Codec.LONG, true), (s, v) -> s.cfStored = v, s -> s.cfStored).add()
            .append(new KeyedCodec<>("FluidType", Codec.STRING, true), (s, v) -> s.fluidTypeName = v == null ? "" : v, s -> s.fluidTypeName).add()
            .append(new KeyedCodec<>("FluidMB", Codec.INTEGER, true), (s, v) -> s.fluidMB = v, s -> s.fluidMB).add()
            .build();

    private Vector3i cachedPosition = new Vector3i(0, 0, 0);
    private boolean positionResolved = false;
    private boolean registeredInNetwork = false;
    private long cfStored = 0;
    private String fluidTypeName = "";
    private int fluidMB = 0;
    private static final long LAVA_CF_PER_1000 = 1000;
    private static final long CREOSOTE_CF_PER_100 = 250;
    private static final int CAPACITY = 4000;
    /** CF generated in the most recent tick (for UI display). */
    private long cfGenThisTick = 0;
    private int uiTick = 0;

    @Override
    public Vector3i getPosition() { return cachedPosition; }

    @Override
    public WorldChunk getChunk() { return null; }

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
            FluidNetwork.register(cachedPosition, this);
            CrystallineFluxNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
        }
        // Prioritize Lava
        cfGenThisTick = 0;
        if (fluidTypeName.equals("LAVA") && fluidMB >= 1000) {
            fluidMB -= 1000;
            cfStored += LAVA_CF_PER_1000;
            cfGenThisTick = LAVA_CF_PER_1000;
        } else if (fluidTypeName.equals("CREOSOTE") && fluidMB >= 100) {
            fluidMB -= 100;
            cfStored += CREOSOTE_CF_PER_100;
            cfGenThisTick = CREOSOTE_CF_PER_100;
        }
        // If tank is empty, clear fluid type
        if (fluidMB <= 0) fluidTypeName = "";
        // Push CF into the wire network
        if (cfStored > 0) {
            CrystallineFluxNetwork.pushFromProvider(cachedPosition, this);
        }
        // UI refresh
        if (FluidGeneratorUIPage.hasWatcher(cachedPosition)) {
            uiTick++;
            if (uiTick >= 20) {
                uiTick = 0;
                FluidGeneratorUIPage.tickRefresh(this, store, cachedPosition);
            }
        }
    }

    // FluidCapable implementation
    // FluidCapable implementation
    @Override
    public boolean canAcceptFluid(FluidType type) {
        return type != null && (fluidTypeName.isEmpty() || fluidTypeName.equals(type.name())) && fluidMB < CAPACITY;
    }

    @Override
    public int acceptFluid(FluidType type, int amount, boolean simulate) {
        if (!canAcceptFluid(type)) return 0;
        int accepted = Math.min(amount, CAPACITY - fluidMB);
        if (!simulate && accepted > 0) {
            if (fluidTypeName.isEmpty()) fluidTypeName = type.name();
            fluidMB += accepted;
        }
        return accepted;
    }

    @Override
    public boolean canProvideFluid(FluidType type) {
        return type != null && !fluidTypeName.isEmpty() && fluidTypeName.equals(type.name()) && fluidMB > 0;
    }

    @Override
    public int extractFluid(FluidType type, int amount, boolean simulate) {
        if (!canProvideFluid(type)) return 0;
        int extracted = Math.min(amount, fluidMB);
        if (!simulate && extracted > 0) {
            fluidMB -= extracted;
            if (fluidMB <= 0) fluidTypeName = "";
        }
        return extracted;
    }

    // CrystallineFluxProvider implementation
    @Override
    public long extractCF(long maxExtract, boolean simulate) {
        long extracted = Math.min(cfStored, maxExtract);
        if (!simulate) cfStored -= extracted;
        return extracted;
    }

    @Override
    public long getCFStored() { return cfStored; }

    @Override
    public long getCFCapacity() { return Long.MAX_VALUE; } // Or set a specific cap if needed

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
                synchronized (FluidGeneratorState.class) {
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
    public FluidGeneratorState clone() {
        try {
            return (FluidGeneratorState) super.clone();
        } catch (Exception e) {
            throw new AssertionError();
        }
    }

    public int getFluidMB() { return fluidMB; }
    public String getFluidTypeName() { return fluidTypeName; }
    public long getCfGenThisTick() { return cfGenThisTick; }
}


