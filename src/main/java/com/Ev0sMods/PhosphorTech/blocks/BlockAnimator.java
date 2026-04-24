package com.Ev0sMods.PhosphorTech.blocks;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;

/**
 * Reusable per-block animation state manager.
 *
 * <p>Each block-state class that wants animatable block states holds a single
 * {@code BlockAnimator} instance. The animator tracks the currently-applied
 * state name, handles auto-revert after a configurable hold duration, and
 * centralises the {@code applyBlockState} logic extracted from
 * {@code FertilizerState}.
 *
 * <h3>Usage pattern</h3>
 * <pre>{@code
 * // In your block state class:
 * private final BlockAnimator animator = new BlockAnimator();
 *
 * // In tick(), after position is resolved:
 * animator.tick(world, cachedPosition);
 *
 * // To enter a named state (hold for default 60 ticks, then revert to "Off"):
 * animator.setState(world, cachedPosition, "Working");
 *
 * // To hold indefinitely (e.g. while a machine is running):
 * animator.setState(world, cachedPosition, "Working", BlockAnimator.HOLD_INDEFINITE);
 *
 * // To immediately revert to "Off":
 * animator.clear(world, cachedPosition);
 * }</pre>
 *
 * <h3>State name conventions</h3>
 * <ul>
 *   <li>{@value #STATE_OFF}    – default / idle (auto-revert target)</li>
 *   <li>{@value #STATE_WORKING} – machine is actively processing</li>
 *   <li>{@value #STATE_DONE}   – processing just completed (brief flash)</li>
 *   <li>{@value #STATE_ACTIVE} – generic "on" state (generators, wires)</li>
 * </ul>
 * If a named state is not defined in the block's JSON the call is silently
 * ignored, so adding the animator never breaks blocks that have no extra
 * visual states.
 */
public final class BlockAnimator {

    // ── State name constants ──────────────────────────────────────────────────

    public static final String STATE_OFF     = "Off";
    public static final String STATE_WORKING = "Working";
    public static final String STATE_DONE    = "Done";
    public static final String STATE_ACTIVE  = "Active";

    /** Pass as {@code holdTicks} to keep a state until {@link #clear} is called. */
    public static final int HOLD_INDEFINITE = -1;
    /** Default hold: 60 ticks (~2 s at 30 TPS) before auto-reverting to {@value #STATE_OFF}. */
    public static final int HOLD_DEFAULT    = 60;

    // ── Instance state ────────────────────────────────────────────────────────

    /**
     * When non-null, the animator operates in "separate block" mode:
     * {@link #setState} calls {@link #replaceBlock} with
     * {@code baseBlockName + "_" + stateName}, and {@link #clear} reverts to
     * {@code baseBlockName} itself.  When {@code null}, the legacy
     * {@link #applyBlockState} path is used instead.
     */
    private final String baseBlockName;

    private boolean isAnimating   = false;
    private int     animHoldTimer = 0;
    private String  currentState  = null;

    /** Legacy constructor — uses {@link #applyBlockState} for state changes. */
    public BlockAnimator() { this.baseBlockName = null; }

