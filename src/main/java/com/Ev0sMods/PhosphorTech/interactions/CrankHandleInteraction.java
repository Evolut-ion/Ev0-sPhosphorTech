package com.Ev0sMods.PhosphorTech.interactions;

import com.Ev0sMods.PhosphorTech.blocks.HandCrankState;
import com.Ev0sMods.PhosphorTech.mechanical.GearNetwork;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import org.joml.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Custom interaction registered as {@code "CrankHandle"}.
 *
 * <p>Right-clicking the Hand Crank block starts one revolution:
 * sets {@link HandCrankState#activated} to {@code true} so the state's
 * tick method begins the revolution on its next tick.
 *
 * <p>A built-in cooldown prevents repeated spam (one revolution must
 * complete before another can start — enforced by {@link CooldownHandler}).
 */
public final class CrankHandleInteraction extends CompatSimpleBlockInteraction {

    public static final BuilderCodec<CrankHandleInteraction> CODEC =
            BuilderCodec.builder(
                    CrankHandleInteraction.class,
                    CrankHandleInteraction::new,
                    SimpleBlockInteraction.CODEC)
            .documentation("Turns the Hand Crank one full revolution, producing 4 J.")
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

        Object node = GearNetwork.getAt(blockPos);
        if (!(node instanceof HandCrankState crank)) {
            HytaleLogger.getLogger().atFine().log(
                    "[CrankHandle] No HandCrankState registered at %s", blockPos);
            return;
        }
        // Ignore if a revolution is already in progress.
        if (crank.revolutionTimer > 0) return;

        crank.activated = true;
        HytaleLogger.getLogger().atFine().log(
                "[CrankHandle] Revolution started at %d,%d,%d",
                blockPos.x, blockPos.y, blockPos.z);
    }
}
