package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Map;

import javax.annotation.Nonnull;

import org.Ev0Mods.plugin.api.HopperSlotPolicy;
import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.fluid.FluidCapable;
import com.Ev0sMods.PhosphorTech.fluid.FluidNetwork;
import com.Ev0sMods.PhosphorTech.fluid.FluidTank;
import com.Ev0sMods.PhosphorTech.fluid.FluidType;
import com.Ev0sMods.PhosphorTech.heat.HeatCapable;
import com.Ev0sMods.PhosphorTech.heat.HeatNetwork;
import com.Ev0sMods.PhosphorTech.ui.CokeOvenUIPage;
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
 * ECS component for the Coke Oven block.
 *
 * <p><b>Process:</b> Slot 0 = charcoal input (2 charcoal consumed per cycle).
 * Slot 1 = coal coke output (1 coal coke produced per cycle).
 * Each completed cycle also produces {@link #CREOSOTE_PER_COKE} mB of
 * {@link FluidType#CREOSOTE} into the internal tank, which is then pushed to
 * adjacent fluid-capable blocks every tick.
 *
 * <p>No CF power is required — the Coke Oven runs purely on charcoal fuel.
 */
@SuppressWarnings({"unchecked", "removal"})
public class CokeOvenState
        implements Component<ChunkStore>, TickableBlockState, ItemContainerBlockState,
                   FluidCapable, HopperSlotPolicy {

    // ── Constants ─────────────────────────────────────────────────────────────

    public static final String INPUT_ITEM_ID    = "Ingredient_Charcoal";
    public static final String OUTPUT_ITEM_ID   = "Ingredient_CoalCoke";
    /** Charcoal consumed per coal coke produced. */
    public static final int    CHARCOAL_PER_COKE = 2;
    /** Ticks to process one coal coke (30 seconds at 30 tps). */
    public static final int    TICKS_PER_COKE    = 900;
    /** mB of base creosote produced alongside each coal coke. */
    public static final int    CREOSOTE_PER_COKE = 250;
    /** mB of bonus creosote per adjacent aimed heater/bellows. */
    public static final int    CREOSOTE_PER_HEATER = 25;
    /** Max mB of creosote the internal tank holds. */
    public static final int    CREOSOTE_CAPACITY = 10_000;
    /** mB pushed to adjacent fluid blocks per tick. */
    public static final int    PUSH_PER_TICK     = 100;

    @Override public int[] getHopperProtectedInputSlots()  { return new int[]{0}; }
    @Override public int[] getHopperProtectedOutputSlots() { return new int[]{1}; }

    // ── Component registration ────────────────────────────────────────────────

    public static ComponentType<ChunkStore, CokeOvenState> COMPONENT_TYPE;

    // ── Serialised fields ─────────────────────────────────────────────────────

    public int     processTimer = 0;
    public boolean processing   = false;
    /** Creosote stored in mB. */
    public int     creosoteStored = 0;

    // ── Runtime-only state ────────────────────────────────────────────────────

    public int     uiTick  = 0;
    public boolean uiDirty = false;

    /** Per-state visual animator. */
    private final BlockAnimator animator = new BlockAnimator();

    /** Fluid tank (mirrors {@link #creosoteStored} for rich API). */
    private final FluidTank creosoteTank = new FluidTank(CREOSOTE_CAPACITY);

    // ── Position resolution ───────────────────────────────────────────────────

    volatile boolean removed          = false;
    private Vector3i cachedPosition   = new Vector3i(0, 0, 0);
    private boolean  positionResolved    = false;
    private boolean  registeredInNetwork = false;

    // ── Item container ────────────────────────────────────────────────────────

    /** 2-slot container: slot 0 = charcoal input, slot 1 = coal coke output. */
    private final SimpleItemContainer itemContainer;

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<CokeOvenState> CODEC =
            BuilderCodec.builder(CokeOvenState.class, CokeOvenState::new)
                .append(new KeyedCodec<>("ProcessTimer",   Codec.INTEGER, true),
                        (s, v) -> s.processTimer  = v, s -> s.processTimer).add()
                .append(new KeyedCodec<>("Processing",     Codec.BOOLEAN, true),
                        (s, v) -> s.processing    = v, s -> s.processing).add()
                .append(new KeyedCodec<>("CreosoteStored", Codec.INTEGER, true),
                        (s, v) -> { s.creosoteStored = v; s.syncTank(); },
                        s -> s.creosoteStored).add()
                .build();

    // ── Constructor ───────────────────────────────────────────────────────────

    public CokeOvenState() {
        itemContainer = new SimpleItemContainer((short) 2);
    }

    private CokeOvenState(CokeOvenState other) {
        this();
        this.processTimer   = other.processTimer;
        this.processing     = other.processing;
        this.creosoteStored = other.creosoteStored;
        syncTank();
    }

    /** Sync the FluidTank from {@link #creosoteStored} after deserialization. */
    private void syncTank() {
        // Drain the tank and re-fill to reflect the serialized value
        creosoteTank.extract(FluidType.CREOSOTE, creosoteTank.getCapacity(), false);
        if (creosoteStored > 0) {
            creosoteTank.accept(FluidType.CREOSOTE, creosoteStored, false);
        }
    }

    // ── Component interface ───────────────────────────────────────────────────

    @Override public CokeOvenState clone()    { return new CokeOvenState(this); }
    @Override public WorldChunk   getChunk()  { return null; }
    @Override public Vector3i     getPosition(){ return cachedPosition; }
    @Override public void         invalidate() {
        registeredInNetwork = false;
    }

    public ItemContainer getItemContainer() { return itemContainer; }
    public FluidTank     getCreosoteTank()  { return creosoteTank;  }

    // ── FluidCapable — output only ────────────────────────────────────────────

    @Override public boolean canAcceptFluid(FluidType type) { return false; }
    @Override public int     acceptFluid(FluidType type, int amount, boolean simulate) { return 0; }

    @Override
    public boolean canProvideFluid(FluidType type) {
        return type == FluidType.CREOSOTE && creosoteStored > 0;
    }

    @Override
    public int extractFluid(FluidType type, int amount, boolean simulate) {
        if (type != FluidType.CREOSOTE) return 0;
        int actual = creosoteTank.extract(type, amount, simulate);
        if (!simulate && actual > 0) {
            creosoteStored = creosoteTank.getStored();
            uiDirty = true;
        }
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

        // ── 2. Register in fluid network ──────────────────────────────────────
        if (!registeredInNetwork) {
            FluidNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
        }

        // ── 3. Animation countdown ────────────────────────────────────────────
        animator.tick(world, cachedPosition);

        // ── 4. Processing ─────────────────────────────────────────────────────
        tickProcessing(world);

        // ── 5. Push creosote to adjacent fluid blocks ─────────────────────────
        if (creosoteStored > 0) {
            int moved = FluidNetwork.pushToAdjacent(FluidType.CREOSOTE, cachedPosition, this, PUSH_PER_TICK);
            if (moved > 0) {
                creosoteStored = creosoteTank.getStored();
                uiDirty = true;
            }
        }

        // ── 6. UI refresh ─────────────────────────────────────────────────────
        uiTick++;
        if (CokeOvenUIPage.hasWatcher(cachedPosition) && uiTick >= 20) {
            uiTick  = 0;
            uiDirty = false;
            CokeOvenUIPage.tickRefresh(this, store, cachedPosition);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void tickProcessing(World world) {
        ItemStack inputStack = itemContainer.getItemStack((short) 0);
        boolean hasEnough = inputStack != null && !inputStack.isEmpty()
                && INPUT_ITEM_ID.equals(inputStack.getItemId())
                && inputStack.getQuantity() >= CHARCOAL_PER_COKE;

        if (!hasEnough) {
            if (processing) {
                processing = false; processTimer = 0; uiDirty = true;
                animator.clear(world, cachedPosition);
            }
            return;
        }

        // Check output slot can accept 1 more coal coke
        ItemStack outputStack = itemContainer.getItemStack((short) 1);
        boolean outputBlocked = outputStack != null && !outputStack.isEmpty()
                && (!OUTPUT_ITEM_ID.equals(outputStack.getItemId())
                    || outputStack.getQuantity() >= 99);
        if (outputBlocked) {
            if (processing) {
                processing = false; processTimer = 0; uiDirty = true;
                animator.clear(world, cachedPosition);
            }
            return;
        }

        if (processing) {
            // Stop if all heat sources disappear mid-process
            if (countAimedHeatSources(cachedPosition, true) == 0) {
                processing = false; processTimer = 0; uiDirty = true;
                animator.clear(world, cachedPosition);
                return;
            }
        } else {
            // Require at least one active adjacent heater or bellows aimed at this block
            if (countAimedHeatSources(cachedPosition, true) == 0) return;
            processing = true;
            uiDirty = true;
            animator.setState(world, cachedPosition, BlockAnimator.STATE_WORKING, BlockAnimator.HOLD_INDEFINITE);
        }

        processTimer++;

        if (processTimer >= TICKS_PER_COKE) {
            // Consume 2 charcoal
            itemContainer.removeItemStackFromSlot((short) 0, CHARCOAL_PER_COKE, true, false);

            // Produce 1 coal coke
            ItemStack currentOut = itemContainer.getItemStack((short) 1);
            int existingQty = (currentOut != null && !currentOut.isEmpty()) ? currentOut.getQuantity() : 0;
            itemContainer.setItemStackForSlot((short) 1, new ItemStack(OUTPUT_ITEM_ID, existingQty + 1, null));

            // Produce creosote (base + bonus per aimed heater/bellows)
            int heatSources   = countAimedHeatSources(cachedPosition);
            int totalCreosote = CREOSOTE_PER_COKE + heatSources * CREOSOTE_PER_HEATER;
            int accepted = creosoteTank.accept(FluidType.CREOSOTE, totalCreosote, false);
            if (accepted > 0) creosoteStored = creosoteTank.getStored();

            processing   = false;
            processTimer = 0;
            uiDirty      = true;
            animator.setState(world, cachedPosition, BlockAnimator.STATE_DONE);
            HytaleLogger.getLogger().atFine().log(
                    "[CokeOven %d,%d,%d] Completed: 2x Charcoal -> 1x CoalCoke + %dmB Creosote (%d heaters)",
                    cachedPosition.x, cachedPosition.y, cachedPosition.z, totalCreosote, heatSources);
        }
    }

    // ── Heat-source bonus ──────────────────────────────────────────────────────

    /**
     * Counts adjacent heaters / bellows whose facing direction points at {@code pos}.
     * Each such source adds {@link #CREOSOTE_PER_HEATER} mB to the cycle yield.
     * Pass {@code activeOnly=true} to only count sources that are currently heating/pumping.
     */
    public static int countAimedHeatSources(Vector3i pos) {
        return countAimedHeatSources(pos, false);
    }

    public static int countAimedHeatSources(Vector3i pos, boolean activeOnly) {
        int[] dx = { 1, -1,  0,  0, 0,  0 };
        int[] dy = { 0,  0,  1, -1, 0,  0 };
        int[] dz = { 0,  0,  0,  0, 1, -1 };
        int count = 0;
        for (int i = 0; i < 6; i++) {
            Vector3i neighbor = new Vector3i(pos.x + dx[i], pos.y + dy[i], pos.z + dz[i]);
            String nKey = neighbor.x + "," + neighbor.y + "," + neighbor.z;

            // Check PoweredHeater / MechanicalHeater (both register in HeatNetwork)
            HeatCapable hc = HeatNetwork.getAt(neighbor);
            if (hc instanceof PoweredHeaterState phs) {
                if (!activeOnly || phs.isHeating) count++;
            } else if (hc instanceof MechanicalHeaterState mhs) {
                if (!activeOnly || mhs.isHeating) count++;
            }
            // Check Bellows (uses its own static registry)
            BellowsState bs = BellowsState.REGISTRY.get(nKey);
            if (bs != null) {
                if (!activeOnly || bs.pumpTimer > 0) count++;
            }
        }
        return count;
    }

    // ── Position resolution ────────────────────────────────────────────────────

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
                    cachedPosition    = new Vector3i(wx, wy, wz);
                    positionResolved  = true;
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> entityRefsViaReflection(BlockComponentChunk bcc) {
        try {
            if (!entityRefsMethodResolved) {
                synchronized (CokeOvenState.class) {
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