    /**
     * Separate-block constructor — uses {@link #replaceBlock} with
     * {@code baseBlockName + "_" + stateName} for animated states and
     * {@code baseBlockName} for the off/idle state.
     */
    public BlockAnimator(String baseBlockName) { this.baseBlockName = baseBlockName; }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Must be called once per tick (after the block's position is resolved).
     * Counts down the hold timer and reverts to {@value #STATE_OFF} when it
     * expires.
     */
    public void tick(World world, Vector3i pos) {
        if (animHoldTimer > 0) {
            animHoldTimer--;
            if (animHoldTimer == 0 && isAnimating) {
                if (baseBlockName != null) {
                    replaceBlock(world, pos, baseBlockName);
                } else {
                    applyBlockState(world, pos, STATE_OFF);
                }
                isAnimating  = false;
                currentState = null;
            }
        }
    }

    /**
     * Transition to {@code stateName} and hold for {@link #HOLD_DEFAULT} ticks
     * before auto-reverting to {@value #STATE_OFF}.
     *
     * <p>Calling with the same {@code stateName} that is already applied resets
     * the hold timer without issuing a redundant block-state change.
     */
    public void setState(World world, Vector3i pos, String stateName) {
        setState(world, pos, stateName, HOLD_DEFAULT);
    }

    /**
     * Transition to {@code stateName} with a custom hold duration.
     *
     * @param holdTicks Ticks to hold before auto-reverting.
     *                  Pass {@link #HOLD_INDEFINITE} to hold until
     *                  {@link #clear} is called explicitly.
     */
    public void setState(World world, Vector3i pos, String stateName, int holdTicks) {
        if (stateName == null || STATE_OFF.equals(stateName)) {
            clear(world, pos);
            return;
        }
        animHoldTimer = holdTicks;
        if (!stateName.equals(currentState)) {
            if (baseBlockName != null) {
                replaceBlock(world, pos, baseBlockName + "_" + stateName);
            } else {
                applyBlockState(world, pos, stateName);
            }
            isAnimating  = true;
            currentState = stateName;
        }
    }

    /**
     * Immediately revert to {@value #STATE_OFF} and cancel any pending hold.
     */
    public void clear(World world, Vector3i pos) {
        if (isAnimating) {
            if (baseBlockName != null) {
                replaceBlock(world, pos, baseBlockName);
            } else {
                applyBlockState(world, pos, STATE_OFF);
            }
            isAnimating  = false;
            currentState = null;
        }
        animHoldTimer = 0;
    }

    /**
     * Reset internal state without sending any block-state-change packet to clients.
     * Use this when the block has already been destroyed to avoid sending stale packets
     * that can crash the C# engine.
     */
    public void reset() {
        isAnimating   = false;
        currentState  = null;
        animHoldTimer = 0;
    }

    /** @return {@code true} if a non-Off animation state is currently active. */
    public boolean isActive() { return isAnimating; }

    /** @return The currently applied state name, or {@code null} if in the Off state. */
    public String getCurrentState() { return currentState; }

    // ── Static utility — block-state application ──────────────────────────────

    /**
     * Directly apply a named block state, preserving the block's existing
     * Y-rotation. Silently no-ops if the state is not defined.
     */
    public static void applyBlockState(World world, Vector3i pos, String stateName) {
        try {
            int bx = pos.x, by = pos.y, bz = pos.z;
            WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(bx, bz));
            if (chunk == null) return;
            BlockType current = chunk.getBlockType(bx, by, bz);
            if (current == null) return;
            String stateKey = current.getBlockKeyForState(stateName);
            if (stateKey == null) return;
            var assetMap = BlockType.getAssetMap();
            int idx = assetMap.getIndex(stateKey);
            if (idx == Integer.MIN_VALUE) return;
            BlockType target = (BlockType) assetMap.getAsset(idx);
            int rot = chunk.getRotationIndex(bx, by, bz);
            chunk.setBlock(bx, by, bz, idx, target, rot,
                    SetBlockSettings.NONE,
                    SetBlockSettings.NO_UPDATE_STATE | SetBlockSettings.NO_SEND_PARTICLES | 256);
            // Re-arm ticking so the block keeps processing after the setBlock
            // call clears the tick flag.
            chunk.setTicking(bx, by, bz, true);
        } catch (Throwable e) {
            HytaleLogger.getLogger().atFine().log(
                    "[BlockAnimator] applyBlockState '%s' at (%d,%d,%d) failed: %s",
                    stateName, pos.x, pos.y, pos.z, e.getMessage());
        }
    }

    /**
     * Directly apply a named block state with an explicit Y-rotation.
     *
     * <p>Rotation encoding (counter-clockwise 90° steps):
     * <ul>
     *   <li>0 → South (+Z) — canonical / no rotation</li>
     *   <li>1 → West  (-X)</li>
     *   <li>2 → North (-Z)</li>
     *   <li>3 → East  (+X)</li>
     * </ul>
     */
    public static void applyBlockState(World world, Vector3i pos, String stateName, int yRotation) {
        try {
            int bx = pos.x, by = pos.y, bz = pos.z;
            WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(bx, bz));
            if (chunk == null) return;
            BlockType current = chunk.getBlockType(bx, by, bz);
            if (current == null) return;
            String stateKey = current.getBlockKeyForState(stateName);
            if (stateKey == null) return;
            var assetMap = BlockType.getAssetMap();
            int idx = assetMap.getIndex(stateKey);
            if (idx == Integer.MIN_VALUE) return;
            BlockType target = (BlockType) assetMap.getAsset(idx);
            chunk.setBlock(bx, by, bz, idx, target, yRotation,
                    SetBlockSettings.NONE,
                    SetBlockSettings.NO_UPDATE_STATE | SetBlockSettings.NO_SEND_PARTICLES | 256);
            chunk.setTicking(bx, by, bz, true);
        } catch (Throwable e) {
            HytaleLogger.getLogger().atFine().log(
                    "[BlockAnimator] applyBlockState '%s' rot=%d at (%d,%d,%d) failed: %s",
                    stateName, yRotation, pos.x, pos.y, pos.z, e.getMessage());
        }
    }

