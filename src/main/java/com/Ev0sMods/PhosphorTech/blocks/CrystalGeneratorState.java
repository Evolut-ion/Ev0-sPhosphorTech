package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Map;

import javax.annotation.Nonnull;

import org.Ev0Mods.plugin.api.HopperSlotPolicy;
import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxNetwork;
import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxProvider;
import com.Ev0sMods.PhosphorTech.fluid.FluidCapable;
import com.Ev0sMods.PhosphorTech.fluid.FluidNetwork;
import com.Ev0sMods.PhosphorTech.fluid.FluidType;
import com.Ev0sMods.PhosphorTech.heat.HeatCapable;
import com.Ev0sMods.PhosphorTech.ui.CrystalGeneratorUIPage;
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
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.inventory.container.filter.SlotFilter;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerBlockState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/**
 * ECS component for the Crystal Generator block.
 *
 * <p><b>Energy model:</b>
 * <ul>
 *   <li>Slot 0 &mdash; Crystal input (resource type: Crystals). Filtered by
 *       item ID containing "crystal".</li>
 *   <li>Slot 1 &mdash; Water bucket input.  Each bucket consumed fills
 *       {@code waterMB} by 1 000 mB (max {@value #WATER_MAX_MB} mB).</li>
 *   <li>Every {@value #GENERATE_INTERVAL} ticks the machine attempts to
 *       consume 1 crystal + {@value #WATER_PER_CYCLE} mB.  While resources
 *       were consumed it generates {@value #CF_PER_TICK} CF every tick for
 *       the next {@value #GENERATE_INTERVAL} ticks.</li>
 *   <li>Produced CF is first stored internally (cap {@value #CF_MAX_STORED})
 *       then pushed to adjacent {@link CrystallineFluxNetwork} receivers.</li>
 * </ul>
 *
 * <p>TODO(JOML-migration): {@link Vector3i} field types change to
 * {@code org.joml.Vector3i}; field-access (.x/.y/.z) is identical.
 */
