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
import com.Ev0sMods.PhosphorTech.ui.MechanicalGrinderUIPage;
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
 * ECS component for the Mechanical Grinder block.
 *
 * <p>Functions identically to the {@link CrusherState} (same slot layout and
 * recipe registry) but is powered by mechanical Joules instead of CF.
 *
 * <p><b>Power requirement:</b> at least 1 J must be delivered every
 * {@value #RECEIVE_INTERVAL} ticks and the connected gear chain must be
 * spinning at speed ≥ 1.  If power is absent for more than
 * {@value #STALL_TICKS} ticks the current job is paused (timer freezes)
 * but not reset — resuming power continues where it left off.
 *
 * <p>Joules are received via {@link #receiveJoules} called by
 * {@link GearNetwork#propagateFrom} / {@link GearNetwork#pushFromProvider}.
 *
 * <p>Slot layout:
 * <ul>
 *   <li>Slot 0 – input</li>
 *   <li>Slot 1 – primary output</li>
 *   <li>Slot 2 – bonus output</li>
 * </ul>
 */
@SuppressWarnings({"unchecked", "removal"})
public class MechanicalGrinderState
        implements Component<ChunkStore>, TickableBlockState, ItemContainerBlockState,
                   JouleReceiver, MechanicalCapable, SpinningGear, HopperSlotPolicy {

    @Override public int[] getHopperProtectedInputSlots() { return new int[]{0}; }

    // ── Constants ─────────────────────────────────────────────────────────────

    public static final double J_CAPACITY       = 20.0;   // buffer
    /** Minimum speed required for processing. */
    public static final double MIN_SPEED        = 1.0;
    /** Receive interval matches 5-tick cadence specification. */
    public static final int    RECEIVE_INTERVAL = 5;
    /** Ticks without power before processing stalls. */
    public static final int    STALL_TICKS      = 10;

    // ── Component registration ────────────────────────────────────────────────

    public static ComponentType<ChunkStore, MechanicalGrinderState> COMPONENT_TYPE;


    /** Position-keyed registry — populated as soon as position is resolved so UIPage can find
     *  the state even before the first GearNetwork registration tick. */
    public static final ConcurrentHashMap<String, MechanicalGrinderState> REGISTRY =
            new ConcurrentHashMap<>();

    // ── Serialised fields ─────────────────────────────────────────────────────

    public double  joulesStored   = 0.0;
    public int     processTimer   = 0;
    public boolean processing     = false;
    /** Current speed of the connected gear chain. */
    public double  currentSpeed   = 0.0;

    // ── Runtime-only state ─────────────────────────────────────────────────────

    public int     uiTick    = 0;
    public boolean uiDirty   = false;

    private final BlockAnimator animator = new BlockAnimator();

    private double jRequired   = 0.0;
    public  int    ticksNeeded = 90;
    private int    stallTimer  = 0;

        /** Set by the cleanup system the moment the block is removed -- tick() checks this first. */
    volatile boolean removed = false;

    private Vector3i cachedPosition     = new Vector3i(0, 0, 0);
    private boolean  positionResolved   = false;
    private boolean  registeredInNetwork = false;

    private final SimpleItemContainer itemContainer;

    private static final java.util.Random RANDOM = new java.util.Random();

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<MechanicalGrinderState> CODEC =
            BuilderCodec.builder(MechanicalGrinderState.class, MechanicalGrinderState::new)
                .append(new KeyedCodec<>("JoulesStored",  Codec.DOUBLE,  true),
                        (s, v) -> s.joulesStored  = v, s -> s.joulesStored).add()
                .append(new KeyedCodec<>("ProcessTimer",  Codec.INTEGER, true),
                        (s, v) -> s.processTimer  = v, s -> s.processTimer).add()
                .append(new KeyedCodec<>("Processing",    Codec.BOOLEAN, true),
                        (s, v) -> s.processing    = v, s -> s.processing).add()
                .build();

    // ── Constructor ───────────────────────────────────────────────────────────

    public MechanicalGrinderState() {
        itemContainer  = new SimpleItemContainer((short) 3);
    }

    private MechanicalGrinderState(MechanicalGrinderState o) {
        this();
        this.joulesStored  = o.joulesStored;
        this.processTimer  = o.processTimer;
        this.processing    = o.processing;
    }

    // ── Component ────────────────────────────────────────────────────────────

    @Override public MechanicalGrinderState clone()    { return new MechanicalGrinderState(this); }
    @Override public WorldChunk               getChunk()   { return null; }
    @Override public Vector3i                 getPosition(){ return cachedPosition; }
    @Override public void                     invalidate() {
        // Network de-registration is handled by NetworkCleanupSystem.
        registeredInNetwork = false;
        REGISTRY.remove(VectorCompat.posKey(cachedPosition));
    }

    public ItemContainer getItemContainer() { return itemContainer; }

    // ── MechanicalCapable ─────────────────────────────────────────────────────

    @Override public void onNeighborGearChanged() { /* grinder has no connection model */ }

    // ── SpinningGear ──────────────────────────────────────────────────────────

    @Override
    public void receiveSpinSignal(double speed) {
        currentSpeed = speed;
        stallTimer   = 0;   // reset stall — gears are actively spinning
        uiDirty      = true;
    }

    // ── JouleReceiver ─────────────────────────────────────────────────────────

    @Override public double getJoulesStored()   { return joulesStored; }
    @Override public double getJoulesCapacity() { return J_CAPACITY; }

    @Override
    public double receiveJoules(double amount, double speed, boolean simulate) {
        double space  = J_CAPACITY - joulesStored;
        double actual = Math.min(amount, space);
        if (!simulate && actual > 0) {
            joulesStored  += actual;
            uiDirty        = true;
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
            // Position just resolved — register in fast-lookup immediately so UI works on first open
            REGISTRY.put(VectorCompat.posKey(cachedPosition), this);
        }

        // ── 2. Register in gear network ───────────────────────────────────────
        if (!registeredInNetwork) {
            GearNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
        }

        // ── 3. Stall counter ──────────────────────────────────────────────────
        stallTimer++;
        if (stallTimer > STALL_TICKS) {
            currentSpeed = 0.0;
        }
        // ── 4. Animation ──────────────────────────────────────────────────────
        animator.tick(world, cachedPosition);

        // ── 5. Processing ─────────────────────────────────────────────────────
        tickProcessing(world);

        // ── 6. UI refresh ─────────────────────────────────────────────────────
        uiTick++;
        boolean hasWatcher = MechanicalGrinderUIPage.hasWatcher(cachedPosition);
        if (hasWatcher && uiTick >= 20) {
            uiTick  = 0;
            uiDirty = false;
            MechanicalGrinderUIPage.tickRefresh(this, store, cachedPosition);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void tickProcessing(World world) {
        // Drain stored Joules while idle/standby even if there's no input present.
        if (!processing && stallTimer % RECEIVE_INTERVAL == 0 && joulesStored > 0) {
            joulesStored = Math.max(0.0, joulesStored - 2.0);
            uiDirty = true;
        }

        ItemStack inputStack = itemContainer.getItemStack((short) 0);
        if (inputStack == null || inputStack.isEmpty()) {
            if (processing) {
                processing = false; processTimer = 0; jRequired = 0; uiDirty = true;
                animator.clear(world, cachedPosition);
            }
            return;
        }

        ProcessingRecipe recipe = ProcessingRecipeRegistry.CRUSHER.findByInput(inputStack.getItemId());
        if (recipe == null) {
            if (processing) {
                processing = false; processTimer = 0; jRequired = 0; uiDirty = true;
                animator.clear(world, cachedPosition);
            }
            return;
        }

        // Block if output slot is full or has a different item
        ItemStack outputStack = itemContainer.getItemStack((short) 1);
        boolean outputOccupied = outputStack != null && !outputStack.isEmpty();
        if (outputOccupied) {
            if (!recipe.outputItemId().equals(outputStack.getItemId()) ||
                    outputStack.getQuantity() + recipe.outputQty() > 99) {
                if (processing) {
                    processing = false; processTimer = 0; jRequired = 0; uiDirty = true;
                    animator.clear(world, cachedPosition);
                }
                return;
            }
        }

        // Require speed ≥ 1 and enough Joules to process
        boolean hasPower = currentSpeed >= MIN_SPEED;
        // Convert CF cost to Joules (1 J = 100 CF)
        double jCost = recipe.cfCost() / 100.0;

        if (!processing) {
            // If idle/standby, slowly bleed stored Joules as well (2 J per RECEIVE_INTERVAL).
            if (stallTimer % RECEIVE_INTERVAL == 0 && joulesStored > 0) {
                joulesStored = Math.max(0.0, joulesStored - 2.0);
                uiDirty = true;
            }
            // If no power, insufficient Joules, or not enough input quantity, cannot start.
            if (inputStack.getQuantity() < recipe.inputQty()) return;
            if (!hasPower || joulesStored < jCost) return;
            processing  = true;
            jRequired   = jCost;
            ticksNeeded = recipe.tickDuration();
            uiDirty     = true;
            animator.setState(world, cachedPosition, BlockAnimator.STATE_WORKING, BlockAnimator.HOLD_INDEFINITE);
        }

        // Stall if power lost mid-process. While stalled the grinder
        // slowly bleeds stored Joules: 2 J per RECEIVE_INTERVAL ticks.
        if (!hasPower) {
            animator.clear(world, cachedPosition);
            if (stallTimer % RECEIVE_INTERVAL == 0 && joulesStored > 0) {
                joulesStored = Math.max(0.0, joulesStored - 2.0);
                uiDirty = true;
            }
            return;
        }

        processTimer++;

        if (processTimer >= ticksNeeded) {
            double consume = Math.min(jRequired, joulesStored);
            joulesStored = Math.max(0, joulesStored - consume);
            itemContainer.removeItemStackFromSlot((short) 0, recipe.inputQty(), true, false);
            ItemStack curOut = itemContainer.getItemStack((short) 1);
            int existQty = (curOut != null && !curOut.isEmpty()) ? curOut.getQuantity() : 0;
            ItemStack outputItem = new ItemStack(recipe.outputItemId(), existQty + recipe.outputQty(), null);
            itemContainer.setItemStackForSlot((short) 1, outputItem);
            // Bonus outputs (chance-based)
            short bonusSlot = 2;
            for (com.Ev0sMods.PhosphorTech.recipe.ProcessingRecipe.BonusEntry bonus : recipe.allBonuses()) {
                if (RANDOM.nextFloat() < bonus.chance()) {
                    ItemStack cur = itemContainer.getItemStack(bonusSlot);
                    int existing = (cur != null && !cur.isEmpty() && cur.getItemId().equals(bonus.itemId())) ? cur.getQuantity() : 0;
                    itemContainer.setItemStackForSlot(bonusSlot, new ItemStack(bonus.itemId(), existing + bonus.qty(), null));
                }
                bonusSlot++;
            }
            processing   = false;
            processTimer = 0;
            jRequired    = 0;
            uiDirty      = true;
            animator.setState(world, cachedPosition, BlockAnimator.STATE_DONE);
            HytaleLogger.getLogger().atFine().log(
                    "[MechanicalGrinder %d,%d,%d] Completed: %s -> %s",
                    cachedPosition.x, cachedPosition.y, cachedPosition.z,
                    recipe.inputItemId(), recipe.outputItemId());
        }
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
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> entityRefsViaReflection(BlockComponentChunk bcc) {
        try {
            if (!entityRefsMethodResolved) {
                synchronized (MechanicalGrinderState.class) {
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
