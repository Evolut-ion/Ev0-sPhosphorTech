package com.Ev0sMods.PhosphorTech.interactions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.blocks.BellowsState;
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
 * Custom interaction registered as {@code "PumpBellows"}.
 *
 * <p>Right-clicking the Bellows block triggers a heat boost into all adjacent
 * {@link com.Ev0sMods.PhosphorTech.heat.HeatCapable} blocks for
 * {@link BellowsState#PUMP_TICKS} ticks.
 */
public final class PumpBellowsInteraction extends CompatSimpleBlockInteraction {

    public static final BuilderCodec<PumpBellowsInteraction> CODEC =
            BuilderCodec.builder(
                    PumpBellowsInteraction.class,
                    PumpBellowsInteraction::new,
                    SimpleBlockInteraction.CODEC)
            .documentation("Activates the Bellows, blasting heat into adjacent heater blocks.")
            .build();

    @Override
    protected void interactWithBlockCompat(
            @Nonnull  World                      world,
            @Nonnull  CommandBuffer<EntityStore> commandBuffer,
            @Nonnull  InteractionType            interactionType,
            @Nonnull  InteractionContext         interactionContext,
            @Nullable ItemStack                  itemStack,
            @Nonnull  Vector3i                   blockPos,
            @Nonnull  CooldownHandler            cooldownHandler) {

        String key = blockPos.x + "," + blockPos.y + "," + blockPos.z;
        BellowsState bellows = BellowsState.REGISTRY.get(key);
        if (bellows == null) {
            HytaleLogger.getLogger().atFine().log(
                    "[PumpBellows] No BellowsState registered at %s", blockPos);
            return;
        }
        // Ignore if already pumping
        if (bellows.pumpTimer > 0) return;

        bellows.activated = true;
        HytaleLogger.getLogger().atFine().log(
                "[PumpBellows] Bellows activated at %d,%d,%d",
                blockPos.x, blockPos.y, blockPos.z);
    }
}
