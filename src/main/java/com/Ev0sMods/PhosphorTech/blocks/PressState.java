package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.Ev0Mods.plugin.api.HopperSlotPolicy;
import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.compat.VectorCompat;
import com.Ev0sMods.PhosphorTech.recipe.ProcessingRecipe;
import com.Ev0sMods.PhosphorTech.recipe.ProcessingRecipeRegistry;
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
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerBlockState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/**
 * ECS component for the Press block (hand-operated, no power required).
 *
 * <p>Slot 0 = input, slot 1 = output. F-key interaction ({@code OpenPress}).
 */
@SuppressWarnings({"unchecked", "removal"})
public class PressState
        implements Component<ChunkStore>, TickableBlockState, ItemContainerBlockState,
                   HopperSlotPolicy {

    @Override public int[] getHopperProtectedInputSlots() { return new int[]{0}; }

    public static ComponentType<ChunkStore, PressState> COMPONENT_TYPE;

    public static final ConcurrentHashMap<String, PressState> REGISTRY = new ConcurrentHashMap<>();

    public static @Nullable PressState getAt(Vector3i pos) {
        return REGISTRY.get(VectorCompat.posKey(pos));
    }

    // ── Serialised fields ─────────────────────────────────────────────────────

    public int     processTimer = 0;
    public boolean processing   = false;

    // ── Runtime-only state ────────────────────────────────────────────────────

    private final BlockAnimator animator = new BlockAnimator();
    public  int    ticksNeeded  = 40;

    volatile boolean removed          = false;
    private Vector3i cachedPosition   = new Vector3i(0, 0, 0);
    private boolean  positionResolved = false;

    private final SimpleItemContainer itemContainer;

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<PressState> CODEC =
            BuilderCodec.builder(PressState.class, PressState::new)
                .append(new KeyedCodec<>("ProcessTimer", Codec.INTEGER, true),
                        (s, v) -> s.processTimer = v, s -> s.processTimer).add()
                .append(new KeyedCodec<>("Processing",   Codec.BOOLEAN, true),
                        (s, v) -> s.processing = v,   s -> s.processing).add()
                .build();

    // ── Constructor ───────────────────────────────────────────────────────────

    public PressState() {
        itemContainer = new SimpleItemContainer((short) 2);
    }

    private PressState(PressState other) {
        this();
        this.processTimer = other.processTimer;
        this.processing   = other.processing;
    }

    // ── Component interface ───────────────────────────────────────────────────

    @Override public PressState clone()       { return new PressState(this); }
    @Override public WorldChunk  getChunk()   { return null; }
    @Override public Vector3i    getPosition(){ return cachedPosition; }
    @Override public void        invalidate() {
        REGISTRY.remove(VectorCompat.posKey(cachedPosition));
    }

    public ItemContainer getItemContainer() { return itemContainer; }

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
            REGISTRY.put(VectorCompat.posKey(cachedPosition), this);
        }

        animator.tick(world, cachedPosition);
        tickProcessing(world);
    }

    // ── Processing ────────────────────────────────────────────────────────────

    private void tickProcessing(World world) {
        ItemStack inputStack = itemContainer.getItemStack((short) 0);
        if (inputStack == null || inputStack.isEmpty()) {
            if (processing) {
                processing = false; processTimer = 0;
                animator.clear(world, cachedPosition);
            }
            return;
        }

        ProcessingRecipe recipe = ProcessingRecipeRegistry.PRESS.findByInput(inputStack.getItemId());
        if (recipe == null) {
            if (processing) {
                processing = false; processTimer = 0;
                animator.clear(world, cachedPosition);
            }
            return;
        }

        ItemStack outputStack = itemContainer.getItemStack((short) 1);
        boolean outputOccupied = outputStack != null && !outputStack.isEmpty();
        if (outputOccupied) {
            if (!recipe.outputItemId().equals(outputStack.getItemId()) ||
                    outputStack.getQuantity() + recipe.outputQty() > 99) {
                if (processing) {
                    processing = false; processTimer = 0;
                    animator.clear(world, cachedPosition);
                }
                return;
            }
        }

        if (!processing) {
            if (inputStack.getQuantity() < recipe.inputQty()) return;
            processing  = true;
            ticksNeeded = recipe.tickDuration();
            animator.setState(world, cachedPosition, BlockAnimator.STATE_WORKING, BlockAnimator.HOLD_INDEFINITE);
        }

        processTimer++;

        if (processTimer >= ticksNeeded) {
            itemContainer.removeItemStackFromSlot((short) 0, recipe.inputQty(), true, false);
            ItemStack curOut  = itemContainer.getItemStack((short) 1);
            int existQty = (curOut != null && !curOut.isEmpty()) ? curOut.getQuantity() : 0;
            itemContainer.setItemStackForSlot((short) 1,
                    new ItemStack(recipe.outputItemId(), existQty + recipe.outputQty(), null));
            processing   = false;
            processTimer = 0;
            animator.setState(world, cachedPosition, BlockAnimator.STATE_DONE);
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
                synchronized (PressState.class) {
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
