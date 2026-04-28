package com.Ev0sMods.PhosphorTech.interactions;

import com.Ev0sMods.PhosphorTech.blocks.RotaryPivotState;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
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
 * Registered as {@code "RotaryPivotActivate"}.
 *
 * <p>When the player right-clicks the Rotary Pivot:
 * <ul>
 *   <li>If <b>IDLE</b>: sets {@code activated = true} so the state scans for an
 *       adjacent block and locks onto it.</li>
 *   <li>If <b>LOCKED</b>: sets {@code activated = true} as a manual trigger —
 *       fills the Joule buffer to immediately start a rotation without needing
 *       mechanical power. Useful for testing.</li>
 * </ul>
 * While ROTATING or RESTORING the click is silently ignored.
 */
public final class RotaryPivotInteraction extends CompatSimpleBlockInteraction {

    public static final BuilderCodec<RotaryPivotInteraction> CODEC =
            BuilderCodec.builder(
                    RotaryPivotInteraction.class,
                    RotaryPivotInteraction::new,
                    SimpleBlockInteraction.CODEC)
            .documentation("Locks the Rotary Pivot to an adjacent block or manually triggers a rotation.")
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
        RotaryPivotState state = RotaryPivotState.REGISTRY.get(key);
        if (state == null) return;
        state.activate();
    }
}
