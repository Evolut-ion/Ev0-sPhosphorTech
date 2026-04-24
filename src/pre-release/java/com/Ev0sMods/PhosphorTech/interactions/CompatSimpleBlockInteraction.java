package com.Ev0sMods.PhosphorTech.interactions;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

abstract class CompatSimpleBlockInteraction extends SimpleBlockInteraction {

    @Override
    protected final void interactWithBlock(
            @Nonnull  World world,
            @Nonnull  CommandBuffer<EntityStore> commandBuffer,
            @Nonnull  InteractionType interactionType,
            @Nonnull  InteractionContext interactionContext,
            @Nullable ItemStack itemStack,
            @Nonnull  Vector3i blockPos,
            @Nonnull  CooldownHandler cooldownHandler) {
        interactWithBlockCompat(world, commandBuffer, interactionType, interactionContext, itemStack,
                blockPos, cooldownHandler);
    }

    protected abstract void interactWithBlockCompat(
            @Nonnull  World world,
            @Nonnull  CommandBuffer<EntityStore> commandBuffer,
            @Nonnull  InteractionType interactionType,
            @Nonnull  InteractionContext interactionContext,
            @Nullable ItemStack itemStack,
            @Nonnull  Vector3i blockPos,
            @Nonnull  CooldownHandler cooldownHandler);

    @Override
    protected final void simulateInteractWithBlock(
            @Nonnull  InteractionType interactionType,
            @Nonnull  InteractionContext interactionContext,
            @Nullable ItemStack itemStack,
            @Nonnull  World world,
            @Nonnull  Vector3i blockPos) {
        simulateInteractWithBlockCompat(interactionType, interactionContext, itemStack, world, blockPos);
    }

    protected void simulateInteractWithBlockCompat(
            @Nonnull  InteractionType interactionType,
            @Nonnull  InteractionContext interactionContext,
            @Nullable ItemStack itemStack,
            @Nonnull  World world,
            @Nonnull  Vector3i blockPos) {}
}