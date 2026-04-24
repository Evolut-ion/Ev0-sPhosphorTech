package com.Ev0sMods.PhosphorTech.blocks;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepts {@link BreakBlockEvent} for animated PhosphorTech blocks.
 *
 * <p>When a block in a non-default animated state (Spin, Working, etc.) is
 * broken, the C# client crashes with {@code DivideByZeroException} because
 * it cannot transition a looping {@code CustomModelAnimation} directly to air.
 *
 * <p>Fix: cancel the original break and transition the block to "Off" state
 * (client-visible).  The block remains in the world in Off state.  The
 * player's next break attempt hits an Off-state block, which is safe to
 * destroy (non-looping animation).
 */
public class BreakBlockAnimationResetSystem
        extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    /**
     * Block type IDs whose JSON definitions include looping animated state
     * variants (Working, Active, Done) that crash the C# client if broken
     * directly (looping animation → air = DivideByZeroException).
     *
     * <p><b>Excluded:</b>
     * <ul>
     *   <li>Gear blocks (SmallGear, LargeGear, HandCrank, Clutch) — use
     *       separate-block approach, IDs never contain {@code _State_}.</li>
     *   <li>Wires — use {@code Connect_N} states with no "Off" definition;
     *       intercepting them makes wires permanently unbreakable.</li>
     *   <li>Fluid pipes — same as wires (connection states, no Off).</li>
     *   <li>WaterTank, Pump, SteamReservoir, SteamGenerator — no looping
     *       {@code CustomModelAnimation}; safe to break in any state.</li>
     * </ul>
     */
    private static final Set<String> ANIMATED_BLOCK_IDS = Set.of(
            "Crusher", "Extractor", "Centrifuge", "AlloySmelter",
            "MechanicalGrinder",
            "CrystalGenerator", "CrystallineCapacitor",
            "Waterwheel", "LeafSpringFlywheelCapacitor",
            "Shaft"
    );

    /**
     * Positions recently transitioned to Off.  Prevents double-cancel
     * if another event fires for the same position before the player
     * re-breaks it.
     */
    private static final Set<Long> RECENTLY_RESET = ConcurrentHashMap.newKeySet();

    public BreakBlockAnimationResetSystem() {
        super(BreakBlockEvent.class);
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull BreakBlockEvent event) {
        try {
            var pos = event.getTargetBlock();
            long packed = packPos(pos.x, pos.y, pos.z);

            BlockType blockType = event.getBlockType();
            if (blockType == null) return;

            String id = (String) blockType.getId();
            if (id == null) return;

            // Strip state-variant decoration to get the base block ID.
            String baseId = id;
            if (baseId.startsWith("*")) baseId = baseId.substring(1);
            int stateIdx = baseId.indexOf("_State_");
            if (stateIdx > 0) baseId = baseId.substring(0, stateIdx);

            if (!ANIMATED_BLOCK_IDS.contains(baseId)) return;

            // Only intercept if the block is in a non-default (animated) state.
            // Default-state blocks (no _State_ suffix) are safe to break.
            if (!id.contains("_State_")) return;

            // Off state is safe (Looping: false) — let the break proceed.
            if (id.endsWith("_Off")) {
                RECENTLY_RESET.remove(packed);
                return;
            }

            // Already reset this position — don't double-cancel.
            if (RECENTLY_RESET.contains(packed)) return;

            // ── Cancel the original break ────────────────────────────────────
            event.setCancelled(true);

            World world = store.getExternalData().getWorld();
            if (world == null) return;

            // ── Transition block to "Off" state — client-visible ─────────────
            // CRITICAL: We do NOT use BlockAnimator.applyBlockState() here
            // because it uses SetBlockSettings.NO_UPDATE_STATE, which means
            // the client never sees the Off transition.  Instead we call
            // chunk.setBlock directly with flags=NONE so the state change is
            // sent to the client.
            int bx = pos.x, by = pos.y, bz = pos.z;
            WorldChunk chunk = world.getChunkIfInMemory(
                    ChunkUtil.indexChunkFromBlock(bx, bz));
            if (chunk == null) return;

            BlockType current = chunk.getBlockType(pos.x, pos.y, pos.z);
            if (current == null) return;

            String offKey = current.getBlockKeyForState("Off");
            if (offKey == null) return;

            var assetMap = BlockType.getAssetMap();
            int offIdx = assetMap.getIndex(offKey);
            if (offIdx == Integer.MIN_VALUE) return;
            BlockType offType = (BlockType) assetMap.getAsset(offIdx);
            int rot = chunk.getRotationIndex(bx, by, bz);

            // Flags: NONE / NONE → client receives the block-state update
            chunk.setBlock(bx, by, bz, offIdx, offType, rot,
                    SetBlockSettings.NONE, SetBlockSettings.NONE);
            chunk.setTicking(bx, by, bz, true);

            RECENTLY_RESET.add(packed);

            System.out.println("[PhosphorTech] Cancelled break of " + id
                    + " at " + bx + "," + by + "," + bz
                    + " — transitioned to Off, re-break to destroy");
        } catch (Throwable t) {
            System.out.println("[PhosphorTech] BreakBlockAnimationReset error: " + t);
            t.printStackTrace();
        }
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    /** Pack a block position into a single long for the recursion guard set. */
    private static long packPos(int x, int y, int z) {
        return ((long) (x + 30_000_000) << 40)
             | ((long) (y + 1024) << 20)
             | ((long) (z + 30_000_000));
    }
}
