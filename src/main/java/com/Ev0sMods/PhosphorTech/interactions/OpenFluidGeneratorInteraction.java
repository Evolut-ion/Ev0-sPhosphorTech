package com.Ev0sMods.PhosphorTech.interactions;

import com.Ev0sMods.PhosphorTech.ui.FluidGeneratorUIPage;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import org.joml.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class OpenFluidGeneratorInteraction extends CompatSimpleBlockInteraction {

    public static final BuilderCodec<OpenFluidGeneratorInteraction> CODEC =
            BuilderCodec.builder(
                    OpenFluidGeneratorInteraction.class,
                    OpenFluidGeneratorInteraction::new,
                    SimpleBlockInteraction.CODEC)
            .documentation("Opens the Fluid Generator UI.")
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

        Ref<EntityStore> playerEnt = interactionContext.getOwningEntity();
        Store<EntityStore> store   = playerEnt.getStore();
        PlayerRef playerRef;
        try {
            playerRef = store.getComponent(playerEnt, PlayerRef.getComponentType());
        } catch (Throwable t) {
            HytaleLogger.getLogger().atWarning().log(
                    "[OpenFluidGenerator] Could not resolve PlayerRef: " + t.getMessage());
            return;
        }
        if (playerRef == null) return;

        try {
            FluidGeneratorUIPage.openForced(playerRef, playerEnt, store, blockPos);
        } catch (Throwable t) {
            HytaleLogger.getLogger().atWarning().log(
                    "[OpenFluidGenerator] UI open failed: " + t.getMessage());
        }
    }
}
