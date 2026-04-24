package com.Ev0sMods.PhosphorTech.interactions;

import com.Ev0sMods.PhosphorTech.ui.WaterTankUIPage;
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
 * Custom interaction registered as {@code "OpenWaterTank"}.
 *
 * <p>Right-clicking a Water Tank block opens the HyUI status panel
 * via the {@link WaterTankUI} reflection bridge.
 *
 * <p>Usage in item JSON:
 * <pre>{@code
 *   "Interactions": { "Use": { "Interactions": [{ "Type": "OpenWaterTank" }] } }
 * }</pre>
 */
public final class OpenWaterTankInteraction extends CompatSimpleBlockInteraction {

    public static final BuilderCodec<OpenWaterTankInteraction> CODEC =
            BuilderCodec.builder(
                    OpenWaterTankInteraction.class,
                    OpenWaterTankInteraction::new,
                    SimpleBlockInteraction.CODEC)
            .documentation("Opens the Water Tank status UI.")
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
                    "[OpenWaterTank] Could not resolve PlayerRef: " + t.getMessage());
            return;
        }
        if (playerRef == null) return;

        try {
            WaterTankUIPage.openForced(playerRef, playerEnt, store, blockPos);
        } catch (Throwable t) {
            HytaleLogger.getLogger().atWarning().log(
                    "[OpenWaterTank] UI open failed: " + t.getMessage());
        }
    }
}
