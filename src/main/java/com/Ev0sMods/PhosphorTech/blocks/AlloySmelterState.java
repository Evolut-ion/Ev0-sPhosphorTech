package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Map;

import javax.annotation.Nonnull;

import org.Ev0Mods.plugin.api.HopperSlotPolicy;
import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxNetwork;
import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxReceiver;
import com.Ev0sMods.PhosphorTech.recipe.AlloySmelterRecipe;
import com.Ev0sMods.PhosphorTech.recipe.AlloySmelterRecipeRegistry;
import com.Ev0sMods.PhosphorTech.ui.AlloySmelterUIPage;
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
 * ECS component for the Alloy Smelter block.
 *
 * <p>Slots:
 * <ul>
 *   <li>Slot 0 – Input 1 (hopper push-in only)</li>
 *   <li>Slot 1 – Input 2 (hopper push-in only)</li>
 *   <li>Slot 2 – Output 1 (hopper pull-out only)</li>
 *   <li>Slot 3 – Output 2 (hopper pull-out only)</li>
 * </ul>
 *
 * <p>Looks up the recipe via {@link AlloySmelterRecipeRegistry#INSTANCE}.
 * Consumes CF ({@link #CF_CAPACITY} max) over the recipe's {@code tickDuration}.
 */
@SuppressWarnings({"unchecked", "removal"})
public class AlloySmelterState
        implements Component<ChunkStore>, TickableBlockState, ItemContainerBlockState,
                   CrystallineFluxReceiver, HopperSlotPolicy {

    @Override public int[] getHopperProtectedInputSlots() { return new int[]{0, 1}; }

    // ── Constants ─────────────────────────────────────────────────────────────

    public static final long CF_CAPACITY = 10_000L;

    // ── Component registration ────────────────────────────────────────────────

    public static ComponentType<ChunkStore, AlloySmelterState> COMPONENT_TYPE;

    // ── Serialised fields ─────────────────────────────────────────────────────

    public long    cfStored     = 0L;
    public int     processTimer = 0;
    public boolean processing   = false;

    // ── Runtime-only state ────────────────────────────────────────────────────

    public int     uiTick  = 0;
    public boolean uiDirty = false;

    // ── Animation ─────────────────────────────────────────────────────────────

    private final BlockAnimator animator = new BlockAnimator();

    private int cfRequired  = 0;
    public  int ticksNeeded = 120;

    // ── Position resolution ───────────────────────────────────────────────────

        /** Set by the cleanup system the moment the block is removed -- tick() checks this first. */
    volatile boolean removed = false;

    private Vector3i cachedPosition      = new Vector3i(0, 0, 0);
    private boolean  positionResolved    = false;
    private boolean  registeredInNetwork = false;

    // ── Item containers ───────────────────────────────────────────────────────

    /** 4-slot container (slot 0/1 = inputs, slot 2/3 = outputs). */
    private final SimpleItemContainer itemContainer;

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<AlloySmelterState> CODEC =
            BuilderCodec.builder(AlloySmelterState.class, AlloySmelterState::new)
                .append(new KeyedCodec<>("CfStored",     Codec.LONG,    true),
                        (s, v) -> s.cfStored = v,     s -> s.cfStored).add()
                .append(new KeyedCodec<>("ProcessTimer", Codec.INTEGER, true),
                        (s, v) -> s.processTimer = v, s -> s.processTimer).add()
                .append(new KeyedCodec<>("Processing",   Codec.BOOLEAN, true),
                        (s, v) -> s.processing = v,   s -> s.processing).add()
                .build();

    // ── Constructor ───────────────────────────────────────────────────────────

    public AlloySmelterState() {
        itemContainer = new SimpleItemContainer((short) 4);
    }

    private AlloySmelterState(AlloySmelterState o) {
        this();
        this.cfStored     = o.cfStored;
        this.processTimer = o.processTimer;
        this.processing   = o.processing;
    }

    // ── Component ────────────────────────────────────────────────────────────

    @Override public AlloySmelterState clone()     { return new AlloySmelterState(this); }
    @Override public WorldChunk         getChunk() { return null; }
    @Override public Vector3i           getPosition() { return cachedPosition; }
    @Override public void               invalidate()  { registeredInNetwork = false; }

    /** Container for UI and hopper access. */
    public ItemContainer getItemContainer() { return itemContainer; }

    // ── CrystallineFluxReceiver ───────────────────────────────────────────────

    @Override public long getCFStored()   { return cfStored; }
    @Override public long getCFCapacity() { return CF_CAPACITY; }

    @Override
    public long receiveCF(long amount, boolean simulate) {
        long space  = CF_CAPACITY - cfStored;
        long actual = Math.min(amount, space);
        if (!simulate && actual > 0) { cfStored += actual; uiDirty = true; }
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
            if (!positionResolved) {
                HytaleLogger.getLogger().atWarning().log(
                        "[AlloySmelter] Position NOT resolved (tick skipped)");
                return;
            }
            HytaleLogger.getLogger().atInfo().log(
                    "[AlloySmelter] Position resolved: %d,%d,%d",
                    cachedPosition.x, cachedPosition.y, cachedPosition.z);
        }

        if (!registeredInNetwork) {
            CrystallineFluxNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
            HytaleLogger.getLogger().atInfo().log(
                    "[AlloySmelter] Registered in CF network at %d,%d,%d",
                    cachedPosition.x, cachedPosition.y, cachedPosition.z);
        }

        animator.tick(world, cachedPosition);

        tickProcessing(world);

        // ── Diagnostic: log CF level every ~2 seconds (40 ticks) ──────────────
        uiTick++;
        if (uiTick % 40 == 0) {
            HytaleLogger.getLogger().atInfo().log(
                    "[AlloySmelter %d,%d,%d] cfStored=%d  processing=%s",
                    cachedPosition.x, cachedPosition.y, cachedPosition.z,
                    cfStored, processing);
        }
        boolean hasWatcher = AlloySmelterUIPage.hasWatcher(cachedPosition);
        if (hasWatcher && uiTick >= 20) {
            uiTick  = 0;
            uiDirty = false;
            AlloySmelterUIPage.tickRefresh(this, store, cachedPosition);
        }
    }

    // ── Processing logic ──────────────────────────────────────────────────────

    private void tickProcessing(World world) {
        // ── Require both input slots filled ──────────────────────────────────
        ItemStack in1 = itemContainer.getItemStack((short) 0);
        ItemStack in2 = itemContainer.getItemStack((short) 1);
        if (in1 == null || in1.isEmpty() || in2 == null || in2.isEmpty()) {
            if (processing) stopProcessing(world);
            return;
        }

        // ── Recipe lookup ─────────────────────────────────────────────────────
        AlloySmelterRecipe recipe = AlloySmelterRecipeRegistry.INSTANCE
                .findByInputs(in1.getItemId(), in2.getItemId());
        if (recipe == null) {
            if (processing) stopProcessing(world);
            return;
        }

        // ── Check output space ────────────────────────────────────────────────
        if (!canFitOutput(recipe)) {
            if (processing) stopProcessing(world);
            return;
        }

        // ── Start or continue processing ──────────────────────────────────────
        if (!processing) {
            if (cfStored < recipe.cfCost()) return;
            processing  = true;
            cfRequired  = recipe.cfCost();
            ticksNeeded = recipe.tickDuration();
            uiDirty     = true;
            animator.setState(world, cachedPosition, BlockAnimator.STATE_WORKING, BlockAnimator.HOLD_INDEFINITE);
        }

        processTimer++;

        if (processTimer >= ticksNeeded) {
            // ── Complete craft ────────────────────────────────────────────────
            long consume = Math.min(cfRequired, cfStored);
            cfStored -= consume;

            // Remove one of each input
            itemContainer.removeItemStackFromSlot((short) 0, 1, true, false);
            itemContainer.removeItemStackFromSlot((short) 1, 1, true, false);

            // Place primary output
            placeOutput((short) 2, recipe.output1ItemId(), recipe.output1Qty());

            // Place secondary output (if any)
            if (recipe.hasSecondOutput()) {
                placeOutput((short) 3, recipe.output2ItemId(), recipe.output2Qty());
            }

            processing   = false;
            processTimer = 0;
            cfRequired   = 0;
            uiDirty      = true;
            animator.setState(world, cachedPosition, BlockAnimator.STATE_DONE, 40);
        }
    }

    private boolean canFitOutput(AlloySmelterRecipe recipe) {
        if (!canFitInSlot((short) 2, recipe.output1ItemId(), recipe.output1Qty())) return false;
        if (recipe.hasSecondOutput()) {
            if (!canFitInSlot((short) 3, recipe.output2ItemId(), recipe.output2Qty())) return false;
        }
        return true;
    }

    private boolean canFitInSlot(short slot, String itemId, int qty) {
        ItemStack existing = itemContainer.getItemStack(slot);
        if (existing == null || existing.isEmpty()) return true;
        return existing.getItemId().equals(itemId) && existing.getQuantity() + qty <= 99;
    }

    private void placeOutput(short slot, String itemId, int qty) {
        ItemStack existing = itemContainer.getItemStack(slot);
        int existQty = (existing != null && !existing.isEmpty()) ? existing.getQuantity() : 0;
        ItemStack out = new ItemStack(itemId, existQty + qty, null);
        itemContainer.setItemStackForSlot(slot, out);
    }

    private void stopProcessing(World world) {
        processing   = false;
        processTimer = 0;
        cfRequired   = 0;
        uiDirty      = true;
        animator.clear(world, cachedPosition);
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
        } catch (Throwable t) {
            HytaleLogger.getLogger().atWarning()
                    .log("[AlloySmelterState] resolvePosition failed: " + t.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> entityRefsViaReflection(BlockComponentChunk bcc) {
        try {
            if (!entityRefsMethodResolved) {
                synchronized (AlloySmelterState.class) {
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
