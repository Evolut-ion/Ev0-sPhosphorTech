package com.Ev0sMods.PhosphorTech.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.blocks.SieveState;
import com.Ev0sMods.PhosphorTech.mechanical.GearNetwork;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.HyUIStyle;
import au.ellie.hyui.builders.ItemIconBuilder;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PageBuilder;
import au.ellie.hyui.builders.PanelBuilder;

/** HyUI page for the Sieve block. */
@SuppressWarnings({"removal", "unchecked"})
public final class SieveUIPage {

    private SieveUIPage() {}

    private record PlayerSession(Ref<EntityStore> entityRef, Store<EntityStore> store,
                                  Vector3i blockPos, HyUIPage page) {}

    private record SlotInfo(String id, ItemContainer container, short slot) {}

    private static final ConcurrentHashMap<PlayerRef, PlayerSession> SESSIONS      = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,    Integer>        WATCHER_COUNT = new ConcurrentHashMap<>();

    public static void openForced(PlayerRef playerRef, Ref<EntityStore> entityRef,
                                  Store<EntityStore> store, Vector3i blockPos) {
        PlayerSession existing = SESSIONS.get(playerRef);
        String posKey = posKey(blockPos);
        if (existing != null && posKey(existing.blockPos()).equals(posKey)) return;
        if (existing != null) decrementWatcher(existing.blockPos());
        SESSIONS.put(playerRef, new PlayerSession(entityRef, store, blockPos, null));
        WATCHER_COUNT.merge(posKey, 1, Integer::sum);
        renderPage(playerRef, entityRef, store, blockPos);
    }

    public static boolean hasWatcher(Vector3i pos) {
        Integer c = WATCHER_COUNT.get(posKey(pos));
        return c != null && c > 0;
    }

    public static void tickRefresh(SieveState state, Store<?> store, Vector3i pos) {
        SESSIONS.forEach((playerRef, session) -> {
            if (!posKey(session.blockPos()).equals(posKey(pos))) return;
            HyUIPage page = session.page();
            if (page == null) return;
            partialRefresh(page, state);
        });
    }

    private static void renderPage(PlayerRef playerRef, Ref<EntityStore> entityRef,
                                   Store<EntityStore> store, Vector3i pos) {
        try {
            SieveState state = lookupState(pos);
            Inventory inventory = null;
            try {
                Player player = store.getComponent(entityRef, Player.getComponentType());
                if (player != null) inventory = player.getInventory();
            } catch (Throwable ignored) {}

            List<SlotInfo> slots = new ArrayList<>();
            PageBuilder builder = PageBuilder.pageForPlayer(playerRef)
                    .fromHtml(buildHtml(state, inventory, slots))
                    .withLifetime(CustomPageLifetime.CanDismissOrCloseThroughInteraction);

            builder.addEventListener("sv-close-btn", CustomUIEventBindingType.Activating,
                    (ign, ctx) -> {
                        PlayerSession s = SESSIONS.remove(playerRef);
                        if (s != null) decrementWatcher(s.blockPos());
                        ctx.getPage().ifPresent(HyUIPage::close);
                    });

            builder.onDismiss((page, playerInitiated) -> {
                PlayerSession s = SESSIONS.remove(playerRef);
                if (s != null) decrementWatcher(s.blockPos());
            });

            for (SlotInfo info : slots) {
                final ItemContainer src  = info.container();
                final short         slot = info.slot();
                final String        sid  = info.id();
                if ("sv-slot-1-btn".equals(sid) || "sv-slot-2-btn".equals(sid)
                        || "sv-slot-3-btn".equals(sid) || "sv-slot-4-btn".equals(sid)
                        || "sv-slot-5-btn".equals(sid)) {
                    final short bSlot = slot;
                    builder.addEventListener(sid, CustomUIEventBindingType.Activating,
                            (ign, ctx) -> transferOutputToPlayer(playerRef, entityRef, store, pos, bSlot));
                } else if ("sv-slot-0-btn".equals(sid)) {
                    builder.addEventListener(sid, CustomUIEventBindingType.Activating,
                            (ign, ctx) -> takeFromBlock(playerRef, entityRef, store, pos, (short) 0));
                } else {
                    builder.addEventListener(sid, CustomUIEventBindingType.Activating,
                            (ign, ctx) -> transferItem(playerRef, entityRef, store, pos, src, slot, (short) 0));
                }
            }

            PlayerSession prev = SESSIONS.get(playerRef);
            if (prev != null && prev.page() != null) {
                try { prev.page().close(); } catch (Throwable ignored) {}
            }

            HyUIPage page = builder.open(store);
            SESSIONS.compute(playerRef, (k, s) -> s == null ? null
                    : new PlayerSession(s.entityRef(), s.store(), s.blockPos(), page));
        } catch (Throwable t) {
            SESSIONS.remove(playerRef);
            HytaleLogger.getLogger().atWarning().log("[SieveUI] renderPage failed: " + t);
        }
    }

