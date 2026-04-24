package com.Ev0sMods.PhosphorTech.ui;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.blocks.CrystalGeneratorState;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.HyUIStyle;
import au.ellie.hyui.builders.ItemIconBuilder;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PageBuilder;
import au.ellie.hyui.builders.PanelBuilder;

/**
 * HyUI page for the Crystal Generator block.
 *
 * <p>Layout:
 * <ul>
 *   <li><b>Left panel</b> — status, water-tank bar (0-10 000 mB), CF buffer bar,
 *       and a next-cycle countdown.</li>
 *   <li><b>Right panel</b> — crystal slot (top) and water-bucket slot (bottom).</li>
 *   <li><b>Bottom</b> — player hotbar + storage (same as FertilizerUIPage).</li>
 * </ul>
 *
 * <p>Called from {@link CrystalGeneratorUI} via reflection so that HyUI can
 * remain an optional dependency.
 */
@SuppressWarnings({"removal", "unchecked"})
public final class CrystalGeneratorUIPage {

    private CrystalGeneratorUIPage() {}

    // ── Session tracking ──────────────────────────────────────────────────────

    private record PlayerSession(Ref<EntityStore> entityRef, Store<EntityStore> store,
                                  Vector3i blockPos, HyUIPage page) {}

    private static final ConcurrentHashMap<PlayerRef, PlayerSession> SESSIONS      = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,    Integer>        WATCHER_COUNT = new ConcurrentHashMap<>();

    private record SlotInfo(String id, ItemContainer container, short slot) {}

    // ── Open / close ──────────────────────────────────────────────────────────

    public static void openForced(PlayerRef playerRef, Ref<EntityStore> entityRef,
                                  Store<EntityStore> store, Vector3i pos) {
        PlayerSession existing = SESSIONS.get(playerRef);
        String posKey = posKey(pos);
        if (existing != null && posKey(existing.blockPos()).equals(posKey)) return;
        if (existing != null) decrementWatcher(existing.blockPos());
        SESSIONS.put(playerRef, new PlayerSession(entityRef, store, pos, null));
        WATCHER_COUNT.merge(posKey, 1, Integer::sum);
        renderPage(playerRef, entityRef, store, pos);
    }

    /** True when at least one player has this block's UI open. */
    public static boolean hasWatcher(Vector3i pos) {
        Integer c = WATCHER_COUNT.get(posKey(pos));
        return c != null && c > 0;
    }