    // ── Static utility — block type replacement (separate-block approach) ─────

    /**
     * Replace the block at {@code pos} with a completely different block type
     * identified by its Item/Block JSON name (e.g. {@code "SmallGear_Spin"}).
     *
     * <p>Uses {@code NO_UPDATE_STATE} to preserve the existing {@link TickableBlockState}
     * component so the ECS state (timers, network registration, etc.) survives the
     * block-type swap.  The block's Y-rotation is also preserved.
     *
     * <p>This is the "separate block" alternative to {@link #applyBlockState} — instead
     * of switching between State/Definitions within one block, we swap to an entirely
     * different block that shares the same ECS component.
     */
    public static void replaceBlock(World world, Vector3i pos, String blockName) {
        try {
            int bx = pos.x, by = pos.y, bz = pos.z;
            WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(bx, bz));
            if (chunk == null) return;
            var assetMap = BlockType.getAssetMap();
            int idx = assetMap.getIndex(blockName);
            if (idx == Integer.MIN_VALUE) {
                HytaleLogger.getLogger().atFine().log(
                        "[BlockAnimator] replaceBlock: unknown block '%s'", blockName);
                return;
            }
            BlockType target = (BlockType) assetMap.getAsset(idx);
            int rot = chunk.getRotationIndex(bx, by, bz);
            chunk.setBlock(bx, by, bz, idx, target, rot,
                    SetBlockSettings.NONE,
                    SetBlockSettings.NO_UPDATE_STATE | SetBlockSettings.NO_SEND_PARTICLES | 256);
            chunk.setTicking(bx, by, bz, true);
        } catch (Exception e) {
            HytaleLogger.getLogger().atFine().log(
                    "[BlockAnimator] replaceBlock '%s' at (%d,%d,%d) failed: %s",
                    blockName, pos.x, pos.y, pos.z, e.getMessage());
        }
    }

    /**
     * Replace the block at {@code pos} with a different block type, overriding
     * the Y-rotation instead of preserving it.
     *
     * @param yRotation The explicit Y-rotation to apply (0-3 for horizontal,
     *                  4 = Up, 5 = Down).
     */
    public static void replaceBlock(World world, Vector3i pos, String blockName, int yRotation) {
        try {
            int bx = pos.x, by = pos.y, bz = pos.z;
            WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(bx, bz));
            if (chunk == null) return;
            var assetMap = BlockType.getAssetMap();
            int idx = assetMap.getIndex(blockName);
            if (idx == Integer.MIN_VALUE) {
                HytaleLogger.getLogger().atFine().log(
                        "[BlockAnimator] replaceBlock: unknown block '%s'", blockName);
                return;
            }
            BlockType target = (BlockType) assetMap.getAsset(idx);
            chunk.setBlock(bx, by, bz, idx, target, yRotation,
                    SetBlockSettings.NONE,
                    SetBlockSettings.NO_UPDATE_STATE | SetBlockSettings.NO_SEND_PARTICLES | 256);
            chunk.setTicking(bx, by, bz, true);
        } catch (Exception e) {
            HytaleLogger.getLogger().atFine().log(
                    "[BlockAnimator] replaceBlock '%s' rot=%d at (%d,%d,%d) failed: %s",
                    blockName, yRotation, pos.x, pos.y, pos.z, e.getMessage());
        }
    }

    // ── Static utility — connection-count model selector ──────────────────────

    /**
     * Face index constants — identical to {@code AdjacentSide.ordinal()} values.
     *
     * <p>Use these as indices into the {@code connected} array passed to
     * {@link #syncConnectionModel}.
     */
    public static final int FACE_UP    = 0;
    public static final int FACE_DOWN  = 1;
    public static final int FACE_NORTH = 2;
    public static final int FACE_EAST  = 3;
    public static final int FACE_SOUTH = 4;
    public static final int FACE_WEST  = 5;

    /** Neighbour offsets indexed by {@code FACE_*}. Order: Up, Down, North, East, South, West. */
    public static final int[][] FACE_OFFSETS = {
        { 0,  1,  0 },  // UP
        { 0, -1,  0 },  // DOWN
        { 0,  0, -1 },  // NORTH  (-Z)
        { 1,  0,  0 },  // EAST   (+X)
        { 0,  0,  1 },  // SOUTH  (+Z) — canonical arm direction
        {-1,  0,  0 },  // WEST   (-X)
    };

    /**
     * Selects and applies the correct connection-count block-state model.
     *
     * <p>Only issues a {@code setBlock} call when the connection mask has
     * actually changed (deduplication via {@code lastMask}).
     *
     * <h3>State name conventions (add matching entries to the block JSON):</h3>
     * <pre>
     *  Connection  State name              Canonical model orientation
     *  ──────────  ──────────────────────  ─────────────────────────────────────────
     *  0           Connect_0               centre node only, symmetric
     *  1           Connect_1               single arm facing +Z (South)
     *              Connect_1_Up            arm facing +Y
     *              Connect_1_Down          arm facing -Y
     *  2           Connect_2               straight, arms on Z-axis (N/S)
     *              Connect_2_Vertical      straight, arms on Y-axis (U/D)
     *              Connect_2_Corner        L-bend, openings at South (+Z) and East (+X)
     *  3           Connect_3               T-shape, bar=E/W, stem=South (+Z)
     *              Connect_3_Stem_Up       T-shape, bar=horizontal, stem=Up (+Y)
     *              Connect_3_Stem_Down     T-shape, bar=horizontal, stem=Down (-Y)
     *  4           Connect_4               flat cross N/S/E/W, symmetric
     *              Connect_4_Vertical      vertical cross U/D + N/S (rot=0) or U/D + E/W (rot=1)
     *  5           Connect_5               5 arms, MISSING South (+Z) — rotate for other h-gaps
     *              Connect_5_MissUp        5 arms, missing +Y
     *              Connect_5_MissDown      5 arms, missing -Y
     *  6           Connect_6               all 6 arms, symmetric
     * </pre>
     *
     * @param connected  6-element boolean array indexed by {@code FACE_*} constants.
     * @param lastMask   6-bit mask from the previous call; pass {@code -1} on first use.
     * @return           The new 6-bit mask (store and pass back next call).
     */
    public static int syncConnectionModel(World world, Vector3i pos,
                                          boolean[] connected, int lastMask) {
        int mask = 0;
        for (int i = 0; i < 6; i++) if (connected[i]) mask |= (1 << i);
        if (mask == lastMask) return lastMask;

        applyBlockState(world, pos, "Connect_" + mask);
        return mask;
    }

}