    private static void partialRefresh(HyUIPage page, SieveState state) {
        if (page == null || state == null) return;

        int jFill = (int)(288.0 * state.joulesStored / SieveState.J_CAPACITY);
        page.getById("sv-j-fill", PanelBuilder.class).ifPresent(b -> b.withContentWidth(jFill));
        page.getById("sv-j-val", LabelBuilder.class)
                .ifPresent(b -> b.withText(String.format("%.1f / %.0f J",
                        state.joulesStored, SieveState.J_CAPACITY)));

        boolean stalled = state.currentSpeed < SieveState.MIN_SPEED;
        String speedColor = stalled ? "#ef9a9a" : "#a5d6a7";
        String speedText  = !state.processing ? ""
                : (stalled ? "Stalled (no power)" : String.format("Speed: %.1f", state.currentSpeed));
        page.getById("sv-speed-val", LabelBuilder.class)
                .ifPresent(b -> b.withText(speedText).withStyle(new HyUIStyle().setTextColor(speedColor)));

        int progFill = state.processing ? (int)(288.0 * state.processTimer / Math.max(1, state.ticksNeeded)) : 0;
        page.getById("sv-prog-fill", PanelBuilder.class).ifPresent(b -> b.withContentWidth(progFill));
        String progText = buildProgText(state.processing, state.processTimer, state.ticksNeeded);
        page.getById("sv-prog-text", LabelBuilder.class).ifPresent(b -> b.withText(progText));

        String statusText  = state.processing ? (stalled ? "Stalled" : "Processing...") : "Idle";
        String statusColor = state.processing ? (stalled ? "#ef9a9a" : "#a5d6a7") : "#ef9a9a";
        page.getById("sv-status-val", LabelBuilder.class)
                .ifPresent(b -> b.withText(statusText).withStyle(new HyUIStyle().setTextColor(statusColor)));

        ItemContainer ic = state.getItemContainer();
        for (short i = 0; i <= 5; i++) {
            updateSlotIcon(page, ic, i, "sv-slot-" + i);
        }
        page.updatePage(false);
    }

    private static void updateSlotIcon(HyUIPage page, ItemContainer ic, short slot, String slotId) {
        if (ic == null) return;
        ItemStack stack = ic.getItemStack(slot);
        boolean has = stack != null && !stack.isEmpty();
        String id   = has ? stack.getItemId() : "";
        String name = has ? prettify(stack.getItemId()) : "(empty)";
        String qty  = has ? "x" + stack.getQuantity() : "";
        page.getById(slotId + "-icon", ItemIconBuilder.class).ifPresent(b -> b.withItemId(id));
        page.getById(slotId + "-name", LabelBuilder.class).ifPresent(lb -> lb.withText(name));
        page.getById(slotId + "-qty",  LabelBuilder.class).ifPresent(lb -> lb.withText(qty));
    }