    /** Called from the tick system to push incremental updates. */
    public static void tickRefresh(CrystalGeneratorState state, Store<?> store,
                                   Vector3i pos) {
        SESSIONS.forEach((playerRef, session) -> {
            if (!posKey(session.blockPos()).equals(posKey(pos))) return;
            HyUIPage page = session.page();
            if (page == null) return;
            partialRefresh(playerRef, session, state);
        });
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private static void renderPage(PlayerRef playerRef, Ref<EntityStore> entityRef,
                                   Store<EntityStore> store, Vector3i pos) {
        try {
            CrystalGeneratorState state = lookupState(store, pos);
            Inventory inventory = null;
            try {
                Player player = store.getComponent(entityRef, Player.getComponentType());
                if (player != null) inventory = player.getInventory();
            } catch (Throwable ignored) {}

            List<SlotInfo> slots = new ArrayList<>();
            PageBuilder builder = PageBuilder.pageForPlayer(playerRef)
                    .fromHtml(buildHtml(state, inventory, slots))
                    .withLifetime(CustomPageLifetime.CanDismissOrCloseThroughInteraction);

            // Close button
            builder.addEventListener("gen-close-btn", CustomUIEventBindingType.Activating,
                    (ign, ctx) -> {
                        PlayerSession s = SESSIONS.remove(playerRef);
                        if (s != null) decrementWatcher(s.blockPos());
                        ctx.getPage().ifPresent(HyUIPage::close);
                    });

            // Dismiss (Escape / F)
            builder.onDismiss((page, playerInitiated) -> {
                PlayerSession s = SESSIONS.remove(playerRef);
                if (s != null) decrementWatcher(s.blockPos());
            });

            // Slot click listeners
            for (SlotInfo info : slots) {
                final ItemContainer src  = info.container();
                final short         slot = info.slot();
                final String        sid  = info.id();
                if (sid.startsWith("gen-slot")) {
                    // Block slot — take item back to player inventory
                    builder.addEventListener(sid, CustomUIEventBindingType.Activating,
                            (ign, ctx) -> takeFromBlock(playerRef, entityRef, store, pos, slot));
                } else {
                    // Player inventory — put crystals into slot 0
                    builder.addEventListener(sid, CustomUIEventBindingType.Activating,
                            (ign, ctx) -> transferItem(playerRef, entityRef, store, pos, src, slot, (short) 0));
                }
            }

            // Close previous page
            PlayerSession prev = SESSIONS.get(playerRef);
            if (prev != null && prev.page() != null) {
                try { prev.page().close(); } catch (Throwable ignored) {}
            }

            HyUIPage page = builder.open(store);
            SESSIONS.compute(playerRef, (k, s) -> s == null ? null
                    : new PlayerSession(s.entityRef(), s.store(), s.blockPos(), page));
        } catch (Throwable t) {
            SESSIONS.remove(playerRef);
            com.hypixel.hytale.logger.HytaleLogger.getLogger().atWarning().log("[CrystalGeneratorUI] renderPage failed: " + t);
        }
    }

    // ── Incremental update ────────────────────────────────────────────────────

    private static void partialRefresh(PlayerRef playerRef, PlayerSession session,
                                       CrystalGeneratorState state) {
        HyUIPage page = session.page();
        if (page == null) return;

        // Status
        boolean gen = state.isGenerating;
        String statusText  = gen ? "Generating" : (hasCrystalAndSteam(state) ? "Standby" : "No Resources");
        String statusColor = gen ? "#81c784" : (hasCrystalAndSteam(state) ? "#ffd54f" : "#e57373");

        page.getById("gen-status-val", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(statusText)
                        .withStyle(new HyUIStyle().setTextColor(statusColor)));

        // Steam input bar
        int steamFill = (int)(288.0 * state.steamMB / CrystalGeneratorState.STEAM_MAX_MB);
        page.getById("gen-steam-fill", PanelBuilder.class)
                .ifPresent(p -> p.withContentWidth(steamFill));
        page.getById("gen-steam-label", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(state.steamLabel()));

        // Water output bar
        int waterFill = (int)(288.0 * state.waterMB / CrystalGeneratorState.WATER_MAX_MB);
        page.getById("gen-water-fill", PanelBuilder.class)
                .ifPresent(p -> p.withContentWidth(waterFill));
        page.getById("gen-water-label", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(state.waterLabel()));

        // CF bar
        int cfFill = (int)(288.0 * state.cfStored / CrystalGeneratorState.CF_MAX_STORED);
        page.getById("gen-cf-fill", PanelBuilder.class)
                .ifPresent(p -> p.withContentWidth(cfFill));
        page.getById("gen-cf-label", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(state.cfLabel()));

        // Next cycle countdown
        int secs = Math.max(0, state.ticksUntilNext() / 30);
        page.getById("gen-next-val", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(gen ? secs + "s" : "\u2014"));

        // Slot 0 (crystal)
        updateSlotIcon(page, state.getItemContainer(), (short) 0, "gen-slot0");

        page.updatePage(false);
    }

    private static void updateSlotIcon(HyUIPage page, ItemContainer ic, short slot, String slotId) {
        if (ic == null) return;
        ItemStack stack = ic.getItemStack(slot);
        boolean has = stack != null && !stack.isEmpty();
        String id  = has ? stack.getItemId() : "";
        String name = has ? prettify(stack.getItemId()) : "(empty)";
        String qty  = has ? "x" + stack.getQuantity() : "";
        page.getById(slotId + "-icon", ItemIconBuilder.class).ifPresent(b -> b.withItemId(id));
        page.getById(slotId + "-name", LabelBuilder.class).ifPresent(lb -> lb.withText(name));
        page.getById(slotId + "-qty",  LabelBuilder.class).ifPresent(lb -> lb.withText(qty));
    }

    // ── HTML builder ──────────────────────────────────────────────────────────

    private static String buildHtml(CrystalGeneratorState state, Inventory inv,
                                    List<SlotInfo> slotsOut) {
        // Defaults
        boolean gen        = false;
        String statusText  = "No Resources";
        String statusColor = "#e57373";
        int steamMB        = 0;
        int waterMB        = 0;
        int cfStored       = 0;
        int ticksUntilNext = 0;
        String slot0Id = null; int slot0Qty = 0;

        if (state != null) {
            gen           = state.isGenerating;
            steamMB       = state.steamMB;
            waterMB       = state.waterMB;
            cfStored      = state.cfStored;
            ticksUntilNext = state.ticksUntilNext();
            statusText    = gen ? "Generating"
                    : (hasCrystalAndSteam(state) ? "Standby" : "No Resources");
            statusColor   = gen ? "#81c784"
                    : (hasCrystalAndSteam(state) ? "#ffd54f" : "#e57373");

            ItemContainer ic = state.getItemContainer();
            if (ic != null) {
                ItemStack s0 = ic.getItemStack((short) 0);
                if (s0 != null && !s0.isEmpty()) { slot0Id = s0.getItemId(); slot0Qty = s0.getQuantity(); }
                slotsOut.add(new SlotInfo("gen-slot0-btn", ic, (short) 0));
            }
        }

        // Steam input bar
        int steamFill = (int)(288.0 * steamMB / CrystalGeneratorState.STEAM_MAX_MB);
        String steamLabel = steamMB + " / " + CrystalGeneratorState.STEAM_MAX_MB + " mB";

        // Water output bar
        int waterFill = (int)(288.0 * waterMB / CrystalGeneratorState.WATER_MAX_MB);
        String waterLabel = waterMB + " / " + CrystalGeneratorState.WATER_MAX_MB + " mB";

        // CF bar
        int cfFill = (int)(288.0 * cfStored / CrystalGeneratorState.CF_MAX_STORED);
        String cfLabel = cfStored + " / " + CrystalGeneratorState.CF_MAX_STORED + " CF";

        String nextVal = gen ? Math.max(0, ticksUntilNext / 30) + "s" : "\u2014";
        String cfBarColor = gen ? "#ce93d8" : "#555555";
        int cs = CrystalGeneratorState.CF_PER_TICK;

        String leftPanel = """
                <div style="layout-mode: Top; anchor-width: 330; padding-top: 8; padding-bottom: 8;
                            padding-left: 16; padding-right: 16;">
                    <p class="title-label">&#9889; Crystal Generator</p>
                    <div class="separator"></div>

                    <p class="section-label">Status</p>
                    <p id="gen-status-val" class="info-label" style="color: %s;">%s</p>

                    <p class="section-label">Output Rate</p>
                    <p class="info-label">%d CF / tick (active)</p>

                    <p class="section-label">Steam Input</p>
                    <div style="layout-mode: Left; anchor-width: 288; anchor-height: 18;
                                background-color: #1a2030; border-radius: 9; margin-top: 4; margin-bottom: 4;">
                        <div id="gen-steam-fill" style="anchor-width: %d; anchor-height: 18;
                             background-color: #78909c; border-radius: 9;"></div>
                    </div>
                    <p id="gen-steam-label" class="info-label">%s</p>

                    <p class="section-label">Water Output</p>
                    <div style="layout-mode: Left; anchor-width: 288; anchor-height: 18;
                                background-color: #0d2a3d; border-radius: 9; margin-top: 4; margin-bottom: 4;">
                        <div id="gen-water-fill" style="anchor-width: %d; anchor-height: 18;
                             background-color: #2196f3; border-radius: 9;"></div>
                    </div>
                    <p id="gen-water-label" class="info-label">%s</p>

                    <p class="section-label">CF Buffer</p>
                    <div style="layout-mode: Left; anchor-width: 288; anchor-height: 18;
                                background-color: #1a1a1a; border-radius: 9; margin-top: 4; margin-bottom: 4;">
                        <div id="gen-cf-fill" style="anchor-width: %d; anchor-height: 18;
                             background-color: %s; border-radius: 9;"></div>
                    </div>
                    <p id="gen-cf-label" class="info-label">%s</p>

                    <p class="section-label">Next Cycle</p>
                    <div style="layout-mode: Left; horizontal-align: center;">
                        <p class="hint-label">Every 30 ticks: 1 crystal + 100 mB steam &#8594; 256 CF/tick + 50 mB water</p>
                    </div>
                    <div style="layout-mode: Left; padding-top: 2;">
                        <p class="hint-label">Next in: </p>
                        <p id="gen-next-val" class="info-label" style="padding-left: 6;">%s</p>
                    </div>

                    <div class="separator"></div>
                    <div style="layout-mode: Top; horizontal-align: center; padding-top: 8;">
                        <button id="gen-close-btn" class="secondary-button"
                            style="anchor-width: 120; anchor-height: 30; font-size: 13;
                                   color: #e57373;">&#x2715; Close</button>
                    </div>
                </div>
                """.formatted(
                statusColor, statusText, cs,
                steamFill, steamLabel,
                waterFill, waterLabel,
                cfFill, cfBarColor, cfLabel,
                nextVal);

        String slot0Html = buildSlotHtml(slot0Id, slot0Qty, "Crystal", "gen-slot0");

        String rightPanel = """
                <div style="layout-mode: Top; anchor-width: 210; padding-top: 8; padding-bottom: 8;
                            padding-left: 16; padding-right: 16;">
                    <div style="padding-top: 16;"></div>
                    <p class="section-label">Input Slots</p>
                    <div class="separator"></div>
                    <div style="layout-mode: Top; padding-top: 12; padding-bottom: 8;
                                horizontal-align: center;">
            %s
                    </div>
                    <div class="separator"></div>
                    <p class="hint-label">Slot 0: any crystal item</p>
                    <p class="hint-label">Steam: via pipes / reservoir</p>
                </div>
                """.formatted(slot0Html);
        String inventoryHtml = buildInventoryHtml(inv, slotsOut);

        return STYLE + """
                <div style="anchor-width: 100%%; anchor-height: 100%%;
                            horizontal-align: center; vertical-align: middle;">
                    <div class="decorated-container" data-hyui-title="Crystal Generator"
                         style="anchor-height: 1010; anchor-width: 700;">
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
                """.formatted(leftPanel, rightPanel, inventoryHtml);
    }

    private static String buildSlotHtml(String itemId, int qty, String label, String containerId) {
        String dispId   = itemId != null ? itemId : "";
        String name     = itemId != null ? prettify(itemId) : "(empty)";
        String qtyText  = itemId != null ? "x" + qty : "";
        return """
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
                """.formatted(label, containerId, containerId, dispId,
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
            // Hotbar
            sb.append("<div style=\"layout-mode: Left; anchor-width: 468; anchor-height: 52;\">\n");
            for (short i = 0; i < cols; i++) sb.append(miniSlotHtml(hotbar,  i, "inv_h_" + i, slotsOut));
            sb.append("</div>\n");
            // Storage
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

    // ── Item transfer ─────────────────────────────────────────────────────────

    private static void transferItem(PlayerRef playerRef, Ref<EntityStore> entityRef,
                                     Store<?> store, Vector3i pos,
                                     ItemContainer srcContainer, short srcSlot, short targetSlot) {
        try {
            ItemStack moving = srcContainer.getItemStack(srcSlot);
            if (moving == null || moving.isEmpty()) return;
            CrystalGeneratorState state = lookupState(store, pos);
            if (state == null) return;
            ItemContainer blockIc = state.getItemContainer();
            if (blockIc == null) return;
            blockIc.setItemStackForSlot(targetSlot, moving);
            srcContainer.setItemStackForSlot(srcSlot, ItemStack.EMPTY);
            state.uiDirty = true;

            // Immediate per-slot + block UI push
            PlayerSession s = SESSIONS.get(playerRef);
            if (s != null && s.page() != null) {
                HyUIPage page = s.page();
                // Update block slot immediately
                updateSlotIcon(page, blockIc, targetSlot, "gen-slot" + targetSlot);
                // Clear the source inventory slot immediately
                updateInvSlotIcon(page, srcContainer, srcSlot);
                page.updatePage(false);
            }
        } catch (Throwable ignored) {}
    }

    /** Take an item from the block slot back into the player's first available inventory slot. */
    private static void takeFromBlock(PlayerRef playerRef, Ref<EntityStore> entityRef,
                                      Store<EntityStore> store, Vector3i pos, short blockSlot) {
        try {
            CrystalGeneratorState state = lookupState(store, pos);
            if (state == null) return;
            ItemContainer blockIc = state.getItemContainer();
            if (blockIc == null) return;
            ItemStack item = blockIc.getItemStack(blockSlot);
            if (item == null || item.isEmpty()) return;

            Player player = store.getComponent(SESSIONS.get(playerRef).entityRef(), Player.getComponentType());
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

            // Immediate visual feedback
            PlayerSession s = SESSIONS.get(playerRef);
            if (s != null && s.page() != null) {
                HyUIPage page = s.page();
                updateSlotIcon(page, blockIc, blockSlot, "gen-slot" + blockSlot);
                // Refresh all inv slots since we don't know the exact target ID easily
                refreshAllInvSlots(page, inv);
                page.updatePage(false);
            }
        } catch (Throwable ignored) {}
    }

    /** Try to place an item in the first empty slot. Returns slot index or -1. */
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

    private static int mergeIntoExisting(ItemContainer container, int maxSlots, String itemId, int qty, int maxStack) {
        if (container == null || qty <= 0) return qty;
        for (short i = 0; i < maxSlots && qty > 0; i++) {
            ItemStack s = container.getItemStack(i);
            if (s == null || s.isEmpty() || !s.getItemId().equals(itemId)) continue;
            int space = maxStack - s.getQuantity();
            if (space <= 0) continue;
            int take = Math.min(space, qty);
            container.setItemStackForSlot(i, new ItemStack(itemId, s.getQuantity() + take, null));
            qty -= take;
        }
        return qty;
    }

    private static int placeInEmpty(ItemContainer container, int maxSlots, String itemId, int qty, int maxStack) {
        if (container == null || qty <= 0) return qty;
        for (short i = 0; i < maxSlots && qty > 0; i++) {
            ItemStack s = container.getItemStack(i);
            if (s != null && !s.isEmpty()) continue;
            int take = Math.min(maxStack, qty);
            container.setItemStackForSlot(i, new ItemStack(itemId, take, null));
            qty -= take;
        }
        return qty;
    }

    /** Update a single player inventory slot icon by deriving its element ID. */
    private static void updateInvSlotIcon(HyUIPage page, ItemContainer container, short slot) {
        // Determine if this is hotbar or storage based on container size/identity
        // We search both prefixes since we don't carry a tag
        String hotbarId = "inv_h_" + slot;
        String storageId = "inv_s_" + slot;
        page.getById(hotbarId + "-icon", ItemIconBuilder.class)
                .ifPresent(b -> b.withItemId(""));
        page.getById(storageId + "-icon", ItemIconBuilder.class)
                .ifPresent(b -> b.withItemId(""));
    }

    /** Refresh all player inventory slot icons. */
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

    // ── State lookup ──────────────────────────────────────────────────────────

    private static CrystalGeneratorState lookupState(Store<?> store, Vector3i pos) {
        try {
            Object node = com.Ev0sMods.PhosphorTech.energy.CrystallineFluxNetwork.getAt(pos);
            if (node instanceof CrystalGeneratorState gs) return gs;
        } catch (Throwable ignored) {}

        try {
            if (store == null || store.getExternalData() == null) return null;
            ChunkStore cs = (ChunkStore) store.getExternalData();
            var chunks = cs.getChunkIndexes();
            if (chunks == null) return null;
            for (long chunkIdx : chunks) {
                Ref<ChunkStore> colRef = cs.getChunkReference(chunkIdx);
                if (colRef == null) continue;
                BlockComponentChunk bcc = (BlockComponentChunk) store.getComponent((Ref) colRef, (com.hypixel.hytale.component.ComponentType) BlockComponentChunk.getComponentType());
                if (bcc == null) continue;
                Map<?, ?> refs = entityRefsViaReflection(bcc);
                if (refs == null) continue;
                for (Map.Entry<?, ?> e : refs.entrySet()) {
                    if (!(e.getKey() instanceof Integer blockIndex)) continue;
                    if (!(e.getValue() instanceof Ref<?> ref)) continue;
                    int lx = ChunkUtil.xFromBlockInColumn((int) blockIndex);
                    int wy = ChunkUtil.yFromBlockInColumn((int) blockIndex);
                    int lz = ChunkUtil.zFromBlockInColumn((int) blockIndex);
                    int wx = ChunkUtil.worldCoordFromLocalCoord(ChunkUtil.xOfChunkIndex(chunkIdx), lx);
                    int wz = ChunkUtil.worldCoordFromLocalCoord(ChunkUtil.zOfChunkIndex(chunkIdx), lz);
                    if (wx == pos.x && wy == pos.y && wz == pos.z) {
                        Object comp = store.getComponent((Ref) ref, (com.hypixel.hytale.component.ComponentType) CrystalGeneratorState.COMPONENT_TYPE);
                        if (comp instanceof CrystalGeneratorState s) {
                            com.hypixel.hytale.logger.HytaleLogger.getLogger().atInfo().log("[CrystalGeneratorUI] lookupState: found via chunk-scan at %s", pos);
                            return s;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        com.hypixel.hytale.logger.HytaleLogger.getLogger().atWarning().log("[CrystalGeneratorUI] lookupState: node not found for %s", pos);
        return null;
    }

    private static volatile Method entityRefsMethod;
    private static volatile boolean entityRefsMethodResolved;

    private static Map<?, ?> entityRefsViaReflection(BlockComponentChunk bcc) {
        try {
            if (!entityRefsMethodResolved) {
                synchronized (CrystalGeneratorUIPage.class) {
                    if (!entityRefsMethodResolved) {
                        for (Method m : bcc.getClass().getMethods()) {
                            if ("getEntityReferences".equals(m.getName()) && m.getParameterCount() == 0) {
                                m.setAccessible(true);
                                entityRefsMethod = m;
                                break;
                            }
                        }
                        entityRefsMethodResolved = true;
                    }
                }
            }
            if (entityRefsMethod == null) return null;
            Object r = entityRefsMethod.invoke(bcc);
            return r instanceof Map<?, ?> map ? map : null;
        } catch (Throwable ignored) { return null; }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String posKey(Vector3i v) {
        return v.x + "," + v.y + "," + v.z;
    }

    private static void decrementWatcher(Vector3i pos) {
        String key = posKey(pos);
        WATCHER_COUNT.merge(key, -1, (a, b) -> (a + b <= 0) ? null : a + b);
    }

    private static boolean hasCrystalAndSteam(CrystalGeneratorState state) {
        if (state == null) return false;
        ItemContainer ic = state.getItemContainer();
        if (ic == null) return false;
        ItemStack s0 = ic.getItemStack((short) 0);
        boolean crystal = s0 != null && !s0.isEmpty() && CrystalGeneratorState.isCrystal(s0.getItemId());
        return crystal && state.steamMB >= CrystalGeneratorState.STEAM_PER_CYCLE;
    }

    private static String prettify(String id) {
        if (id == null || id.isEmpty()) return "(empty)";
        return id.replace('_', ' ').replace('-', ' ');
    }

    // ── CSS ───────────────────────────────────────────────────────────────────

    private static final String STYLE = """
            <style>
                .title-label {
                    font-weight: bold;
                    color: #d4aaff;
                    font-size: 18;
                    padding-top: 8;
                    padding-bottom: 6;
                }
                .section-label {
                    font-weight: bold;
                    color: #bdcbd3;
                    font-size: 14;
                    padding-top: 6;
                    padding-bottom: 2;
                }
                .info-label {
                    color: #a0b8c8;
                    font-size: 12;
                    padding-top: 2;
                    padding-bottom: 2;
                }
                .hint-label {
                    color: #7a9aaa;
                    font-size: 11;
                    padding-top: 2;
                    padding-bottom: 2;
                }
                .pct-label {
                    font-weight: bold;
                    font-size: 14;
                    padding-top: 2;
                    padding-bottom: 2;
                }
                .slot-label {
                    font-weight: bold;
                    color: #bdcbd3;
                    font-size: 13;
                    padding-bottom: 2;
                    horizontal-align: center;
                }
                .slot-item-name { color: #c8dbe8; font-size: 13; font-weight: bold; padding-bottom: 2; }
                .slot-item-qty  { color: #a0b8c8; font-size: 12; }
                .arrow-label    { color: #5a7a8a; font-size: 20; horizontal-align: center; }
                .separator {
                    layout-mode: Full;
                    anchor-height: 1;
                    background-color: #ffffff(0.15);
                    margin-top: 6;
                    margin-bottom: 6;
                }
                .vert-separator {
                    anchor-width: 1;
                    layout-mode: Full;
                    background-color: #ffffff(0.15);
                    margin-left: 6;
                    margin-right: 6;
                }
                .empty-slot {
                    anchor-width: 48; anchor-height: 48;
                    background-color: #ffffff(0.06);
                    margin-top: 4; margin-bottom: 4;
                }
                .secondary-button {
                    background-color: #1565c0;
                    border-radius: 8;
                    padding-top: 6; padding-bottom: 6;
                    padding-left: 16; padding-right: 16;
                    color: #e3f2fd;
                }
            </style>
            """;
}
