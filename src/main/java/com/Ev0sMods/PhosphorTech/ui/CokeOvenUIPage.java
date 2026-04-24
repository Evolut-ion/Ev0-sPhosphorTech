package com.Ev0sMods.PhosphorTech.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.blocks.CokeOvenState;
import com.Ev0sMods.PhosphorTech.fluid.FluidNetwork;
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

/**
 * HyUI page for the Coke Oven block.
 *
 * <p>Left panel: status, progress, creosote tank, heat-bonus row, close button.<br>
 * Right panel: charcoal input slot and coal coke output slot.<br>
 * Bottom: full player inventory (hotbar + storage).
 */
@SuppressWarnings({"removal", "unchecked"})
public final class CokeOvenUIPage {

    private CokeOvenUIPage() {}

    // ── Session tracking ──────────────────────────────────────────────────────

    private record PlayerSession(Ref<EntityStore> entityRef, Store<EntityStore> store,
                                  Vector3i blockPos, HyUIPage page) {}

    private record SlotInfo(String id, ItemContainer container, short slot) {}

    private static final ConcurrentHashMap<PlayerRef, PlayerSession> SESSIONS      = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,    Integer>        WATCHER_COUNT = new ConcurrentHashMap<>();

    // ── Open / close ──────────────────────────────────────────────────────────

    public static void openForced(PlayerRef playerRef, Ref<EntityStore> entityRef,
                                  Store<EntityStore> store, Vector3i pos) {
        String posKey = posKey(pos);
        PlayerSession existing = SESSIONS.get(playerRef);
        if (existing != null && posKey(existing.blockPos()).equals(posKey)) return;
        if (existing != null) decrementWatcher(existing.blockPos());
        SESSIONS.put(playerRef, new PlayerSession(entityRef, store, pos, null));
        WATCHER_COUNT.merge(posKey, 1, Integer::sum);
        renderPage(playerRef, entityRef, store, pos);
    }

    public static boolean hasWatcher(Vector3i pos) {
        Integer c = WATCHER_COUNT.get(posKey(pos));
        return c != null && c > 0;
    }

