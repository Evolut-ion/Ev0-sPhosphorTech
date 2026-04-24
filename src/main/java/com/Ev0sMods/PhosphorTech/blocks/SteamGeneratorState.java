package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Map;

import javax.annotation.Nonnull;

import org.Ev0Mods.plugin.api.HopperSlotPolicy;
import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.fluid.FluidCapable;
import com.Ev0sMods.PhosphorTech.fluid.FluidNetwork;
import com.Ev0sMods.PhosphorTech.fluid.FluidType;
import com.Ev0sMods.PhosphorTech.heat.HeatCapable;
import com.Ev0sMods.PhosphorTech.heat.HeatNetwork;
import com.Ev0sMods.PhosphorTech.ui.SteamGeneratorUIPage;
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
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerBlockState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/**
 * ECS component for the Steam Generator block.
 *
 * <p><b>Process:</b>
 * <ul>
 *   <li>Slot 0 &mdash; Crystal input (item ID containing "crystal").</li>
 *   <li>Water fluid input via adjacent pipes / fluid network.</li>
 *   <li>Every {@value #GENERATE_INTERVAL} ticks: consumes 1 crystal +
 *       {@value #WATER_PER_CYCLE} mB water &rarr; produces
 *       {@value #STEAM_PER_CYCLE} mB steam.</li>
 *   <li>Steam is pushed to adjacent {@link FluidNetwork} receivers every 5 ticks.</li>
 * </ul>
 */
