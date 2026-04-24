package com.Ev0sMods.PhosphorTech.interactions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.blocks.BlockAnimator;
import com.Ev0sMods.PhosphorTech.blocks.ClutchState;
import com.Ev0sMods.PhosphorTech.mechanical.GearNetwork;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Custom interaction registered as {@code "ToggleClutch"}.
 *
 * <p>Right-clicking a Gear Clutch block toggles its locked/unlocked state.
 *
 * <ul>
 *   <li><b>Unlocked → Locked:</b> sets the {@link ClutchState#locked} flag,
 *       updates the visual to {@code "Locked"}, and notifies neighbours so
 *       the gear network can recalculate connectivity.</li>
 *   <li><b>Locked → Unlocked:</b> clears the flag, resets visual to
 *       {@code "Off"} (spin will resume next time a provider fires), and
 *       again notifies neighbours.</li>
 * </ul>
 */
public final class ToggleClutchInteraction extends CompatSimpleBlockInteraction {

    public static final BuilderCodec<ToggleClutchInteraction> CODEC =
            BuilderCodec.builder(
                    ToggleClutchInteraction.class,
                    ToggleClutchInteraction::new,
                    SimpleBlockInteraction.CODEC)
            .documentation("Toggles the Gear Clutch locked/unlocked state.")
            .build();

    @Override
    protected void interactWithBlockCompat(
            @Nonnull  World                      world,
            @Nonnull  CommandBuffer<EntityStore>  commandBuffer,
            @Nonnull  InteractionType             interactionType,
            @Nonnull  InteractionContext          interactionContext,
            @Nullable ItemStack                   itemStack,
            @Nonnull  Vector3i                   blockPos,
            @Nonnull  CooldownHandler             cooldownHandler) {

        Object node = GearNetwork.getAt(blockPos);
        if (!(node instanceof ClutchState clutch)) {
            HytaleLogger.getLogger().atFine().log(
                    "[ToggleClutch] No ClutchState registered at %s", blockPos);
            return;
        }

        clutch.locked = !clutch.locked;
        // Clutch state change alters propagation topology — invalidate BFS cache.
        GearNetwork.invalidatePropCache();

        String newState = clutch.locked ? "Locked" : "Off";
        try {
            BlockAnimator.applyBlockState(world, blockPos, newState, 0);
        } catch (Throwable ignored) {}

                // If we've just locked the clutch, immediately stop any connected
                // downstream gears so they don't keep animating until their timers expire.
                if (clutch.locked) {
                        try { GearNetwork.stopConnectedFrom(blockPos); } catch (Throwable ignored) {}
                }
                // Notify adjacent gear-network nodes so they can recalculate.
                GearNetwork.notifyNeighbors(blockPos);

        HytaleLogger.getLogger().atFine().log(
                "[ToggleClutch] Clutch at %d,%d,%d → %s",
                blockPos.x, blockPos.y, blockPos.z,
                clutch.locked ? "LOCKED" : "UNLOCKED");
    }
}