    public static void tickRefresh(CokeOvenState state, Store<?> store, Vector3i pos) {
        SESSIONS.forEach((playerRef, session) -> {
            if (!posKey(session.blockPos()).equals(posKey(pos))) return;
            HyUIPage page = session.page();
            if (page == null) return;
            partialRefresh(page, state);
        });
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private static void renderPage(PlayerRef playerRef, Ref<EntityStore> entityRef,
                                   Store<EntityStore> store, Vector3i pos) {
        try {
            CokeOvenState state = lookupState(pos);
            Inventory inventory = null;
            try {
                Player player = store.getComponent(entityRef, Player.getComponentType());
                if (player != null) inventory = player.getInventory();
            } catch (Throwable ignored) {}

            List<SlotInfo> slots = new ArrayList<>();
            PageBuilder builder = PageBuilder.pageForPlayer(playerRef)
                    .fromHtml(buildHtml(state, inventory, slots))
                    .withLifetime(CustomPageLifetime.CanDismissOrCloseThroughInteraction);

            builder.addEventListener("co-close-btn", CustomUIEventBindingType.Activating,
                    (ign, ctx) -> {
                        PlayerSession s = SESSIONS.remove(playerRef);
                        if (s != null) decrementWatcher(s.blockPos());
                        ctx.getPage().ifPresent(HyUIPage::close);
                    });

            builder.onDismiss((page, playerInitiated) -> {
                PlayerSession s = SESSIONS.remove(playerRef);
                if (s != null) decrementWatcher(s.blockPos());
            });

            // Slot interaction listeners
            for (SlotInfo info : slots) {
                final ItemContainer src  = info.container();
                final short         slot = info.slot();
                final String        sid  = info.id();
                if ("co-slot-1-btn".equals(sid)) {
                    // Output slot → transfer to player
                    builder.addEventListener(sid, CustomUIEventBindingType.Activating,
                            (ign, ctx) -> transferOutputToPlayer(playerRef, entityRef, store, pos, (short) 1));
                } else if ("co-slot-0-btn".equals(sid)) {
                    // Input slot — clicking takes the item back to player
                    builder.addEventListener(sid, CustomUIEventBindingType.Activating,
                            (ign, ctx) -> takeFromBlock(playerRef, entityRef, store, pos, (short) 0));
                } else {
                    // Inventory slot → transfer charcoal into input slot 0
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
            HytaleLogger.getLogger().atWarning().log("[CokeOvenUI] renderPage failed: " + t);
        }
    }

    // ── Incremental update ────────────────────────────────────────────────────

    private static void partialRefresh(HyUIPage page, CokeOvenState state) {
        if (page == null || state == null) return;

        String statusColor = state.processing ? "#a5d6a7" : "#ef9a9a";
        String statusText  = state.processing ? "Processing..." : "Idle";
        page.getById("co-status-val", LabelBuilder.class)
                .ifPresent(b -> b.withText(statusText).withStyle(new HyUIStyle().setTextColor(statusColor)));

        int progFill = state.processing
                ? (int)(288.0 * state.processTimer / CokeOvenState.TICKS_PER_COKE)
                : 0;
        int pct = state.processing
                ? Math.min(100, (int)(100.0 * state.processTimer / CokeOvenState.TICKS_PER_COKE))
                : 0;
        page.getById("co-prog-fill", PanelBuilder.class).ifPresent(p -> p.withContentWidth(progFill));
        page.getById("co-prog-text", LabelBuilder.class).ifPresent(lb -> lb.withText(buildProgText(
                state.processing, state.processTimer, CokeOvenState.TICKS_PER_COKE)));

        int cbarW = (int)(288.0 * state.creosoteStored / CokeOvenState.CREOSOTE_CAPACITY);
        page.getById("co-fluid-fill", PanelBuilder.class).ifPresent(p -> p.withContentWidth(cbarW));
        page.getById("co-fluid-label", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(state.creosoteStored + " / "
                        + CokeOvenState.CREOSOTE_CAPACITY + " mB"));

        int heatSources = CokeOvenState.countAimedHeatSources(state.getPosition());
        int bonusMb = heatSources * CokeOvenState.CREOSOTE_PER_HEATER;
        page.getById("co-heat-val", LabelBuilder.class).ifPresent(lb ->
                lb.withText("+" + bonusMb + " mB / cycle  (" + heatSources + " sources)"));

        updateSlotIcon(page, state.getItemContainer(), (short) 0, "co-slot-0");
        updateSlotIcon(page, state.getItemContainer(), (short) 1, "co-slot-1");
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

    // ── HTML builder ──────────────────────────────────────────────────────────

    private static String buildProgText(boolean processing, int timer, int ticks) {
        if (!processing || ticks <= 0) return "[░░░░░░░░░░░░░░░░░░░░] 0%";
        int pct    = Math.min(100, (int)(100.0 * timer / ticks));
        int filled = pct / 5;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 20; i++) sb.append(i < filled ? '█' : '░');
        sb.append("] ").append(pct).append('%');
        return sb.toString();
    }

    private static String buildHtml(CokeOvenState state, Inventory inv, List<SlotInfo> slotsOut) {
        boolean processing = state != null && state.processing;
        String statusColor = processing ? "#a5d6a7" : "#ef9a9a";
        String statusText  = processing ? "Processing..." : "Idle";

        int     timer     = (state != null) ? state.processTimer   : 0;
        int     creosote  = (state != null) ? state.creosoteStored : 0;
        int     progFill  = processing ? (int)(288.0 * timer / CokeOvenState.TICKS_PER_COKE) : 0;
        int     cbarW     = (int)(288.0 * creosote / CokeOvenState.CREOSOTE_CAPACITY);
        String  progText  = buildProgText(processing, timer, CokeOvenState.TICKS_PER_COKE);
        String  fluidText = creosote + " / " + CokeOvenState.CREOSOTE_CAPACITY + " mB";

        int heatSources = (state != null)
                ? CokeOvenState.countAimedHeatSources(state.getPosition()) : 0;
        int bonusMb     = heatSources * CokeOvenState.CREOSOTE_PER_HEATER;
        String heatText = "+" + bonusMb + " mB / cycle  (" + heatSources + " sources)";

        String in0Id = null; int in0Qty = 0;
        String out1Id = null; int out1Qty = 0;
        if (state != null) {
            ItemContainer ic = state.getItemContainer();
            if (ic != null) {
                ItemStack s0 = ic.getItemStack((short) 0);
                if (s0 != null && !s0.isEmpty()) { in0Id  = s0.getItemId(); in0Qty  = s0.getQuantity(); }
                ItemStack s1 = ic.getItemStack((short) 1);
                if (s1 != null && !s1.isEmpty()) { out1Id = s1.getItemId(); out1Qty = s1.getQuantity(); }
                slotsOut.add(new SlotInfo("co-slot-0-btn", ic, (short) 0));
                slotsOut.add(new SlotInfo("co-slot-1-btn", ic, (short) 1));
            }
        }

        String leftPanel = String.format("""
                <div style="layout-mode: Top; anchor-width: 330; padding-top: 8; padding-bottom: 8;
                            padding-left: 16; padding-right: 16;">
                    <p class="title-label">&#128293; Coke Oven</p>
                    <div class="separator"></div>
                    <p class="section-label">Status</p>
                    <p id="co-status-val" class="info-label" style="color: %s;">%s</p>
                    <div class="separator"></div>
                    <p class="section-label">Progress</p>
                    <div style="layout-mode: Left; anchor-width: 288; anchor-height: 18;
                                background-color: #1a0d00; border-radius: 9; margin-top: 4; margin-bottom: 4;">
                        <div id="co-prog-fill" style="anchor-width: %d; anchor-height: 18;
                             background-color: #ff8f00; border-radius: 9;"></div>
                    </div>
                    <p id="co-prog-text" class="info-label">%s</p>
                    <div class="separator"></div>
                    <p class="section-label">Creosote Tank</p>
                    <div style="layout-mode: Left; anchor-width: 288; anchor-height: 18;
                                background-color: #001a00; border-radius: 9; margin-top: 4; margin-bottom: 4;">
                        <div id="co-fluid-fill" style="anchor-width: %d; anchor-height: 18;
                             background-color: #558b2f; border-radius: 9;"></div>
                    </div>
                    <p id="co-fluid-label" class="info-label">%s</p>
                    <div class="separator"></div>
                    <p class="section-label">Heat Bonus</p>
                    <p id="co-heat-val" class="info-label" style="color: #ffcc80;">%s</p>
                    <p class="hint-label">Heaters &amp; Bellows aimed at this block add +%d mB each.</p>
                    <div style="layout-mode: Top; horizontal-align: center; padding-top: 8;">
                        <button id="co-close-btn" class="secondary-button"
                                style="anchor-width: 120; anchor-height: 30; font-size: 13; color: #e57373;">
                            &#x2715; Close</button>
                    </div>
                </div>
                """,
                statusColor, statusText,
                progFill, progText,
                cbarW, fluidText,
                heatText, CokeOvenState.CREOSOTE_PER_HEATER);

        String rightPanel = String.format("""
                <div style="layout-mode: Top; anchor-width: 210; padding-top: 8; padding-bottom: 8;
                            padding-left: 16; padding-right: 16;">
                    <p class="section-label">Slots</p>
                    <div class="separator"></div>
                    %s
                    <p class="hint-label" style="horizontal-align: center;">&#9660;</p>
                    %s
                </div>
                """,
                buildSlotHtml(in0Id,  in0Qty,  "Input (Charcoal)", "co-slot-0"),
                buildOutputSlotHtml(out1Id, out1Qty, "Output (Coal Coke)", "co-slot-1"));

        String inventoryHtml = buildInventoryHtml(inv, slotsOut);

        return STYLE + String.format("""
                <div style="anchor-width: 100%%; anchor-height: 100%%;
                            horizontal-align: center; vertical-align: middle;">
                    <div class="decorated-container" data-hyui-title="Coke Oven"
                         style="anchor-height: 900; anchor-width: 640;">
                        <div class="container-contents"
                             style="layout-mode: Top; padding-top: 12; padding-bottom: 12;
                                    padding-left: 16; padding-right: 16; horizontal-align: center;">
                            <div style="layout-mode: Left; horizontal-align: center;">
                %s
                                <div class="vert-separator"></div>
                %s
                            </div>
                %s
                        </div>
                    </div>
                </div>
                """, leftPanel, rightPanel, inventoryHtml);
    }

    private static String buildSlotHtml(String itemId, int qty, String label, String containerId) {
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
                            padding-top: 4; padding-bottom: 8;
                            background-color: #1a2a1a; border-radius: 6;">
                    <p class="slot-label">%s &#x2B07;</p>
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

    // ── Inventory HTML ────────────────────────────────────────────────────────

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

    // ── Item transfer helpers ─────────────────────────────────────────────────

    /** Move an inventory item to the charcoal input slot. */
    private static void transferItem(PlayerRef playerRef, Ref<EntityStore> entityRef,
                                     Store<EntityStore> store, Vector3i pos,
                                     ItemContainer srcContainer, short srcSlot, short targetSlot) {
        try {
            ItemStack moving = srcContainer.getItemStack(srcSlot);
            if (moving == null || moving.isEmpty()) return;
            CokeOvenState state = lookupState(pos);
            if (state == null) return;
            ItemContainer blockIc = state.getItemContainer();
            if (blockIc == null) return;
            // Only allow charcoal in the input slot
            if (targetSlot == 0 && !CokeOvenState.INPUT_ITEM_ID.equals(moving.getItemId())) return;
            blockIc.setItemStackForSlot(targetSlot, moving);
            srcContainer.setItemStackForSlot(srcSlot, ItemStack.EMPTY);
            state.uiDirty = true;
            PlayerSession s = SESSIONS.get(playerRef);
            if (s != null && s.page() != null) {
                HyUIPage page = s.page();
                updateSlotIcon(page, blockIc, targetSlot, "co-slot-" + targetSlot);
                try {
                    Player player = store.getComponent(s.entityRef(), Player.getComponentType());
                    if (player != null && player.getInventory() != null)
                        refreshAllInvSlots(page, player.getInventory());
                } catch (Throwable ignored2) {}
                page.updatePage(false);
            }
        } catch (Throwable ignored) {}
    }

    /** Move the coal coke output to the player's inventory. */
    private static void transferOutputToPlayer(PlayerRef playerRef, Ref<EntityStore> entityRef,
                                               Store<EntityStore> store, Vector3i pos, short blockSlot) {
        try {
            CokeOvenState state = lookupState(pos);
            if (state == null) { HytaleLogger.getLogger().atWarning().log("[CokeOvenUI] transferOutput: state null"); return; }
            ItemContainer blockIc = state.getItemContainer();
            if (blockIc == null) { HytaleLogger.getLogger().atWarning().log("[CokeOvenUI] transferOutput: blockIc null"); return; }
            ItemStack output = blockIc.getItemStack(blockSlot);
            if (output == null || output.isEmpty()) { HytaleLogger.getLogger().atWarning().log("[CokeOvenUI] transferOutput: slot " + blockSlot + " empty"); return; }
            PlayerSession ps = SESSIONS.get(playerRef);
            if (ps == null) { HytaleLogger.getLogger().atWarning().log("[CokeOvenUI] transferOutput: no session"); return; }
            Player player = store.getComponent(ps.entityRef(), Player.getComponentType());
            if (player == null) { HytaleLogger.getLogger().atWarning().log("[CokeOvenUI] transferOutput: player null"); return; }
            Inventory inventory = player.getInventory();
            if (inventory == null) { HytaleLogger.getLogger().atWarning().log("[CokeOvenUI] transferOutput: inv null"); return; }
            String itemId  = output.getItemId();
            int remaining  = output.getQuantity();
            remaining = mergeIntoExisting(inventory.getHotbar(),  9,  itemId, remaining, 64);
            remaining = mergeIntoExisting(inventory.getStorage(), 36, itemId, remaining, 64);
            remaining = placeInEmpty(inventory.getHotbar(),       9,  itemId, remaining, 64);
            remaining = placeInEmpty(inventory.getStorage(),      36, itemId, remaining, 64);
            if (remaining == output.getQuantity()) { HytaleLogger.getLogger().atWarning().log("[CokeOvenUI] transferOutput: inventory full"); return; }
            blockIc.setItemStackForSlot(blockSlot,
                    remaining > 0 ? new ItemStack(itemId, remaining, null) : ItemStack.EMPTY);
            state.uiDirty = true;
            PlayerSession s2 = SESSIONS.get(playerRef);
            if (s2 != null && s2.page() != null) {
                HyUIPage page = s2.page();
                updateSlotIcon(page, blockIc, blockSlot, "co-slot-" + blockSlot);
                try {
                    Player p2 = store.getComponent(s2.entityRef(), Player.getComponentType());
                    if (p2 != null && p2.getInventory() != null) refreshAllInvSlots(page, p2.getInventory());
                } catch (Throwable ignored2) {}
                page.updatePage(false);
            }
        } catch (Throwable t) { HytaleLogger.getLogger().atWarning().log("[CokeOvenUI] transferOutput exception: " + t); }
    }

    /** Move the charcoal input back to the player. */
    private static void takeFromBlock(PlayerRef playerRef, Ref<EntityStore> entityRef,
                                      Store<EntityStore> store, Vector3i pos, short blockSlot) {
        try {
            CokeOvenState state = lookupState(pos);
            if (state == null) { HytaleLogger.getLogger().atWarning().log("[CokeOvenUI] takeFromBlock: state null"); return; }
            ItemContainer blockIc = state.getItemContainer();
            if (blockIc == null) { HytaleLogger.getLogger().atWarning().log("[CokeOvenUI] takeFromBlock: blockIc null"); return; }
            ItemStack item = blockIc.getItemStack(blockSlot);
            if (item == null || item.isEmpty()) { HytaleLogger.getLogger().atWarning().log("[CokeOvenUI] takeFromBlock: slot " + blockSlot + " empty"); return; }
            PlayerSession ps = SESSIONS.get(playerRef);
            if (ps == null) { HytaleLogger.getLogger().atWarning().log("[CokeOvenUI] takeFromBlock: no session"); return; }
            Player player = store.getComponent(ps.entityRef(), Player.getComponentType());
            if (player == null) { HytaleLogger.getLogger().atWarning().log("[CokeOvenUI] takeFromBlock: player null"); return; }
            Inventory inv = player.getInventory();
            if (inv == null) { HytaleLogger.getLogger().atWarning().log("[CokeOvenUI] takeFromBlock: inv null"); return; }
            int remaining = item.getQuantity();
            remaining = mergeIntoExisting(inv.getHotbar(),  9,  item.getItemId(), remaining, 64);
            remaining = mergeIntoExisting(inv.getStorage(), 36, item.getItemId(), remaining, 64);
            remaining = placeInEmpty(inv.getHotbar(),  9,  item.getItemId(), remaining, 64);
            remaining = placeInEmpty(inv.getStorage(), 36, item.getItemId(), remaining, 64);
            if (remaining == item.getQuantity()) { HytaleLogger.getLogger().atWarning().log("[CokeOvenUI] takeFromBlock: inventory full, nothing moved"); return; }
            ItemStack updated = remaining > 0 ? new ItemStack(item.getItemId(), remaining, null) : ItemStack.EMPTY;
            blockIc.setItemStackForSlot(blockSlot, updated);
            state.uiDirty = true;
            if (ps.page() != null) {
                HyUIPage page = ps.page();
                updateSlotIcon(page, blockIc, blockSlot, "co-slot-" + blockSlot);
                refreshAllInvSlots(page, inv);
                page.updatePage(false);
            }
        } catch (Throwable t) { HytaleLogger.getLogger().atWarning().log("[CokeOvenUI] takeFromBlock exception: " + t); }
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

    // ── Lookup / helpers ──────────────────────────────────────────────────────

    private static CokeOvenState lookupState(Vector3i pos) {
        Object node = FluidNetwork.getAt(pos);
        return node instanceof CokeOvenState s ? s : null;
    }

    private static String posKey(Vector3i pos) {
        return pos.x + "," + pos.y + "," + pos.z;
    }

    private static void decrementWatcher(Vector3i pos) {
        String key = posKey(pos);
        WATCHER_COUNT.compute(key, (k, v) -> (v == null || v <= 1) ? null : v - 1);
    }

    private static String prettify(String id) {
        if (id == null || id.isEmpty()) return "(empty)";
        return id.replace('_', ' ').replace('-', ' ');
    }

    // ── CSS ───────────────────────────────────────────────────────────────────

    private static final String STYLE = """
            <style>
                .title-label { font-weight: bold; color: #ffcc80; font-size: 18; padding-top: 8; padding-bottom: 6; }
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