@SuppressWarnings({"unchecked", "removal"})
public class CrystalGeneratorState
        implements Component<ChunkStore>, TickableBlockState, ItemContainerBlockState,
                   CrystallineFluxProvider, FluidCapable, HopperSlotPolicy {

    @Override public int[] getHopperProtectedInputSlots() { return new int[]{0, 1}; }

    // ── Constants ─────────────────────────────────────────────────────────────

    /** CF generated every server tick while the machine is active. */
    public static final int CF_PER_TICK = 256;
    /** Maximum CF the internal buffer can hold. */
    public static final int CF_MAX_STORED = 1_000_000;
    /** Maximum steam the input tank can hold, in milli-buckets. */
    public static final int STEAM_MAX_MB = 10_000;
    /** Steam consumed per generation cycle, in milli-buckets. */
    public static final int STEAM_PER_CYCLE = 100;
    /** Maximum water the output tank can hold, in milli-buckets. */
    public static final int WATER_MAX_MB = 10_000;
    /** Water produced per generation cycle, in milli-buckets. */
    public static final int WATER_PER_CYCLE = 50;
    /** Ticks per generation cycle (resource consumption + CF production period). */
    public static final int GENERATE_INTERVAL = 30;

    // ── Component registration ────────────────────────────────────────────────

    /** Set during plugin setup via {@code csr.registerComponent(...)}. */
    public static ComponentType<ChunkStore, CrystalGeneratorState> COMPONENT_TYPE;

    // ── Serialised fields (included in CODEC) ─────────────────────────────────

    /** Incoming steam tank, in milli-buckets. Range: 0 – {@value #STEAM_MAX_MB}. */
    public int steamMB  = 0;
    /** Output water tank, in milli-buckets. Range: 0 – {@value #WATER_MAX_MB}. */
    public int waterMB  = 0;
    /** Stored CF in the internal buffer. */
    public int cfStored = 0;

    // ── Runtime-only state (NOT serialised) ───────────────────────────────────

    /** Temperature of the last batch of steam accepted, in °C. Used to set water exit temp. */
    public double steamInletHeat = HeatCapable.AMBIENT_CELSIUS;

    /** True while the current 30-tick generation window is active. */
    public boolean isGenerating = false;
    /** Ticks since last resource consumption attempt. Resets every {@value #GENERATE_INTERVAL} ticks. */
    public int generateTimer = 0;
    /** Throttle for periodic UI refresh. */
    public int uiTick = 0;
    /** Set to push an immediate UI update on the next tick. */
    public boolean uiDirty = false;

    // ── Animation ─────────────────────────────────────────────────────────────

    /** Per-state visual animator — drives "Active" / "Off" block states. */
    private final BlockAnimator animator = new BlockAnimator();

    // ── Position resolution (same pattern as FertilizerState) ─────────────────

        /** Set by the cleanup system the moment the block is removed -- tick() checks this first. */
    volatile boolean removed = false;

    private Vector3i cachedPosition = new Vector3i(0, 0, 0);
    private boolean positionResolved = false;
    private boolean registeredInNetwork = false;

    // ── Static capability checks ──────────────────────────────────────────────

    /** True when HyUI is present on the server at runtime. */
    static final boolean HYUI_PRESENT;
    static {
        boolean hyui = false;
        try { Class.forName("au.ellie.hyui.builders.PageBuilder", true, Thread.currentThread().getContextClassLoader()); hyui = true; }
        catch (ClassNotFoundException ignored) {}
        HYUI_PRESENT = hyui;
    }

    // ── Item container ────────────────────────────────────────────────────────

    private final SimpleItemContainer itemContainer;

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<CrystalGeneratorState> CODEC =
            BuilderCodec.builder(CrystalGeneratorState.class, CrystalGeneratorState::new)
                .append(new KeyedCodec<>("SteamMB",   Codec.INTEGER, true),
                        (s, v) -> s.steamMB  = v, s -> s.steamMB).add()
                .append(new KeyedCodec<>("WaterOutMB", Codec.INTEGER, true),
                        (s, v) -> s.waterMB  = v, s -> s.waterMB).add()
                .append(new KeyedCodec<>("CFStored",   Codec.INTEGER, true),
                        (s, v) -> s.cfStored = v, s -> s.cfStored).add()
                .build();

    // ── Constructor ───────────────────────────────────────────────────────────

    public CrystalGeneratorState() {
        itemContainer = new SimpleItemContainer((short) 1);

        // Slot 0: crystal input — hoppers can insert crystals but cannot extract
        itemContainer.setSlotFilter(FilterActionType.ADD, (short) 0,
                (type, cont, slot, stack) -> stack != null && isCrystal(stack.getItemId()));
        itemContainer.setSlotFilter(FilterActionType.REMOVE, (short) 0, SlotFilter.DENY);
    }

    private CrystalGeneratorState(CrystalGeneratorState other) {
        this();
        this.steamMB  = other.steamMB;
        this.waterMB  = other.waterMB;
        this.cfStored = other.cfStored;
    }

    // ── Item predicates ───────────────────────────────────────────────────────

    /** Accepts any item whose ID (case-insensitive) contains "crystal". */
    public static boolean isCrystal(String id) {
        return id != null && id.toLowerCase().contains("crystal");
    }

    // ── Component interface ───────────────────────────────────────────────────

    @Override public CrystalGeneratorState clone()       { return new CrystalGeneratorState(this); }
    @Override public WorldChunk              getChunk()  { return null; }
    @Override public Vector3i               getPosition(){ return cachedPosition; }
    @Override public void                   invalidate() {
        // Network de-registration is handled by NetworkCleanupSystem.
        registeredInNetwork = false;
    }

    public ItemContainer getItemContainer() { return itemContainer; }

    // ── CrystallineFluxProvider ───────────────────────────────────────────────

    @Override public long getCFStored()   { return cfStored; }
    @Override public long getCFCapacity() { return CF_MAX_STORED; }

    @Override
    public long extractCF(long amount, boolean simulate) {
        long actual = Math.min(amount, cfStored);
        if (!simulate) cfStored -= (int) actual;
        return actual;
    }

    // ── FluidCapable ──────────────────────────────────────────────────────────

    /** Accepts steam into the input tank. */
    @Override
    public boolean canAcceptFluid(FluidType type) {
        return type == FluidType.STEAM && steamMB < STEAM_MAX_MB;
    }

    @Override
    public int acceptFluid(FluidType type, int amount, boolean simulate) {
        return acceptFluidAt(type, amount, simulate, HeatCapable.AMBIENT_CELSIUS);
    }

    public int acceptFluidAt(FluidType type, int amount, boolean simulate, double inletHeat) {
        if (type != FluidType.STEAM) return 0;
        int space  = STEAM_MAX_MB - steamMB;
        int actual = Math.min(amount, space);
        if (!simulate && actual > 0) {
            steamMB += actual;
            steamInletHeat = inletHeat;
            uiDirty = true;
        }
        return actual;
    }

    /** Provides water from the output tank via the fluid network (pipes). Steam is input-only. */
    @Override
    public boolean canProvideFluid(FluidType type) {
        return type == FluidType.WATER && waterMB > 0;
    }

    @Override
    public boolean canProvideFluidTo(FluidType type, Vector3i toPos) {
        return type == FluidType.WATER && waterMB > 0;
    }

    @Override
    public int extractFluid(FluidType type, int amount, boolean simulate) {
        if (type != FluidType.WATER) return 0;
        int actual = Math.min(amount, waterMB);
        if (!simulate && actual > 0) { waterMB -= actual; uiDirty = true; }
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

        // ── 2. Register in CF + fluid networks once position is known ──────────
        if (!registeredInNetwork) {
            CrystallineFluxNetwork.register(cachedPosition, this);
            FluidNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
        }
        // ── Animation hold countdown ──────────────────────────────────────────
        animator.tick(world, cachedPosition);
        // ── 3. Generation cycle (every GENERATE_INTERVAL ticks) ───────────────
        generateTimer++;
        if (generateTimer >= GENERATE_INTERVAL) {
            generateTimer = 0;
            tryConsumeResources();
        }

        // ── 4. Add CF to buffer if generating ────────────────────
        if (isGenerating) {
            cfStored = Math.min(cfStored + CF_PER_TICK, CF_MAX_STORED);
        }

        // ── 5. Push CF to adjacent network nodes ─────────────────
        if (cfStored > 0) {
            CrystallineFluxNetwork.pushFromProvider(cachedPosition, this);
        }

        // ── 5b. Push water into adjacent pipes ─────────────────────────────────
        // Water is always condensed at 100°C regardless of inlet steam temperature.
        if (waterMB > 0) {
            FluidNetwork.pushToAdjacentWithHeat(FluidType.WATER, cachedPosition, this, waterMB, 100.0);
        }

        // ── 6. Visual state update ──────────────────────────────────────────────
        if (isGenerating) {
            animator.setState(world, cachedPosition, BlockAnimator.STATE_ACTIVE, BlockAnimator.HOLD_INDEFINITE);
        } else {
            animator.clear(world, cachedPosition);
        }

        // ── 7. UI refresh ─────────────────────────────────────────
        uiTick++;
        boolean hasWatcher = CrystalGeneratorUIPage.hasWatcher(cachedPosition);
        if (hasWatcher) {
            if (uiDirty || uiTick >= 20) {
                uiTick = 0;
                uiDirty = false;
                CrystalGeneratorUIPage.tickRefresh(this, store, cachedPosition);
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Attempts to consume 1 crystal (slot 0) + {@value #STEAM_PER_CYCLE} mB of steam.
     * Produces {@value #WATER_PER_CYCLE} mB of water + CF.
     * Sets {@link #isGenerating} accordingly.
     */
    private void tryConsumeResources() {
        ItemStack crystal  = itemContainer.getItemStack((short) 0);
        boolean hasCrystal = crystal != null && !crystal.isEmpty() && isCrystal(crystal.getItemId());
        boolean hasSteam   = steamMB >= STEAM_PER_CYCLE;
        boolean hasWaterRoom = waterMB + WATER_PER_CYCLE <= WATER_MAX_MB;

        if (hasCrystal && hasSteam && hasWaterRoom) {
            itemContainer.removeItemStackFromSlot((short) 0, 1, true, false);
            steamMB  -= STEAM_PER_CYCLE;
            waterMB  += WATER_PER_CYCLE;
            isGenerating = true;
            uiDirty = true;
            HytaleLogger.getLogger().atFine().log(
                    "[CrystalGenerator %d,%d,%d] Consumed crystal+steam, generating CF/tick=%d",
                    cachedPosition.x, cachedPosition.y, cachedPosition.z, CF_PER_TICK);
        } else {
            if (isGenerating) uiDirty = true;
            isGenerating = false;
        }
    }

    // ── Position resolution (mirrors FertilizerState) ────────────────────────

    private void probePosition() {
        try {
            Class<?> sc = getClass().getSuperclass();
            if (sc == null) return;
            for (String name : new String[]{"getBlockPosition", "getPosition", "getPos", "position"}) {
                try {
                    java.lang.reflect.Method m = sc.getMethod(name);
                    Object r = m.invoke(this);
                    if (r instanceof Vector3i v && !(v.x == 0 && v.y == 0 && v.z == 0)) {
                        cachedPosition = v;
                        positionResolved = true;
                        return;
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
                synchronized (CrystalGeneratorState.class) {
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

    // ── UI helper ─────────────────────────────────────────────────────────────

    /** Progress 0-100 of steam input tank. */
    public int steamPct()     { return (int)(100L * steamMB / STEAM_MAX_MB); }
    /** Progress 0-100 of water output tank. */
    public int waterPct()     { return (int)(100L * waterMB / WATER_MAX_MB); }
    /** Progress 0-100 of CF buffer fill. */
    public int cfPct()        { return (int)(100L * cfStored / CF_MAX_STORED); }
    /** Ticks until next resource consumption attempt. */
    public int ticksUntilNext() { return Math.max(0, GENERATE_INTERVAL - generateTimer); }
    /** Human-readable steam input level. */
    public String steamLabel() { return steamMB + " / " + STEAM_MAX_MB + " mB"; }
    /** Human-readable water output level. */
    public String waterLabel() { return waterMB + " / " + WATER_MAX_MB + " mB"; }
    /** Human-readable CF buffer. */
    public String cfLabel()    { return cfStored + " / " + CF_MAX_STORED + " CF"; }
}