@SuppressWarnings({"unchecked", "removal"})
public class SteamGeneratorState
        implements Component<ChunkStore>, TickableBlockState, ItemContainerBlockState,
                   FluidCapable, HeatCapable, HopperSlotPolicy {

    @Override public int[] getHopperProtectedInputSlots() { return new int[]{0, 1}; }

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Minimum temperature (°C) required to convert water into steam. */
    public static final double HEAT_THRESHOLD = 100.0;
    /** Maximum temperature the steam generator can hold (°C), used for UI scaling. */
    public static final double HEAT_MAX_CELSIUS = 500.0;

    /** Maximum water the input tank can hold, in milli-buckets. */
    public static final int WATER_MAX_MB     = 10_000;
    /** Water consumed per generation cycle, in milli-buckets. */
    public static final int WATER_PER_CYCLE  = 500;
    /** Maximum steam the output tank can hold, in milli-buckets. */
    public static final int STEAM_MAX_MB     = 10_000;
    /** Steam produced per generation cycle, in milli-buckets. */
    public static final int STEAM_PER_CYCLE  = 1_000;
    /** Ticks per generation cycle. */
    public static final int GENERATE_INTERVAL = 30;

    // ── Component registration ────────────────────────────────────────────────

    public static ComponentType<ChunkStore, SteamGeneratorState> COMPONENT_TYPE;

    // ── Serialised fields ─────────────────────────────────────────────────────

    /** Water input tank, in milli-buckets. Range: 0 – {@value #WATER_MAX_MB}. */
    public int waterMB = 0;
    /** Steam output tank, in milli-buckets. Range: 0 – {@value #STEAM_MAX_MB}. */
    public int steamMB = 0;
    /** Current heat level in °C; block must reach {@value #HEAT_THRESHOLD}°C to generate steam. */
    public double heatCelsius = HeatCapable.AMBIENT_CELSIUS;

    // ── Runtime-only state ────────────────────────────────────────────────────

    public boolean isGenerating   = false;
    public int     generateTimer  = 0;
    public int     inputCheckTimer = 0;
    public int     uiTick         = 0;
    public boolean uiDirty        = false;

    // ── Animation ─────────────────────────────────────────────────────────────

    // ── Water-level visual state ───────────────────────────────────────────────

    private static final String[] WATER_STATES = {
        "Water_Empty", "Water_Low", "Water_Mid", "Water_High", "Water_Full"
    };

    /** Last applied water-level index (0-4); -1 forces sync on first tick. */
    private int lastAppliedWaterLevel = -1;

    // ── Position resolution ───────────────────────────────────────────────────

        /** Set by the cleanup system the moment the block is removed -- tick() checks this first. */
    volatile boolean removed = false;

    private Vector3i cachedPosition      = new Vector3i(0, 0, 0);
    private boolean  positionResolved    = false;
    private boolean  registeredInNetwork = false;
    private boolean  registeredAsProvider = false;

    // ── Item container ────────────────────────────────────────────────────────

    private final SimpleItemContainer itemContainer;

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<SteamGeneratorState> CODEC =
            BuilderCodec.builder(SteamGeneratorState.class, SteamGeneratorState::new)
                .append(new KeyedCodec<>("WaterInMB",  Codec.INTEGER, true),
                        (s, v) -> s.waterMB = v, s -> s.waterMB).add()
                .append(new KeyedCodec<>("SteamOutMB", Codec.INTEGER, true),
                        (s, v) -> s.steamMB = v, s -> s.steamMB).add()
                .append(new KeyedCodec<>("HeatCelsius", Codec.DOUBLE, true),
                        (s, v) -> s.heatCelsius = v, s -> s.heatCelsius).add()
                .build();

    // ── Constructor ───────────────────────────────────────────────────────────

    public SteamGeneratorState() {
        itemContainer = new SimpleItemContainer((short) 2);

        // Slot 0: any crystal item
        itemContainer.setSlotFilter(FilterActionType.ADD, (short) 0,
                (type, cont, slot, stack) -> stack != null && isCrystal(stack.getItemId()));

        // Slot 1: water bucket input — hoppers push filled water buckets in here
        itemContainer.setSlotFilter(FilterActionType.ADD, (short) 1,
                (type, cont, slot, stack) -> stack != null && isWaterBucket(stack.getItemId()));
    }

    private SteamGeneratorState(SteamGeneratorState other) {
        this();
        this.waterMB = other.waterMB;
        this.steamMB = other.steamMB;
        this.heatCelsius = other.heatCelsius;
    }

    // ── Item predicates ───────────────────────────────────────────────────────

    public static boolean isCrystal(String id) {
        return id != null && id.toLowerCase().contains("crystal");
    }

    public static boolean isWaterBucket(String id) {
        if (id == null) return false;
        String norm = id.startsWith("*") ? id.substring(1) : id;
        return norm.equals("Container_Bucket_State_Filled_Water");
    }

    // ── Component interface ───────────────────────────────────────────────────

    @Override public SteamGeneratorState clone()      { return new SteamGeneratorState(this); }
    @Override public WorldChunk           getChunk()  { return null; }
    @Override public Vector3i            getPosition(){ return cachedPosition; }
    @Override public void                invalidate() {
        // Network de-registration is handled by NetworkCleanupSystem.
        if (registeredAsProvider && positionResolved) {
            HeatNetwork.unregisterProvider(cachedPosition);
        }
        registeredInNetwork   = false;
        registeredAsProvider  = false;
    }

    public ItemContainer getItemContainer() { return itemContainer; }

    // ── HeatCapable ───────────────────────────────────────────────────────────

    @Override public double getHeat()            { return heatCelsius; }
    @Override public double getMaxHeat()         { return HEAT_MAX_CELSIUS; }
    @Override public void   setHeat(double c)    {
        heatCelsius = Math.max(HeatCapable.AMBIENT_CELSIUS, Math.min(HEAT_MAX_CELSIUS, c));
        uiDirty = true;
    }

    // ── FluidCapable ──────────────────────────────────────────────────────────

    /** Accepts water into the input tank. */
    @Override
    public boolean canAcceptFluid(FluidType type) {
        return type == FluidType.WATER && waterMB < WATER_MAX_MB;
    }

    @Override
    public int acceptFluid(FluidType type, int amount, boolean simulate) {
        if (type != FluidType.WATER) return 0;
        int space  = WATER_MAX_MB - waterMB;
        int actual = Math.min(amount, space);
        if (!simulate && actual > 0) { waterMB += actual; uiDirty = true; }
        return actual;
    }

    /** Provides steam from the output tank. */
    @Override
    public boolean canProvideFluid(FluidType type) {
        return type == FluidType.STEAM && steamMB > 0;
    }

    @Override
    public int extractFluid(FluidType type, int amount, boolean simulate) {
        if (type != FluidType.STEAM) return 0;
        int actual = Math.min(amount, steamMB);
        if (!simulate && actual > 0) { steamMB -= actual; uiDirty = true; }
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

        // ── 2. Register in fluid/heat networks ─────────────────────────────────
        if (!registeredInNetwork) {
            FluidNetwork.register(cachedPosition, this);
            HeatNetwork.register(cachedPosition, this);
            HeatNetwork.registerProvider(cachedPosition);
            registeredAsProvider = true;
            registeredInNetwork = true;
        }

        // ── Visual state sync ─────────────────────────────────────────────────
        syncWaterLevelState(world);

        // ── 3. Push output steam + propagate heat to adjacent blocks (every 5t) ─
        inputCheckTimer++;
        if (inputCheckTimer >= 5) {
            inputCheckTimer = 0;
            if (steamMB > 0) {
                // Steam exits at generator temperature (always ≥ 100°C by construction).
                FluidNetwork.pushToAdjacentWithHeat(FluidType.STEAM, cachedPosition, this, steamMB, heatCelsius);
            }
            if (waterMB > 0) {
                // Water exits as water. If the generator is above 100°C the water is at
                // the phase-change boundary (100°C); below that it carries the actual temp.
                double waterExitTemp = Math.min(heatCelsius, HEAT_THRESHOLD);
                FluidNetwork.pushToAdjacentWithHeat(FluidType.WATER, cachedPosition, this, waterMB, waterExitTemp);
            }
            // Drain any filled water bucket sitting in slot 1
            tryConsumeWaterBucket();
        }

        // ── 3b. Passive heat cooling ──────────────────────────────────────────
        HeatNetwork.tickCooling(cachedPosition);

        // ── 3c. Push heat to connected pipes whenever heat is above ambient ──────
        if (heatCelsius > HeatCapable.AMBIENT_CELSIUS) {
            HeatNetwork.pushHeat(cachedPosition, heatCelsius);
        }

        // ── 4. Generation cycle (every GENERATE_INTERVAL ticks) ───────────────
        generateTimer++;
        if (generateTimer >= GENERATE_INTERVAL) {
            generateTimer = 0;
            tryConsumeResources(world);
        }

        // ── 5. UI refresh ─────────────────────────────────────────────────────
        uiTick++;
        boolean hasWatcher = SteamGeneratorUIPage.hasWatcher(cachedPosition);
        if (hasWatcher) {
            if (uiDirty || uiTick >= 20) {
                uiTick = 0;
                uiDirty = false;
                SteamGeneratorUIPage.tickRefresh(this, store, cachedPosition);
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Consumes a filled water bucket from slot 1, adds 1000 mB water, leaves an empty bucket. */
    private void tryConsumeWaterBucket() {
        if (waterMB >= WATER_MAX_MB) return;
        ItemStack bucketSlot = itemContainer.getItemStack((short) 1);
        if (bucketSlot == null || bucketSlot.isEmpty()) return;
        if (!isWaterBucket(bucketSlot.getItemId())) return;
        itemContainer.removeItemStackFromSlot((short) 1, 1, true, false);
        waterMB = Math.min(waterMB + 1_000, WATER_MAX_MB);
        uiDirty = true;
    }

    private void tryConsumeResources(World world) {
        ItemStack crystal    = itemContainer.getItemStack((short) 0);
        boolean hasCrystal   = crystal != null && !crystal.isEmpty() && isCrystal(crystal.getItemId());
        boolean hasWater     = waterMB >= WATER_PER_CYCLE;
        boolean hasHeat      = heatCelsius >= HEAT_THRESHOLD;
        // Multiplier: floor(heat / 100), so 100°C=1x, 200°C=2x, 300°C=3x, etc.
        int heatMultiplier   = hasHeat ? Math.max(1, (int)(heatCelsius / 100)) : 0;
        int steamThisCycle   = STEAM_PER_CYCLE * heatMultiplier;
        boolean hasSteamRoom = steamMB + steamThisCycle <= STEAM_MAX_MB;

        if (hasCrystal && hasWater) {
            // Always consume inputs when resources are available
            itemContainer.removeItemStackFromSlot((short) 0, 1, true, false);
            waterMB -= WATER_PER_CYCLE;
            // Each crystal burned adds 50°C of heat
            heatCelsius = Math.min(HEAT_MAX_CELSIUS, heatCelsius + 50.0);
            uiDirty = true;

            // Only produce steam once the heat threshold is met and there is tank space
            if (hasHeat && hasSteamRoom) {
                steamMB += steamThisCycle;
                isGenerating = true;
                HytaleLogger.getLogger().atFine().log(
                        "[SteamGenerator %d,%d,%d] Consumed crystal+water, produced %d mB steam (x%d heat mult)",
                        cachedPosition.x, cachedPosition.y, cachedPosition.z, steamThisCycle, heatMultiplier);
            } else {
                // Consuming but not yet hot enough (or tank full) — mark as active but no output
                isGenerating = false;
            }
        } else {
            if (isGenerating) uiDirty = true;
            isGenerating = false;
        }

        // Water-level visual is synced at top of tick(); nothing extra needed here.
    }

    // ── Water-level visual helpers ────────────────────────────────────────────

    /** Returns 0 (empty) … 4 (full) based on current {@link #waterMB}. */
    private int getWaterLevelIndex() {
        double pct = (double) waterMB / WATER_MAX_MB;
        if (pct <= 0.0)    return 0;
        if (pct < 0.375)   return 1;
        if (pct < 0.625)   return 2;
        if (pct < 0.875)   return 3;
        return 4;
    }

    /** Applies the matching water-level block state, skipping if already current. */
    private void syncWaterLevelState(World world) {
        int level = getWaterLevelIndex();
        if (level == lastAppliedWaterLevel) return;
        lastAppliedWaterLevel = level;
        BlockAnimator.applyBlockState(world, cachedPosition, WATER_STATES[level]);
    }

    // ── Position resolution (mirrors CrystalGeneratorState) ──────────────────

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
                synchronized (SteamGeneratorState.class) {
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

    // ── UI helpers ────────────────────────────────────────────────────────────

    public int waterPct()       { return (int)(100L * waterMB / WATER_MAX_MB); }
    public int steamPct()       { return (int)(100L * steamMB / STEAM_MAX_MB); }
    public int heatPct()        { return (int)(100.0 * Math.max(0, heatCelsius - HeatCapable.AMBIENT_CELSIUS) / (HEAT_MAX_CELSIUS - HeatCapable.AMBIENT_CELSIUS)); }
    public int ticksUntilNext() { return Math.max(0, GENERATE_INTERVAL - generateTimer); }
    public String waterLabel()  { return waterMB + " / " + WATER_MAX_MB + " mB"; }
    public String steamLabel()  { return steamMB + " / " + STEAM_MAX_MB + " mB"; }
    public String heatLabel()   { return String.format("%.1f / %.0f °C", heatCelsius, HEAT_MAX_CELSIUS); }
}
