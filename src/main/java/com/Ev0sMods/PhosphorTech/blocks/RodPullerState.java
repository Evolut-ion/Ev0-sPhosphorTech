package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.Ev0Mods.plugin.api.HopperSlotPolicy;
import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.compat.VectorCompat;
import com.Ev0sMods.PhosphorTech.mechanical.GearNetwork;
import com.Ev0sMods.PhosphorTech.mechanical.JouleReceiver;
import com.Ev0sMods.PhosphorTech.mechanical.MechanicalCapable;
import com.Ev0sMods.PhosphorTech.mechanical.SpinningGear;
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
 * ECS component for the Rod Puller block (mechanical rod-making machine).
 *
 * <p>Gear-powered machine that pulls ingots into rods.
 * Slot 0 = input, slot 1 = output.  Uses {@link ProcessingRecipeRegistry#LATHE}
 * (shared with {@link LatheState}).  No UI — F-key interaction only.
 *
 * <p>Iron: 50 J per batch.  Steel: 100 J per batch.
 */
@SuppressWarnings({"unchecked", "removal"})
public class RodPullerState
        implements Component<ChunkStore>, TickableBlockState, ItemContainerBlockState,
                   JouleReceiver, MechanicalCapable, SpinningGear, HopperSlotPolicy {

    @Override public int[] getHopperProtectedInputSlots() { return new int[]{0}; }

    public static final double J_CAPACITY        = 200.0;
    public static final double MIN_SPEED         = 1.0;
    public static final int    RECEIVE_INTERVAL  = 5;
    public static final int    STALL_TICKS       = 10;

    public static ComponentType<ChunkStore, RodPullerState> COMPONENT_TYPE;

    /** Position-keyed registry for F-key interaction lookup. */
    public static final ConcurrentHashMap<String, RodPullerState> REGISTRY = new ConcurrentHashMap<>();

    public static @Nullable RodPullerState getAt(Vector3i pos) {
        return REGISTRY.get(VectorCompat.posKey(pos));
    }

    // ── Serialised fields ─────────────────────────────────────────────────────

    public double  joulesStored  = 0.0;
    public int     processTimer  = 0;
    public boolean processing    = false;
    public double  currentSpeed  = 0.0;

    // ── Runtime-only state ────────────────────────────────────────────────────

    private final BlockAnimator animator = new BlockAnimator();
    private double jRequired    = 0.0;
    public  int    ticksNeeded  = 40;
    private int    stallTimer   = 0;

    volatile boolean removed             = false;
    private Vector3i cachedPosition      = new Vector3i(0, 0, 0);
    private boolean  positionResolved    = false;
    private boolean  registeredInNetwork = false;

    private final SimpleItemContainer itemContainer;

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<RodPullerState> CODEC =
            BuilderCodec.builder(RodPullerState.class, RodPullerState::new)
                .append(new KeyedCodec<>("JoulesStored", Codec.DOUBLE,  true),
                        (s, v) -> s.joulesStored = v,  s -> s.joulesStored).add()
                .append(new KeyedCodec<>("ProcessTimer", Codec.INTEGER, true),
                        (s, v) -> s.processTimer = v,  s -> s.processTimer).add()
                .append(new KeyedCodec<>("Processing",   Codec.BOOLEAN, true),
                        (s, v) -> s.processing   = v,  s -> s.processing).add()
                .build();

    // ── Constructor ───────────────────────────────────────────────────────────

    public RodPullerState() {
        itemContainer = new SimpleItemContainer((short) 2);
    }

    private RodPullerState(RodPullerState o) {
        this();
        this.joulesStored = o.joulesStored;
        this.processTimer = o.processTimer;
        this.processing   = o.processing;
    }

    // ── Component interface ───────────────────────────────────────────────────

    @Override public RodPullerState clone()    { return new RodPullerState(this); }
    @Override public WorldChunk     getChunk() { return null; }
    @Override public Vector3i       getPosition(){ return cachedPosition; }
    @Override public void           invalidate() {
        registeredInNetwork = false;
        REGISTRY.remove(VectorCompat.posKey(cachedPosition));
    }

    public ItemContainer getItemContainer() { return itemContainer; }

    // ── MechanicalCapable / SpinningGear / JouleReceiver ─────────────────────

    @Override public void onNeighborGearChanged() {}

    @Override
    public void receiveSpinSignal(double speed) {
        currentSpeed = speed;
        stallTimer   = 0;
    }

    @Override public double getJoulesStored()   { return joulesStored; }
    @Override public double getJoulesCapacity() { return J_CAPACITY; }

    @Override
    public double receiveJoules(double amount, double speed, boolean simulate) {
        double space  = J_CAPACITY - joulesStored;
        double actual = Math.min(amount, space);
        if (!simulate && actual > 0) joulesStored += actual;
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
            if (!positionResolved) return;
            REGISTRY.put(VectorCompat.posKey(cachedPosition), this);
        }

        if (!registeredInNetwork) {
            GearNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
        }

        stallTimer++;
        if (stallTimer > STALL_TICKS) currentSpeed = 0.0;

        animator.tick(world, cachedPosition);
        tickProcessing(world);
    }

    // ── Processing ────────────────────────────────────────────────────────────

    private void tickProcessing(World world) {
        if (!processing && stallTimer % RECEIVE_INTERVAL == 0 && joulesStored > 0) {
            joulesStored = Math.max(0.0, joulesStored - 2.0);
        }

        ItemStack inputStack = itemContainer.getItemStack((short) 0);
        if (inputStack == null || inputStack.isEmpty()) {
            if (processing) {
                processing = false; processTimer = 0; jRequired = 0;
                animator.clear(world, cachedPosition);
            }
            return;
        }

        ProcessingRecipe recipe = ProcessingRecipeRegistry.LATHE.findByInput(inputStack.getItemId());
        if (recipe == null) {
            if (processing) {
                processing = false; processTimer = 0; jRequired = 0;
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
                    processing = false; processTimer = 0; jRequired = 0;
                    animator.clear(world, cachedPosition);
                }
                return;
            }
        }

        // CF cost in recipe → convert to J (1 J = 100 CF)
        double jCost = recipe.cfCost() / 100.0;
        boolean hasPower = currentSpeed >= MIN_SPEED;

        if (!processing) {
            if (stallTimer % RECEIVE_INTERVAL == 0 && joulesStored > 0) {
                joulesStored = Math.max(0.0, joulesStored - 2.0);
            }
            if (inputStack.getQuantity() < recipe.inputQty()) return;
            if (!hasPower || joulesStored < jCost) return;
            processing  = true;
            jRequired   = jCost;
            ticksNeeded = recipe.tickDuration();
            animator.setState(world, cachedPosition, BlockAnimator.STATE_WORKING, BlockAnimator.HOLD_INDEFINITE);
        }

        if (!hasPower) {
            animator.clear(world, cachedPosition);
            if (stallTimer % RECEIVE_INTERVAL == 0 && joulesStored > 0) {
                joulesStored = Math.max(0.0, joulesStored - 2.0);
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
            itemContainer.setItemStackForSlot((short) 1,
                    new ItemStack(recipe.outputItemId(), existQty + recipe.outputQty(), null));
            processing   = false;
            processTimer = 0;
            jRequired    = 0;
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
                synchronized (RodPullerState.class) {
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
