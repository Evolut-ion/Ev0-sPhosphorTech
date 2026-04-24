package com.Ev0sMods.PhosphorTech.interactions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.ui.GenericFluidTankUIPage;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Custom interaction registered as {@code "OpenGenericFluidTank"}.
 *
 * <p>Right-clicking a Generic Fluid Tank block opens the HyUI status panel.
 *
 * <p>Usage in item JSON:
 * <pre>{@code
 *   "Interactions": { "Use": { "Interactions": [{ "Type": "OpenGenericFluidTank" }] } }
 * }</pre>
 */
public final class OpenGenericFluidTankInteraction extends CompatSimpleBlockInteraction {

    public static final BuilderCodec<OpenGenericFluidTankInteraction> CODEC =
            BuilderCodec.builder(
                    OpenGenericFluidTankInteraction.class,
                    OpenGenericFluidTankInteraction::new,
                    SimpleBlockInteraction.CODEC)
            .documentation("Opens the Generic Fluid Tank status UI.")
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
                    "[OpenGenericFluidTank] Could not resolve PlayerRef: " + t.getMessage());
            return;
        }
        if (playerRef == null) return;

        try {
            GenericFluidTankUIPage.openForced(playerRef, playerEnt, store, blockPos);
        } catch (Throwable t) {
            HytaleLogger.getLogger().atWarning().log(
                    "[OpenGenericFluidTank] UI open failed: " + t.getMessage());
        }
    }
}
