package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3i;

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
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerBlockState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/**
 * ECS component for the Brick Form block.
 *
 * <p>Accepts up to 4 {@link #CLAY_ITEM_ID} or {@link #MIX_ITEM_ID}, one item at
 * a time via F-key interaction.  Once all 4 are loaded, a {@link #PROCESS_TICKS}
 * tick timer begins.  Extraction is locked during processing.  On completion the
 * inputs are replaced with 4× unfired bricks.
 */
@SuppressWarnings({"unchecked", "removal"})
public class BrickFormState
        implements Component<ChunkStore>, TickableBlockState, ItemContainerBlockState {

    // ── Item ID constants ─────────────────────────────────────────────────────

    public static final String CLAY_ITEM_ID        = "Soil_Clay";
    public static final String MIX_ITEM_ID         = "Ingredient_CokeBrickMix";
    public static final String UNFIRED_CLAY_ITEM_ID = "Ingredient_UnfiredClayBrick";
    public static final String UNFIRED_COKE_ITEM_ID = "Ingredient_UnfiredCokeBrick";

    /** Number of input items required before processing begins. */
    public static final int REQUIRED_QUANTITY = 4;

    /** Ticks required to process 4 inputs into unfired bricks. */
    public static final int PROCESS_TICKS = 60;

    // ── Component registration ────────────────────────────────────────────────

    public static ComponentType<ChunkStore, BrickFormState> COMPONENT_TYPE;
    // ── Position registry (for interaction lookup) ──────────────────────

    private static final ConcurrentHashMap<String, BrickFormState> REGISTRY = new ConcurrentHashMap<>();

    public static @Nullable BrickFormState getAt(Vector3i pos) {
        return REGISTRY.get(pos.x + "," + pos.y + "," + pos.z);
    }

    public static void unregisterAt(Vector3i pos) {
        REGISTRY.remove(pos.x + "," + pos.y + "," + pos.z);
    }
    // ── Codec — no persistent numeric fields beyond the item container ─────────

    public static final BuilderCodec<BrickFormState> CODEC =
            BuilderCodec.builder(BrickFormState.class, BrickFormState::new)
                .build();

    // ── Runtime-only state ────────────────────────────────────────────────────

    volatile boolean removed          = false;
    private Vector3i cachedPosition   = new Vector3i(0, 0, 0);
    private boolean  positionResolved = false;

    // ── Item container ────────────────────────────────────────────────────────

    /** 1-slot container: slot 0 = clay or coke-brick-mix input. */
    private final SimpleItemContainer itemContainer;

    // ── Processing state ──────────────────────────────────────────────────────

    /**
     * -1 = idle (< 4 items or not yet started).
     *  0-59 = processing (extraction locked).
     *  60 = done (ready to extract).
     */
    public volatile int processingTick = -1;

    // ── Constructor ───────────────────────────────────────────────────────────

    public BrickFormState() {
        itemContainer = new SimpleItemContainer((short) 1);
    }

    private BrickFormState(BrickFormState other) {
        itemContainer = new SimpleItemContainer((short) 1);
    }

    // ── Helpers for interaction ───────────────────────────────────────────────

    /** Whether a player may insert one more item. */
    public boolean canInsert() {
        if (processingTick >= 0) return false; // processing or done — no insert
        ItemStack s = itemContainer.getItemStack((short) 0);
        int qty = (s != null && !s.isEmpty()) ? s.getQuantity() : 0;
        return qty < REQUIRED_QUANTITY;
    }

    /** Whether a player may take items out. */
    public boolean canExtract() {
        // Allow take-back when idle (< 4 inserted), locked during processing, allow at done
        if (processingTick > 0 && processingTick < PROCESS_TICKS) return false;
        ItemStack s = itemContainer.getItemStack((short) 0);
        return s != null && !s.isEmpty();
    }

    /** Number of items currently in the slot (0-4). */
    public int fillCount() {
        ItemStack s = itemContainer.getItemStack((short) 0);
        return (s != null && !s.isEmpty()) ? s.getQuantity() : 0;
    }

    // ── Component interface ───────────────────────────────────────────────────

    @Override public BrickFormState clone()      { return new BrickFormState(this); }
    public           WorldChunk     getChunk()   { return null; }
    @Override public Vector3i       getPosition(){ return cachedPosition; }
    @Override public void           invalidate() {}

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

        // ── 1. Resolve position ───────────────────────────────────────────────
        if (!positionResolved) {
            probePosition();
            if (!positionResolved) resolvePositionFromStore(store, archetypeChunk.getReferenceTo(index));
            if (!positionResolved) return;
            REGISTRY.put(cachedPosition.x + "," + cachedPosition.y + "," + cachedPosition.z, this);
        }

        // ── 2. Processing ─────────────────────────────────────────────────────
        tickProcessing(world);
    }

    // ── Processing ────────────────────────────────────────────────────────────

    private void tickProcessing(World world) {
        ItemStack slotStack = itemContainer.getItemStack((short) 0);

        // If slot is empty or output already placed, nothing to do
        if (processingTick >= PROCESS_TICKS) {
            // Done — waiting for player to extract; update visual to Stage4
            if (slotStack != null && !slotStack.isEmpty()) {
                String id = slotStack.getItemId();
                boolean isClay = UNFIRED_CLAY_ITEM_ID.equals(id);
                applyVisualState(world, isClay ? "Stage4Clay" : "Stage4Coke");
            }
            return;
        }

        if (slotStack == null || slotStack.isEmpty()) {
            // Slot cleared after extraction — reset
            if (processingTick != -1) {
                processingTick = -1;
                applyVisualState(world, "Empty");
            }
            return;
        }

        String itemId = slotStack.getItemId();
        boolean isClay = CLAY_ITEM_ID.equals(itemId);
        boolean isMix  = MIX_ITEM_ID.equals(itemId);
        int qty = slotStack.getQuantity();

        if (!isClay && !isMix) return; // unknown item

        if (processingTick < 0) {
            // Idle — update visual to match fill count, start processing when full
            String stage = stageNameForCount(qty, isClay);
            applyVisualState(world, stage);

            if (qty >= REQUIRED_QUANTITY && isWaterlogged(world)) {
                // All 4 inserted and block is waterlogged — begin processing
                processingTick = 0;
            }
            return;
        }

        // Active processing (0 … PROCESS_TICKS-1) — only advance while waterlogged
        if (!isWaterlogged(world)) return;
        processingTick++;

        // Stage during processing = Stage3 (form full, shaping)
        applyVisualState(world, isClay ? "Stage3Clay" : "Stage3Coke");

        if (processingTick >= PROCESS_TICKS) {
            // Transform: remove inputs, place outputs
            String outputId = isClay ? UNFIRED_CLAY_ITEM_ID : UNFIRED_COKE_ITEM_ID;
            itemContainer.removeItemStackFromSlot((short) 0, REQUIRED_QUANTITY, true, false);
            itemContainer.setItemStackForSlot((short) 0, new ItemStack(outputId, REQUIRED_QUANTITY, null));
            applyVisualState(world, isClay ? "Stage4Clay" : "Stage4Coke");
        }
    }

    private static String stageNameForCount(int qty, boolean isClay) {
        String suffix = isClay ? "Clay" : "Coke";
        return switch (qty) {
            case 1 -> "Stage0" + suffix;
            case 2 -> "Stage1" + suffix;
            case 3 -> "Stage2" + suffix;
            default -> "Stage3" + suffix; // 4+
        };
    }

    /** Returns true if the brick form's own block position contains any fluid (is waterlogged). */
    private boolean isWaterlogged(World world) {
        try {
            int x = cachedPosition.x, y = cachedPosition.y, z = cachedPosition.z;
            WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z));
            if (chunk == null) return false;
            Store<ChunkStore> fluidStore = world.getChunkStore().getStore();
            ChunkColumn column = fluidStore.getComponent(chunk.getReference(), ChunkColumn.getComponentType());
            if (column == null) return false;
            Ref<ChunkStore> sectionRef = column.getSection(ChunkUtil.chunkCoordinate(y));
            if (sectionRef == null) return false;
            FluidSection fluidSection = fluidStore.getComponent(sectionRef, FluidSection.getComponentType());
            if (fluidSection == null) return false;
            return fluidSection.getFluidId(x, y, z) > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Called externally (e.g. from the interaction) to reset the visual to Empty immediately. */
    public void resetVisual(World world) {
        applyVisualState(world, "Empty");
    }

    private void applyVisualState(World world, String stateId) {
        try {
            com.Ev0sMods.PhosphorTech.blocks.BlockAnimator.applyBlockState(world, cachedPosition, stateId);
        } catch (Throwable ignored) {}
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
                synchronized (BrickFormState.class) {
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
