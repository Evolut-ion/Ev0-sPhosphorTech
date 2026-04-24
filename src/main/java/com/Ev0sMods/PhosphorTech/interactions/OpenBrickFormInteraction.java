package com.Ev0sMods.PhosphorTech.interactions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.blocks.BrickFormState;
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
 * "OpenBrickForm" interaction — no UI, pure inventory logic.
 *
 * <p>Press F on a Brick Form block:
 * <ul>
 *   <li>Holding clay or coke brick mix and form is not yet processing → inserts
 *       <em>one item</em> per interaction (up to 4 total).</li>
 *   <li>Processing is locked while the 60-tick timer runs.</li>
 *   <li>Once processing is done, pressing F returns the output to inventory and
 *       resets the block.</li>
 * </ul>
 */
public final class OpenBrickFormInteraction extends CompatSimpleBlockInteraction {

    public static final BuilderCodec<OpenBrickFormInteraction> CODEC =
            BuilderCodec.builder(
                    OpenBrickFormInteraction.class,
                    OpenBrickFormInteraction::new,
                    SimpleBlockInteraction.CODEC)
            .documentation("Insert or take items from the Brick Form block.")
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

        BrickFormState state = BrickFormState.getAt(blockPos);
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

            boolean holdingInput = itemStack != null && !itemStack.isEmpty()
                    && (BrickFormState.CLAY_ITEM_ID.equals(itemStack.getItemId())
                     || BrickFormState.MIX_ITEM_ID.equals(itemStack.getItemId()));

            if (holdingInput && state.canInsert()) {
                // Insert exactly one item
                insertOne(inv, blockIc, state, itemStack);
            } else {
                // Try to take — allowed regardless of what the player is holding,
                // but still locked while the 60-tick timer is mid-run.
                if (!state.canExtract()) return;
                takeToInventory(world, inv, blockIc, state);
            }
        } catch (Throwable ignored) {}
    }

    private static void insertOne(Inventory inv, ItemContainer blockIc,
                                   BrickFormState state, ItemStack held) {
        ItemStack blockSlot = blockIc.getItemStack((short) 0);
        int currentQty = (blockSlot != null && !blockSlot.isEmpty()) ? blockSlot.getQuantity() : 0;
        String blockItemId = (blockSlot != null && !blockSlot.isEmpty()) ? blockSlot.getItemId() : null;

        // Mixed types not allowed
        if (blockItemId != null && !blockItemId.equals(held.getItemId())) return;
        if (currentQty >= BrickFormState.REQUIRED_QUANTITY) return;

        // Only insert 1 per interaction
        if (!deductFromInventory(inv, held.getItemId(), 1)) return;

        blockIc.setItemStackForSlot((short) 0,
                new ItemStack(held.getItemId(), currentQty + 1, null));
    }

    private static void takeToInventory(World world, Inventory inv,
                                          ItemContainer blockIc, BrickFormState state) {
        ItemStack blockSlot = blockIc.getItemStack((short) 0);
        if (blockSlot == null || blockSlot.isEmpty()) return;

        String itemId = blockSlot.getItemId();
        int    qty    = blockSlot.getQuantity();

        int remaining = mergeIntoExisting(inv.getHotbar(),  9,  itemId, qty,       64);
        remaining = mergeIntoExisting(inv.getStorage(), 36, itemId, remaining, 64);
        remaining = placeInEmpty(inv.getHotbar(),       9,  itemId, remaining, 64);
        remaining = placeInEmpty(inv.getStorage(),      36, itemId, remaining, 64);

        int placed = qty - remaining;
        if (placed == 0) return;

        if (remaining > 0) {
            blockIc.setItemStackForSlot((short) 0, new ItemStack(itemId, remaining, null));
        } else {
            blockIc.setItemStackForSlot((short) 0, ItemStack.EMPTY);
            // Reset processing state and visual so the form is ready for a new batch
            state.processingTick = -1;
            state.resetVisual(world);
        }
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

