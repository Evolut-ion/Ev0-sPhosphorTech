package com.Ev0sMods.PhosphorTech.interactions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.blocks.RodPullerState;
import com.Ev0sMods.PhosphorTech.recipe.ProcessingRecipeRegistry;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * "OpenRodPuller" interaction — no UI, pure inventory logic.
 *
 * <p>Press F: insert a valid rod-recipe input if input slot is empty,
 * or take finished rods from the output slot.
 */
public final class OpenRodPullerInteraction extends CompatSimpleBlockInteraction {

    public static final BuilderCodec<OpenRodPullerInteraction> CODEC =
            BuilderCodec.builder(
                    OpenRodPullerInteraction.class,
                    OpenRodPullerInteraction::new,
                    SimpleBlockInteraction.CODEC)
            .documentation("Insert or take items from the Rod Puller block.")
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

        RodPullerState state = RodPullerState.getAt(blockPos);
        if (state == null) return;

        ItemContainer blockIc = state.getItemContainer();
        if (blockIc == null) return;

        Ref<EntityStore> playerEnt = interactionContext.getOwningEntity();
        Store<EntityStore> store   = playerEnt.getStore();

        try {
            Player player = store.getComponent(playerEnt, Player.getComponentType());
            if (player == null) return;
            Inventory inv = player.getInventory();
            if (inv == null) return;

            ItemStack outputSlot = blockIc.getItemStack((short) 1);
            if (outputSlot != null && !outputSlot.isEmpty()) {
                takeToInventory(inv, blockIc, (short) 1);
                return;
            }

            ItemStack inputSlot = blockIc.getItemStack((short) 0);
            boolean inputEmpty = inputSlot == null || inputSlot.isEmpty();
            if (itemStack != null && !itemStack.isEmpty() && inputEmpty) {
                if (ProcessingRecipeRegistry.LATHE.findByInput(itemStack.getItemId()) != null) {
                    insertOne(inv, blockIc, itemStack);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void insertOne(Inventory inv, ItemContainer blockIc, ItemStack held) {
        if (!deductFromInventory(inv, held.getItemId(), 1)) return;
        blockIc.setItemStackForSlot((short) 0, new ItemStack(held.getItemId(), 1, null));
    }

    private static void takeToInventory(Inventory inv, ItemContainer blockIc, short slot) {
        ItemStack blockSlot = blockIc.getItemStack(slot);
        if (blockSlot == null || blockSlot.isEmpty()) return;
        String itemId = blockSlot.getItemId();
        int qty = blockSlot.getQuantity();
        int remaining = mergeIntoExisting(inv.getHotbar(),  9,  itemId, qty, 64);
        remaining = mergeIntoExisting(inv.getStorage(), 36, itemId, remaining, 64);
        remaining = placeInEmpty(inv.getHotbar(),       9,  itemId, remaining, 64);
        remaining = placeInEmpty(inv.getStorage(),      36, itemId, remaining, 64);
        int placed = qty - remaining;
        if (placed == 0) return;
        blockIc.setItemStackForSlot(slot, remaining > 0
                ? new ItemStack(itemId, remaining, null) : ItemStack.EMPTY);
    }

    private static boolean deductFromInventory(Inventory inv, String itemId, int qty) {
        for (ItemContainer ic : new ItemContainer[]{inv.getHotbar(), inv.getStorage()}) {
            if (ic == null) continue;
            int slots = ic == inv.getHotbar() ? 9 : 36;
            for (short i = 0; i < slots; i++) {
                ItemStack s = ic.getItemStack(i);
                if (s == null || s.isEmpty() || !itemId.equals(s.getItemId())) continue;
                if (s.getQuantity() >= qty) {
                    int newQty = s.getQuantity() - qty;
                    ic.setItemStackForSlot(i, newQty > 0
                            ? new ItemStack(itemId, newQty, null) : ItemStack.EMPTY);
                    return true;
                }
            }
        }
        return false;
    }

    private static int mergeIntoExisting(ItemContainer ic, int size, String itemId,
                                         int remaining, int maxStack) {
        if (ic == null || remaining <= 0) return remaining;
        for (short i = 0; i < size && remaining > 0; i++) {
            ItemStack s = ic.getItemStack(i);
            if (s == null || s.isEmpty() || !itemId.equals(s.getItemId())) continue;
            int existing = s.getQuantity();
            if (existing >= maxStack) continue;
            int adding = Math.min(maxStack - existing, remaining);
            ic.setItemStackForSlot(i, new ItemStack(itemId, existing + adding, null));
            remaining -= adding;
        }
        return remaining;
    }

    private static int placeInEmpty(ItemContainer ic, int size, String itemId,
                                    int remaining, int maxStack) {
        if (ic == null || remaining <= 0) return remaining;
        for (short i = 0; i < size && remaining > 0; i++) {
            ItemStack s = ic.getItemStack(i);
            if (s != null && !s.isEmpty()) continue;
            int placing = Math.min(remaining, maxStack);
            ic.setItemStackForSlot(i, new ItemStack(itemId, placing, null));
            remaining -= placing;
        }
        return remaining;
    }
}
