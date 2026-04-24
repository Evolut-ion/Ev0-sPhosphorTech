package com.Ev0sMods.PhosphorTech.interactions;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.math.util.ChunkUtil;
import org.joml.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Custom <b>Primary</b> (left-click) interaction for animated PhosphorTech blocks.
 *
 * <p>Replaces the default {@code BreakBlockInteraction} on blocks that have
 * looping {@code CustomModelAnimation} states.  Without this, the C# client
 * crashes with {@code System.DivideByZeroException} when a looping block is
 * broken directly (Spin→Air).
 *
 * <h3>Two-click breaking</h3>
 * <ol>
 *   <li><b>Click 1</b> (block is animated — Spin, Working, etc.):
 *       transition to Off state via client-visible {@code chunk.setBlock}.</li>
 *   <li><b>Click 2</b> (block is Off / default):
 *       break the block normally via {@code chunk.breakBlock}.</li>
 * </ol>
 */
public final class AnimatedBlockBreakInteraction extends CompatSimpleBlockInteraction {

    public static final BuilderCodec<AnimatedBlockBreakInteraction> CODEC =
            BuilderCodec.builder(
                    AnimatedBlockBreakInteraction.class,
                    AnimatedBlockBreakInteraction::new,
                    SimpleBlockInteraction.CODEC)
            .documentation("Safely breaks animated PhosphorTech blocks by first transitioning to Off state.")
            .build();

    @Override
    protected void interactWithBlockCompat(
            @Nonnull  World                      world,
            @Nonnull  CommandBuffer<EntityStore>  commandBuffer,
            @Nonnull  InteractionType             interactionType,
            @Nonnull  InteractionContext          interactionContext,
            @Nullable ItemStack                   itemStack,
            @Nonnull  Vector3i                    blockPos,
            @Nonnull  CooldownHandler             cooldownHandler) {

        try {
            int bx = blockPos.x, by = blockPos.y, bz = blockPos.z;

            WorldChunk chunk = world.getChunkIfInMemory(
                    ChunkUtil.indexChunkFromBlock(bx, bz));
            if (chunk == null) return;

            BlockType blockType = chunk.getBlockType(bx, by, bz);
            if (blockType == null) return;

            String id = (String) blockType.getId();
            if (id == null) return;

            // ── Determine whether this block is in an animated state ─────────
            // Animated states contain "_State_" and do NOT end with "_Off".
            boolean isAnimated = id.contains("_State_") && !id.endsWith("_Off");

            if (isAnimated) {
                // ── Click 1: transition to Off ───────────────────────────────
                String offKey = blockType.getBlockKeyForState("Off");
                if (offKey == null) {
                    System.out.println("[PhosphorTech] AnimatedBlockBreak: no Off key for " + id);
                    return;
                }

                var assetMap = BlockType.getAssetMap();
                int offIdx = assetMap.getIndex(offKey);
                if (offIdx == Integer.MIN_VALUE) {
                    System.out.println("[PhosphorTech] AnimatedBlockBreak: Off asset not found for " + offKey);
                    return;
                }
                BlockType offType = (BlockType) assetMap.getAsset(offIdx);
                int rot = chunk.getRotationIndex(bx, by, bz);

                // Client-visible flags — ensures the Off transition reaches the client.
                chunk.setBlock(bx, by, bz, offIdx, offType, rot,
                        SetBlockSettings.NONE, SetBlockSettings.NONE);
                chunk.setTicking(bx, by, bz, true);

                System.out.println("[PhosphorTech] AnimatedBlockBreak: "
                        + id + " → Off at " + bx + "," + by + "," + bz);
            } else {
                // ── Click 2: block is Off or default — break it ──────────────
                // Off state has Looping:false, so Off→Air is safe for the client.
                chunk.breakBlock(bx, by, bz);

                System.out.println("[PhosphorTech] AnimatedBlockBreak: broke "
                        + id + " at " + bx + "," + by + "," + bz);
            }
        } catch (Throwable t) {
            System.out.println("[PhosphorTech] AnimatedBlockBreak error: " + t);
            t.printStackTrace();
        }
    }
}
