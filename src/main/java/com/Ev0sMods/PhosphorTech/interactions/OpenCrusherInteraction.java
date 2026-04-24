package com.Ev0sMods.PhosphorTech.interactions;

import com.Ev0sMods.PhosphorTech.ui.CrusherUIPage;
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

/**
 * Custom interaction registered as {@code "OpenCrusher"}.
 * Right-clicking the Crusher block opens the HyUI panel.
 */
public final class OpenCrusherInteraction extends CompatSimpleBlockInteraction {

    public static final BuilderCodec<OpenCrusherInteraction> CODEC =
            BuilderCodec.builder(
                    OpenCrusherInteraction.class,
                    OpenCrusherInteraction::new,
                    SimpleBlockInteraction.CODEC)
            .documentation("Opens the Crusher UI.")
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
                    "[OpenCrusher] Could not resolve PlayerRef: " + t.getMessage());
            return;
        }
        if (playerRef == null) return;

        try {
            CrusherUIPage.openForced(playerRef, playerEnt, store, blockPos);
        } catch (Throwable t) {
            HytaleLogger.getLogger().atWarning().log(
                    "[OpenCrusher] UI open failed: " + t.getMessage());
        }
    }
}
