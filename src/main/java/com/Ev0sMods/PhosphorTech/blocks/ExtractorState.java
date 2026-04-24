package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Map;

import javax.annotation.Nonnull;

import org.Ev0Mods.plugin.api.HopperSlotPolicy;
import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxNetwork;
import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxReceiver;
import com.Ev0sMods.PhosphorTech.recipe.ProcessingRecipe;
import com.Ev0sMods.PhosphorTech.recipe.ProcessingRecipeRegistry;
import com.Ev0sMods.PhosphorTech.ui.ExtractorUIPage;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
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
 * ECS component for the Extractor block.
 *
 * <p>Slot 0 = input, slot 1 = output (output-only).
 * Recipes looked up from {@link ProcessingRecipeRegistry#EXTRACTOR}.
 */
@SuppressWarnings({"unchecked", "removal"})
public class ExtractorState
        implements Component<ChunkStore>, TickableBlockState, ItemContainerBlockState,
                   CrystallineFluxReceiver, HopperSlotPolicy {

    public static ComponentType<ChunkStore, ExtractorState> COMPONENT_TYPE;

    @Override public int[] getHopperProtectedInputSlots() { return new int[]{0}; }

    public static final long CF_CAPACITY = 10_000L;

    public long cfStored      = 0L;
    public int  processTimer  = 0;
    public boolean processing = false;

    public int     uiTick  = 0;
    public boolean uiDirty = false;

    // ── Animation ─────────────────────────────────────────────────────────────

    /** Per-state visual animator — drives "Working" / "Done" / "Off" block states. */
    private final BlockAnimator animator = new BlockAnimator();

    private int cfRequired  = 0;
    public int ticksNeeded = 90;

        /** Set by the cleanup system the moment the block is removed -- tick() checks this first. */
    volatile boolean removed = false;

    private Vector3i cachedPosition      = new Vector3i(0, 0, 0);
    private boolean  positionResolved    = false;
    private boolean  registeredInNetwork = false;

    /** 2-slot container: slot 0 = input, slot 1 = output. */
    private final SimpleItemContainer itemContainer;

    public static final BuilderCodec<ExtractorState> CODEC =
            BuilderCodec.builder(ExtractorState.class, ExtractorState::new)
                .append(new KeyedCodec<>("CfStored",     Codec.LONG,    true),
                        (s, v) -> s.cfStored = v,     s -> s.cfStored).add()
                .append(new KeyedCodec<>("ProcessTimer", Codec.INTEGER, true),
                        (s, v) -> s.processTimer = v, s -> s.processTimer).add()
                .append(new KeyedCodec<>("Processing",   Codec.BOOLEAN, true),
                        (s, v) -> s.processing = v,   s -> s.processing).add()
                .build();

    public ExtractorState() {
        itemContainer = new SimpleItemContainer((short) 2);
    }

    private ExtractorState(ExtractorState other) {
        this();
        this.cfStored     = other.cfStored;
        this.processTimer = other.processTimer;
        this.processing   = other.processing;
    }

    @Override public ExtractorState clone()     { return new ExtractorState(this); }
    @Override public WorldChunk     getChunk()  { return null; }
    @Override public Vector3i       getPosition(){ return cachedPosition; }
    @Override public void           invalidate() {
        // Network de-registration is handled by NetworkCleanupSystem.
        registeredInNetwork = false;
    }

    public ItemContainer getItemContainer() { return itemContainer; }

    @Override public long getCFStored()   { return cfStored; }
    @Override public long getCFCapacity() { return CF_CAPACITY; }

    @Override
    public long receiveCF(long amount, boolean simulate) {
        long space  = CF_CAPACITY - cfStored;
        long actual = Math.min(amount, space);
        if (!simulate && actual > 0) { cfStored += actual; uiDirty = true; }
        return actual;
    }

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
            CrystallineFluxNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
        }

        // ── Animation hold countdown ──────────────────────────────────────────
        animator.tick(world, cachedPosition);

        tickProcessing(world);

        uiTick++;
        boolean hasWatcher = ExtractorUIPage.hasWatcher(cachedPosition);
        if (hasWatcher && uiTick >= 20) {
            uiTick  = 0;
            uiDirty = false;
            ExtractorUIPage.tickRefresh(this, store, cachedPosition);
        }
    }

    private void tickProcessing(World world) {
        ItemStack inputStack = itemContainer.getItemStack((short) 0);
        if (inputStack == null || inputStack.isEmpty()) {
            if (processing) {
                processing = false; processTimer = 0; cfRequired = 0; uiDirty = true;
                animator.clear(world, cachedPosition);
            }
            return;
        }
        ProcessingRecipe recipe = ProcessingRecipeRegistry.EXTRACTOR.findByInput(inputStack.getItemId());
        if (recipe == null) {
            if (processing) {
                processing = false; processTimer = 0; cfRequired = 0; uiDirty = true;
                animator.clear(world, cachedPosition);
            }
            return;
        }
        ItemStack outputStack = itemContainer.getItemStack((short) 1);
        boolean outputOccupied = outputStack != null && !outputStack.isEmpty();
        if (outputOccupied) {
            if (!recipe.outputItemId().equals(outputStack.getItemId()) || outputStack.getQuantity() + recipe.outputQty() > 99) {
                if (processing) {
                    processing = false; processTimer = 0; cfRequired = 0; uiDirty = true;
                    animator.clear(world, cachedPosition);
                }
                return;
            }
        }
        if (!processing) {
            if (inputStack.getQuantity() < recipe.inputQty()) return;
            if (cfStored < recipe.cfCost()) return;
            processing  = true;
            cfRequired  = recipe.cfCost();
            ticksNeeded = recipe.tickDuration();
            uiDirty     = true;
            animator.setState(world, cachedPosition, BlockAnimator.STATE_WORKING, BlockAnimator.HOLD_INDEFINITE);
        }
        processTimer++;
        if (processTimer >= ticksNeeded) {
            long consume = Math.min(cfRequired, cfStored);
            cfStored    -= consume;
            itemContainer.removeItemStackFromSlot((short) 0, recipe.inputQty(), true, false);
            ItemStack currentOut = itemContainer.getItemStack((short) 1);
            int existingQty = (currentOut != null && !currentOut.isEmpty()) ? currentOut.getQuantity() : 0;
            ItemStack outputItem = new ItemStack(recipe.outputItemId(), existingQty + recipe.outputQty(), null);
            itemContainer.setItemStackForSlot((short) 1, outputItem, false);
            processing   = false;
            processTimer = 0;
            cfRequired   = 0;
            uiDirty      = true;
            animator.setState(world, cachedPosition, BlockAnimator.STATE_DONE);
            HytaleLogger.getLogger().atFine().log(
                    "[Extractor %d,%d,%d] Completed: %s -> %s",
                    cachedPosition.x, cachedPosition.y, cachedPosition.z,
                    recipe.inputItemId(), recipe.outputItemId());
        }
    }

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
                synchronized (ExtractorState.class) {
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