    private static String buildProgText(boolean processing, int timer, int ticks) {
        if (!processing || ticks <= 0) return "[--------------------] 0%";
        int pct = Math.min(100, (int)(100.0 * timer / ticks));
        int filled = pct / 5;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 20; i++) sb.append(i < filled ? '=' : '-');
        sb.append("] ").append(pct).append('%');
        return sb.toString();
    }

    private static String buildHtml(SieveState state, Inventory inv, List<SlotInfo> slotsOut) {
        boolean processing = state != null && state.processing;
        boolean stalled    = state != null && state.currentSpeed < SieveState.MIN_SPEED;
        String statusColor = processing ? (stalled ? "#ef9a9a" : "#a5d6a7") : "#ef9a9a";
        String statusText  = processing ? (stalled ? "Stalled" : "Processing...") : "Idle";
        String speedColor  = stalled ? "#ef9a9a" : "#a5d6a7";
        String speedText   = !processing ? ""
                : (stalled ? "Stalled (no power)" : String.format("Speed: %.1f",
                   state != null ? state.currentSpeed : 0.0));
        int jFill = state != null
                ? (int)(288.0 * state.joulesStored / SieveState.J_CAPACITY) : 0;
        String jText = state != null
                ? String.format("%.1f / %.0f J", state.joulesStored, SieveState.J_CAPACITY)
                : "0.0 / 20 J";
        String progText = buildProgText(processing,
                state != null ? state.processTimer : 0,
                state != null ? state.ticksNeeded  : 1);

        // Gather slot data (slots 0-5)
        String[] slotIds  = new String[6];
        int[]    slotQtys = new int[6];
        if (state != null) {
            ItemContainer ic = state.getItemContainer();
            if (ic != null) {
                for (short i = 0; i <= 5; i++) {
                    ItemStack s = ic.getItemStack(i);
                    if (s != null && !s.isEmpty()) { slotIds[i] = s.getItemId(); slotQtys[i] = s.getQuantity(); }
                }
                slotsOut.add(new SlotInfo("sv-slot-0-btn", ic, (short) 0));
                slotsOut.add(new SlotInfo("sv-slot-1-btn", ic, (short) 1));
                slotsOut.add(new SlotInfo("sv-slot-2-btn", ic, (short) 2));
                slotsOut.add(new SlotInfo("sv-slot-3-btn", ic, (short) 3));
                slotsOut.add(new SlotInfo("sv-slot-4-btn", ic, (short) 4));
                slotsOut.add(new SlotInfo("sv-slot-5-btn", ic, (short) 5));
            }
        }

        String inventoryHtml = buildInventoryHtml(inv, slotsOut);

        String leftPanel = String.format("""
                <div style="layout-mode: Top; anchor-width: 530; padding-top: 8; padding-bottom: 8;
                            padding-left: 16; padding-right: 16;">
                    <p class="title-label">Sieve</p>
                    <div class="separator"></div>
                    <p class="section-label">Status</p>
                    <p id="sv-status-val" class="info-label" style="color: %s;">%s</p>
                    <p id="sv-speed-val" class="info-label" style="color: %s;">%s</p>
                    <div class="separator"></div>
                    <p class="section-label">Joules</p>
                    <div style="layout-mode: Left; anchor-width: 288; anchor-height: 18;
                                background-color: #1a1a2e; border-radius: 9; margin-top: 4; margin-bottom: 4;">
                        <div id="sv-j-fill" style="anchor-width: %d; anchor-height: 18;
                             background-color: #ffa726; border-radius: 9;"></div>
                    </div>
                    <p id="sv-j-val" class="info-label">%s</p>
                    <div class="separator"></div>
                    <p class="section-label">Progress</p>
                    <p id="sv-prog-text" class="info-label" style="margin-top: 4; margin-bottom: 4;">%s</p>
                    <p class="hint-label">Processes input using rotary Joule power. Requires at least speed 1 to operate.</p>
                    <div style="layout-mode: Top; horizontal-align: center; padding-top: 8;">
                        <button id="sv-close-btn" class="secondary-button"
                                style="anchor-width: 120; anchor-height: 30; font-size: 13; color: #e57373;">
                            Close</button>
                    </div>
                    %s
                </div>
                """, statusColor, statusText, speedColor, speedText, jFill, jText, progText, inventoryHtml);

        String rightPanel = String.format("""
                <div style="layout-mode: Top; anchor-width: 210; padding-top: 8; padding-bottom: 8;
                            padding-left: 16; padding-right: 16;">
                    <p class="section-label">Slots</p>
                    <div class="separator"></div>
                    %s
                    <p class="hint-label" style="horizontal-align: center;">v</p>
                    %s
                    <p class="hint-label" style="horizontal-align: center;">[Chance Outputs]</p>
                    %s
                    %s
                    %s
                    %s
                </div>
                """,
                buildInputSlotHtml(slotIds[0], slotQtys[0], "Input",   "sv-slot-0"),
                buildOutputSlotHtml(slotIds[1], slotQtys[1], "Output",  "sv-slot-1"),
                buildOutputSlotHtml(slotIds[2], slotQtys[2], "Bonus 1", "sv-slot-2"),
                buildOutputSlotHtml(slotIds[3], slotQtys[3], "Bonus 2", "sv-slot-3"),
                buildOutputSlotHtml(slotIds[4], slotQtys[4], "Bonus 3", "sv-slot-4"),
                buildOutputSlotHtml(slotIds[5], slotQtys[5], "Bonus 4", "sv-slot-5"));

        return STYLE + String.format("""
                <div style="anchor-width: 100%%; anchor-height: 100%%;
                            horizontal-align: center; vertical-align: middle;">
                    <div class="decorated-container" data-hyui-title="Sieve"
                         style="anchor-height: 900; anchor-width: 860;">
                        <div class="container-contents"
                             style="layout-mode: Top; padding-top: 12; padding-bottom: 12;
                                    padding-left: 16; padding-right: 16; horizontal-align: center;">
                            <div style="layout-mode: Left; horizontal-align: center;">
                %s
                                <div class="vert-separator"></div>
                %s
                            </div>
                        </div>
                    </div>
                </div>
                """, leftPanel, rightPanel);
    }

    private static String buildInputSlotHtml(String itemId, int qty, String label, String containerId) {
        String dispId  = itemId != null ? itemId : "";
        String name    = itemId != null ? prettify(itemId) : "(empty)";
        String qtyText = itemId != null ? "x" + qty : "";
        return String.format("""
                <div style="layout-mode: Top; horizontal-align: center;
                            padding-top: 4; padding-bottom: 8;">
                    <p class="slot-label">%s</p>
                    <button id="%s-btn" style="anchor-width: 52; anchor-height: 52;">
                        <span id="%s-icon" class="item-icon" data-hyui-item-id="%s"
                              style="anchor-width: 48; anchor-height: 48;"></span>
                    </button>
                    <p id="%s-name" class="slot-item-name">%s</p>
                    <p id="%s-qty"  class="slot-item-qty">%s</p>
                </div>
                """, label, containerId, containerId, dispId,
                     containerId, name, containerId, qtyText);
    }

    private static String buildOutputSlotHtml(String itemId, int qty, String label, String containerId) {
        String dispId  = itemId != null ? itemId : "";
        String name    = itemId != null ? prettify(itemId) : "(empty)";
        String qtyText = itemId != null ? "x" + qty : "";
        return String.format("""
                <div style="layout-mode: Top; horizontal-align: center;
                            padding-top: 4; padding-bottom: 4;">
                    <p class="slot-label">%s</p>
                    <button id="%s-btn" style="anchor-width: 52; anchor-height: 52;">
                        <span id="%s-icon" class="item-icon" data-hyui-item-id="%s"
                              style="anchor-width: 48; anchor-height: 48;"></span>
                    </button>
                    <p id="%s-name" class="slot-item-name">%s</p>
                    <p id="%s-qty"  class="slot-item-qty">%s</p>
                </div>
                """, label, containerId, containerId, dispId,
                     containerId, name, containerId, qtyText);
    }

    private static String buildInventoryHtml(Inventory inventory, List<SlotInfo> slotsOut) {
        if (inventory == null) return "";
        try {
            ItemContainer hotbar  = inventory.getHotbar();
            ItemContainer storage = inventory.getStorage();
            int cols = 9;
            StringBuilder sb = new StringBuilder();
            sb.append("<div class=\"separator\"></div>\n");
            sb.append("<div style=\"layout-mode: Top; padding-top: 6; padding-bottom: 8; "
                    + "padding-left: 8; padding-right: 8;\">\n");
            sb.append("<p class=\"section-label\" style=\"horizontal-align: center;\">Player Inventory</p>\n");
            sb.append("<div style=\"layout-mode: Left; anchor-width: 468; anchor-height: 52;\">\n");
            for (short i = 0; i < cols; i++) sb.append(miniSlotHtml(hotbar, i, "inv_h_" + i, slotsOut));
            sb.append("</div>\n");
            for (int row = 0; row < 4; row++) {
                sb.append("<div style=\"layout-mode: Left; anchor-width: 468; anchor-height: 52;\">\n");
                for (int col = 0; col < cols; col++) {
                    short idx = (short)(row * cols + col);
                    sb.append(miniSlotHtml(storage, idx, "inv_s_" + idx, slotsOut));
                }
                sb.append("</div>\n");
            }
            sb.append("</div>\n");
            return sb.toString();
        } catch (Throwable ignored) { return ""; }
    }

    private static String miniSlotHtml(ItemContainer container, short slotIndex,
                                       String slotId, List<SlotInfo> slotsOut) {
        String base = "anchor-width: 48; anchor-height: 48; margin-top: 2; margin-bottom: 2; "
                    + "margin-left: 2; margin-right: 2;";
        if (container != null) slotsOut.add(new SlotInfo(slotId, container, slotIndex));
        String itemId = "";
        try {
            ItemStack s = container != null ? container.getItemStack(slotIndex) : null;
            if (s != null && !s.isEmpty()) itemId = s.getItemId();
        } catch (Throwable ignored) {}
        return "<button id=\"" + slotId + "\" style=\"" + base + "\">"
             + "<span id=\"" + slotId + "-icon\" class=\"item-icon\" data-hyui-item-id=\"" + itemId
             + "\" style=\"anchor-width: 40; anchor-height: 40;\"></span>"
             + "</button>\n";
    }

    private static void transferItem(PlayerRef playerRef, Ref<EntityStore> entityRef,
                                     Store<EntityStore> store, Vector3i pos,
                                     ItemContainer srcContainer, short srcSlot, short targetSlot) {
        try {
            ItemStack moving = srcContainer.getItemStack(srcSlot);
            if (moving == null || moving.isEmpty()) return;
            SieveState state = lookupState(pos);
            if (state == null) return;
            ItemContainer blockIc = state.getItemContainer();
            if (blockIc == null) return;
            blockIc.setItemStackForSlot(targetSlot, moving);
            srcContainer.setItemStackForSlot(srcSlot, ItemStack.EMPTY);
            state.uiDirty = true;
            PlayerSession s = SESSIONS.get(playerRef);
            if (s != null && s.page() != null) {
                HyUIPage page = s.page();
                updateSlotIcon(page, blockIc, targetSlot, "sv-slot-" + targetSlot);
                try {
                    Player player = store.getComponent(s.entityRef(), Player.getComponentType());
                    if (player != null && player.getInventory() != null)
                        refreshAllInvSlots(page, player.getInventory());
                } catch (Throwable ignored2) {}
                page.updatePage(false);
            }
        } catch (Throwable ignored) {}
    }

    private static void takeFromBlock(PlayerRef playerRef, Ref<EntityStore> entityRef,
                                      Store<EntityStore> store, Vector3i pos, short blockSlot) {
        try {
            SieveState state = lookupState(pos);
            if (state == null) return;
            ItemContainer blockIc = state.getItemContainer();
            if (blockIc == null) return;
            ItemStack item = blockIc.getItemStack(blockSlot);
            if (item == null || item.isEmpty()) return;
            PlayerSession ps = SESSIONS.get(playerRef);
            if (ps == null) return;
            Player player = store.getComponent(ps.entityRef(), Player.getComponentType());
            if (player == null) return;
            Inventory inv = player.getInventory();
            if (inv == null) return;
            int remaining = item.getQuantity();
            remaining = mergeIntoExisting(inv.getHotbar(),  9,  item.getItemId(), remaining, 64);
            remaining = mergeIntoExisting(inv.getStorage(), 36, item.getItemId(), remaining, 64);
            remaining = placeInEmpty(inv.getHotbar(),  9,  item.getItemId(), remaining, 64);
            remaining = placeInEmpty(inv.getStorage(), 36, item.getItemId(), remaining, 64);
            if (remaining == item.getQuantity()) return;
            ItemStack updated = remaining > 0 ? new ItemStack(item.getItemId(), remaining, null) : ItemStack.EMPTY;
            blockIc.setItemStackForSlot(blockSlot, updated);
            state.uiDirty = true;
            if (ps.page() != null) {
                HyUIPage page = ps.page();
                updateSlotIcon(page, blockIc, blockSlot, "sv-slot-" + blockSlot);
                refreshAllInvSlots(page, inv);
                page.updatePage(false);
            }
        } catch (Throwable ignored) {}
    }

    private static void transferOutputToPlayer(PlayerRef playerRef, Ref<EntityStore> entityRef,
                                               Store<EntityStore> store, Vector3i pos, short blockSlot) {
        try {
            SieveState state = lookupState(pos);
            if (state == null) return;
            ItemContainer blockIc = state.getItemContainer();
            if (blockIc == null) return;
            ItemStack output = blockIc.getItemStack(blockSlot);
            if (output == null || output.isEmpty()) return;
            Player player = store.getComponent(entityRef, Player.getComponentType());
            if (player == null) return;
            Inventory inventory = player.getInventory();
            if (inventory == null) return;
            ItemContainer hotbar  = inventory.getHotbar();
            ItemContainer stg     = inventory.getStorage();
            String itemId   = output.getItemId();
            int remaining   = output.getQuantity();
            final int MAX   = 64;
            remaining = mergeIntoExisting(hotbar, 9,  itemId, remaining, MAX);
            remaining = mergeIntoExisting(stg,   36, itemId, remaining, MAX);
            remaining = placeInEmpty(hotbar, 9,  itemId, remaining, MAX);
            remaining = placeInEmpty(stg,   36, itemId, remaining, MAX);
            if (remaining == output.getQuantity()) return;
            ItemStack updated = remaining > 0 ? new ItemStack(itemId, remaining, null) : ItemStack.EMPTY;
            blockIc.setItemStackForSlot(blockSlot, updated);
            state.uiDirty = true;
            PlayerSession s2 = SESSIONS.get(playerRef);
            if (s2 != null && s2.page() != null) {
                partialRefresh(s2.page(), state);
                refreshAllInvSlots(s2.page(), inventory);
                s2.page().updatePage(false);
            }
        } catch (Throwable ignored) {}
    }

    private static short tryPlaceInContainer(ItemContainer container, ItemStack item) {
        if (container == null) return -1;
        for (short i = 0; i < 36; i++) {
            ItemStack existing = container.getItemStack(i);
            if (existing == null || existing.isEmpty()) {
                container.setItemStackForSlot(i, item);
                return i;
            }
        }
        return -1;
    }

    private static int mergeIntoExisting(ItemContainer ic, int size, String itemId,
                                         int remaining, int maxStack) {
        if (ic == null || remaining <= 0) return remaining;
        for (short i = 0; i < size && remaining > 0; i++) {
            ItemStack slot = ic.getItemStack(i);
            if (slot == null || slot.isEmpty() || !itemId.equals(slot.getItemId())) continue;
            int existing = slot.getQuantity();
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
            ItemStack slot = ic.getItemStack(i);
            if (slot != null && !slot.isEmpty()) continue;
            int placing = Math.min(remaining, maxStack);
            ic.setItemStackForSlot(i, new ItemStack(itemId, placing, null));
            remaining -= placing;
        }
        return remaining;
    }

    private static void refreshAllInvSlots(HyUIPage page, Inventory inv) {
        try {
            ItemContainer hotbar = inv.getHotbar();
            if (hotbar != null) {
                for (short i = 0; i < 9; i++) {
                    ItemStack s = hotbar.getItemStack(i);
                    String id = (s != null && !s.isEmpty()) ? s.getItemId() : "";
                    final String eid = "inv_h_" + i + "-icon";
                    page.getById(eid, ItemIconBuilder.class).ifPresent(b -> b.withItemId(id));
                }
            }
            ItemContainer storage = inv.getStorage();
            if (storage != null) {
                for (short i = 0; i < 36; i++) {
                    ItemStack s = storage.getItemStack(i);
                    String id = (s != null && !s.isEmpty()) ? s.getItemId() : "";
                    final String eid = "inv_s_" + i + "-icon";
                    page.getById(eid, ItemIconBuilder.class).ifPresent(b -> b.withItemId(id));
                }
            }
        } catch (Throwable ignored) {}
    }

    private static SieveState lookupState(Vector3i pos) {
        SieveState s = SieveState.REGISTRY.get(posKey(pos));
        if (s != null) return s;
        Object node = GearNetwork.getAt(pos);
        if (node instanceof SieveState ss) return ss;
        return null;
    }

    private static String posKey(Vector3i v) { return v.x + "," + v.y + "," + v.z; }

    private static void decrementWatcher(Vector3i pos) {
        String key = posKey(pos);
        WATCHER_COUNT.merge(key, -1, (a, b) -> (a + b <= 0) ? null : a + b);
    }

    private static String prettify(String id) {
        if (id == null || id.isEmpty()) return "(empty)";
        return id.replace('_', ' ').replace('-', ' ');
    }

    private static final String STYLE = """
            <style>
                .title-label { font-weight: bold; color: #ffb74d; font-size: 18; padding-top: 8; padding-bottom: 6; }
                .section-label { font-weight: bold; color: #bdcbd3; font-size: 14; padding-top: 6; padding-bottom: 2; }
                .info-label { color: #a0b8c8; font-size: 12; padding-top: 2; padding-bottom: 2; }
                .hint-label { color: #7a9aaa; font-size: 11; padding-top: 2; padding-bottom: 2; }
                .slot-label { font-weight: bold; color: #bdcbd3; font-size: 13; padding-bottom: 2; horizontal-align: center; }
                .slot-item-name { color: #c8dbe8; font-size: 13; font-weight: bold; padding-bottom: 2; }
                .slot-item-qty  { color: #a0b8c8; font-size: 12; }
                .separator { layout-mode: Full; anchor-height: 1; background-color: #ffffff(0.15); margin-top: 6; margin-bottom: 6; }
                .vert-separator { anchor-width: 1; layout-mode: Full; background-color: #ffffff(0.15); margin-left: 6; margin-right: 6; }
                .secondary-button { background-color: #1565c0; border-radius: 8; padding-top: 6; padding-bottom: 6; padding-left: 16; padding-right: 16; color: #e3f2fd; }
            </style>
            """;
}
