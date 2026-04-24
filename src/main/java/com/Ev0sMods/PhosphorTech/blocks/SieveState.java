package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import org.Ev0Mods.plugin.api.HopperSlotPolicy;
import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.compat.VectorCompat;
import com.Ev0sMods.PhosphorTech.mechanical.GearNetwork;
import com.Ev0sMods.PhosphorTech.mechanical.JouleReceiver;
import com.Ev0sMods.PhosphorTech.mechanical.MechanicalCapable;
import com.Ev0sMods.PhosphorTech.mechanical.SpinningGear;
import com.Ev0sMods.PhosphorTech.recipe.ProcessingRecipe;
import com.Ev0sMods.PhosphorTech.recipe.ProcessingRecipeRegistry;
import com.Ev0sMods.PhosphorTech.ui.SieveUIPage;
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
 * Sieve block — behavior copied from MechanicalGrinder but looks up recipes
 * in {@link ProcessingRecipeRegistry#SIEVE} so JSON recipes can differ.
 */
@SuppressWarnings({"unchecked", "removal"})
public class SieveState implements Component<ChunkStore>, TickableBlockState,
        ItemContainerBlockState, JouleReceiver, MechanicalCapable, SpinningGear, HopperSlotPolicy {

    public static ComponentType<ChunkStore, SieveState> COMPONENT_TYPE;
    public static final ConcurrentHashMap<String, SieveState> REGISTRY = new ConcurrentHashMap<>();

    public double joulesStored = 0.0;
    public int processTimer = 0;
    public boolean processing = false;
    public double currentSpeed = 0.0;

    public int uiTick = 0;
    public boolean uiDirty = false;

    private final SimpleItemContainer itemContainer;
    private static final java.util.Random RANDOM = new java.util.Random();

    // Constants (mirror grinder)
    public static final double J_CAPACITY       = 20.0;
    public static final double MIN_SPEED        = 1.0;
    public static final int    RECEIVE_INTERVAL = 5;
    public static final int    STALL_TICKS      = 10;

    private final BlockAnimator animator = new BlockAnimator();

    private double jRequired   = 0.0;
    public  int    ticksNeeded = 90;
    private int    stallTimer  = 0;

    /** Set by the cleanup system the moment the block is removed -- tick() checks this first. */
    volatile boolean removed = false;

    private Vector3i cachedPosition     = new Vector3i(0, 0, 0);
    private boolean  positionResolved   = false;
    private boolean  registeredInNetwork = false;

    public static final BuilderCodec<SieveState> CODEC =
            BuilderCodec.builder(SieveState.class, SieveState::new)
                .append(new KeyedCodec<>("JoulesStored", Codec.DOUBLE, true), (s, v) -> s.joulesStored = v, s -> s.joulesStored).add()
                .append(new KeyedCodec<>("ProcessTimer", Codec.INTEGER, true), (s, v) -> s.processTimer = v, s -> s.processTimer).add()
                .append(new KeyedCodec<>("Processing", Codec.BOOLEAN, true), (s, v) -> s.processing = v, s -> s.processing).add()
                .build();

    public SieveState() { itemContainer = new SimpleItemContainer((short)6); }
    private SieveState(SieveState o) { this(); this.joulesStored = o.joulesStored; this.processTimer = o.processTimer; this.processing = o.processing; }
    @Override public SieveState clone() { return new SieveState(this); }
    @Override public WorldChunk getChunk() { return null; }
    @Override public Vector3i getPosition() { return cachedPosition; }
    @Override public void invalidate() {
        registeredInNetwork = false;
        REGISTRY.remove(VectorCompat.posKey(cachedPosition));
    }
    public ItemContainer getItemContainer() { return itemContainer; }

    @Override
    public int[] getHopperProtectedInputSlots() { return new int[]{0}; }

    @Override public void onNeighborGearChanged() { }
    @Override public void receiveSpinSignal(double speed) { currentSpeed = speed; stallTimer = 0; uiDirty = true; }
    @Override public double getJoulesStored() { return joulesStored; }
    @Override public double getJoulesCapacity() { return 20.0; }
    @Override public double receiveJoules(double amount, double speed, boolean simulate) {
        double space = getJoulesCapacity() - joulesStored;
        double actual = Math.min(space, amount);
        if (!simulate && actual > 0) { joulesStored += actual; uiDirty = true; }
        return actual;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
                     @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        if (removed) return;

        World world = store.getExternalData().getWorld();
        if (world == null) return;

        if (!positionResolved) {
            probePosition();
            if (!positionResolved) resolvePositionFromStore(store, archetypeChunk.getReferenceTo(index));
            if (!positionResolved) return;
            REGISTRY.put(VectorCompat.posKey(cachedPosition), this);
        }

        if (!registeredInNetwork) { GearNetwork.register(cachedPosition, this); registeredInNetwork = true; }

        stallTimer++;
        if (stallTimer > STALL_TICKS) currentSpeed = 0.0;
        animator.tick(world, cachedPosition);

        // UI refresh
        uiTick++;
        boolean hasWatcher = SieveUIPage.hasWatcher(cachedPosition);
        if (hasWatcher && uiTick >= 20) {
            uiTick = 0; uiDirty = false; SieveUIPage.tickRefresh(this, store, cachedPosition);
        }
        // Processing behavior simplified: reuse ProcessingRecipeRegistry.SIEVE lookup
        ItemStack inputStack = itemContainer.getItemStack((short)0);
        if (inputStack == null || inputStack.isEmpty()) { processing = false; processTimer = 0; return; }
        ProcessingRecipe recipe = ProcessingRecipeRegistry.SIEVE.findByInput(inputStack.getItemId());
        if (recipe == null) { processing = false; processTimer = 0; return; }
        boolean hasPower = currentSpeed >= MIN_SPEED;
        double jCost = recipe.cfCost() / 100.0;
        if (!processing) {
            if (stallTimer % RECEIVE_INTERVAL == 0 && joulesStored > 0) { joulesStored = Math.max(0.0, joulesStored - 2.0); uiDirty = true; }
            if (inputStack.getQuantity() < recipe.inputQty()) return;
            if (!hasPower || joulesStored < jCost) return;
            processing = true; jRequired = jCost; ticksNeeded = recipe.tickDuration(); uiDirty = true; animator.setState(world, cachedPosition, BlockAnimator.STATE_WORKING, BlockAnimator.HOLD_INDEFINITE);
        }
        if (!hasPower) {
            animator.clear(world, cachedPosition);
            if (stallTimer % RECEIVE_INTERVAL == 0 && joulesStored > 0) { joulesStored = Math.max(0.0, joulesStored - 2.0); uiDirty = true; }
            return;
        }
        processTimer++;
        if (processTimer >= recipe.tickDuration()) {
            double consume = Math.min(jRequired, joulesStored);
            joulesStored = Math.max(0, joulesStored - consume);
            itemContainer.removeItemStackFromSlot((short)0, recipe.inputQty(), true, false);
            if (recipe.outputQty() > 0) {
                ItemStack curOut = itemContainer.getItemStack((short)1);
                int existQty = (curOut != null && !curOut.isEmpty()) ? curOut.getQuantity() : 0;
                itemContainer.setItemStackForSlot((short)1, new ItemStack(recipe.outputItemId(), existQty + recipe.outputQty(), null));
            }
            // Bonus outputs (chance-based) — each gets its own dedicated slot (2, 3, 4, 5)
            short bonusSlot = 2;
            for (com.Ev0sMods.PhosphorTech.recipe.ProcessingRecipe.BonusEntry bonus : recipe.allBonuses()) {
                if (RANDOM.nextFloat() < bonus.chance()) {
                    ItemStack cur = itemContainer.getItemStack(bonusSlot);
                    int existing = (cur != null && !cur.isEmpty() && cur.getItemId().equals(bonus.itemId())) ? cur.getQuantity() : 0;
                    itemContainer.setItemStackForSlot(bonusSlot, new ItemStack(bonus.itemId(), existing + bonus.qty(), null));
                }
                bonusSlot++;
            }
            processing = false; processTimer = 0; jRequired = 0; uiDirty = true; animator.setState(world, cachedPosition, BlockAnimator.STATE_DONE);
            HytaleLogger.getLogger().atFine().log(
                    "[Sieve %d,%d,%d] Completed: %s -> %s",
                    cachedPosition.x, cachedPosition.y, cachedPosition.z,
                    recipe.inputItemId(), recipe.outputItemId());
        }
    }

    private void probePosition() {
        try {
            Class<?> sc = getClass().getSuperclass();
            if (sc == null) return;
            for (String name : new String[]{"getBlockPosition","getPosition","getPos","position"}) {
                try {
                    java.lang.reflect.Method m = sc.getMethod(name);
                    Object r = m.invoke(this);
                    if (r instanceof Vector3i v && !(v.x == 0 && v.y == 0 && v.z == 0)) { cachedPosition = v; positionResolved = true; return; }
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
                    positionResolved = true; return;
                }
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> entityRefsViaReflection(BlockComponentChunk bcc) {
        try {
            if (!entityRefsMethodResolved) {
                synchronized (SieveState.class) {
                    if (!entityRefsMethodResolved) {
                        for (java.lang.reflect.Method m : bcc.getClass().getMethods()) {
                            if ("getEntityReferences".equals(m.getName()) && m.getParameterCount() == 0) { m.setAccessible(true); entityRefsMethod = m; break; }
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
