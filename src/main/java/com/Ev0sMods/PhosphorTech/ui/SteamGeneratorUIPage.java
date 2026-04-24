package com.Ev0sMods.PhosphorTech.ui;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.blocks.SteamGeneratorState;
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
 * HyUI page for the Steam Generator block.
 *
 * <p>Layout:
 * <ul>
 *   <li><b>Left panel</b> — status, water input bar, steam output bar, next-cycle countdown.</li>
 *   <li><b>Right panel</b> — crystal input slot.</li>
 *   <li><b>Bottom</b> — player hotbar + storage.</li>
 * </ul>
 */
@SuppressWarnings({"removal", "unchecked"})
public final class SteamGeneratorUIPage {

    private SteamGeneratorUIPage() {}

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

    public static boolean hasWatcher(Vector3i pos) {
        Integer c = WATCHER_COUNT.get(posKey(pos));
        return c != null && c > 0;
    }

    public static void tickRefresh(SteamGeneratorState state, Store<?> store,
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
            SteamGeneratorState state = lookupState(store, pos);
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
            builder.addEventListener("sg-close-btn", CustomUIEventBindingType.Activating,
                    (ign, ctx) -> {
                        PlayerSession s = SESSIONS.remove(playerRef);
                        if (s != null) decrementWatcher(s.blockPos());
                        ctx.getPage().ifPresent(HyUIPage::close);
                    });

            // Dismiss
            builder.onDismiss((page, playerInitiated) -> {
                PlayerSession s = SESSIONS.remove(playerRef);
                if (s != null) decrementWatcher(s.blockPos());
            });

            // Slot click listeners
            for (SlotInfo info : slots) {
                final ItemContainer src  = info.container();
                final short         slot = info.slot();
                final String        sid  = info.id();
                builder.addEventListener(sid, CustomUIEventBindingType.Activating,
                        (ign, ctx) -> transferItem(playerRef, entityRef, store, pos, src, slot, (short) 0));
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
            com.hypixel.hytale.logger.HytaleLogger.getLogger().atWarning().log("[SteamGeneratorUI] renderPage failed: " + t);
        }
    }

    // ── Incremental update ────────────────────────────────────────────────────

    private static void partialRefresh(PlayerRef playerRef, PlayerSession session,
                                       SteamGeneratorState state) {
        HyUIPage page = session.page();
        if (page == null) return;

        boolean gen = state.isGenerating;
        boolean heating = !gen && hasResourcesReady(state) && state.heatCelsius < SteamGeneratorState.HEAT_THRESHOLD;
        String statusText  = gen ? "Generating" : (heating ? "Heating Up" : (hasResourcesReady(state) ? "Standby" : "No Resources"));
        String statusColor = gen ? "#81c784" : (heating ? "#ff9800" : (hasResourcesReady(state) ? "#ffd54f" : "#e57373"));

        page.getById("sg-status-val", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(statusText)
                        .withStyle(new HyUIStyle().setTextColor(statusColor)));

        // Heat bar
        int heatFillPr = (int)(288.0 * state.heatPct() / 100.0);
        String heatBarColorPr = state.heatCelsius >= SteamGeneratorState.HEAT_THRESHOLD ? "#ff7043" : "#ff9800";
        page.getById("sg-heat-fill", PanelBuilder.class)
                .ifPresent(p -> p.withContentWidth(heatFillPr));
        page.getById("sg-heat-label", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(state.heatLabel()));

        // Water input bar
        int waterFill = (int)(288.0 * state.waterMB / SteamGeneratorState.WATER_MAX_MB);
        page.getById("sg-water-fill", PanelBuilder.class)
                .ifPresent(p -> p.withContentWidth(waterFill));
        page.getById("sg-water-label", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(state.waterLabel()));

        // Steam output bar
        int steamFill = (int)(288.0 * state.steamMB / SteamGeneratorState.STEAM_MAX_MB);
        page.getById("sg-steam-fill", PanelBuilder.class)
                .ifPresent(p -> p.withContentWidth(steamFill));
        page.getById("sg-steam-label", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(state.steamLabel()));

        // Next cycle countdown
        int secs = Math.max(0, state.ticksUntilNext() / 30);
        page.getById("sg-next-val", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(gen ? secs + "s" : "\u2014"));

        // Slot 0 (crystal)
        updateSlotIcon(page, state.getItemContainer(), (short) 0, "sg-slot0");

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

    private static String buildHtml(SteamGeneratorState state, Inventory inv,
                                    List<SlotInfo> slotsOut) {
        boolean gen        = false;
        String statusText  = "No Resources";
        String statusColor = "#e57373";
        int waterMB        = 0;
        int steamMB        = 0;
        int ticksUntilNext = 0;
        String slot0Id = null; int slot0Qty = 0;

        if (state != null) {
            gen           = state.isGenerating;
            waterMB       = state.waterMB;
            steamMB       = state.steamMB;
            ticksUntilNext = state.ticksUntilNext();
            statusText    = gen ? "Generating"
                    : (hasResourcesReady(state) && state.heatCelsius < SteamGeneratorState.HEAT_THRESHOLD) ? "Heating Up"
                    : (hasResourcesReady(state) ? "Standby" : "No Resources");
            statusColor   = gen ? "#81c784"
                    : (hasResourcesReady(state) && state.heatCelsius < SteamGeneratorState.HEAT_THRESHOLD) ? "#ff9800"
                    : (hasResourcesReady(state) ? "#ffd54f" : "#e57373");

            ItemContainer ic = state.getItemContainer();
            if (ic != null) {
                ItemStack s0 = ic.getItemStack((short) 0);
                if (s0 != null && !s0.isEmpty()) { slot0Id = s0.getItemId(); slot0Qty = s0.getQuantity(); }
                slotsOut.add(new SlotInfo("sg-slot0-btn", ic, (short) 0));
            }
        }

        int waterFill = (int)(288.0 * waterMB / SteamGeneratorState.WATER_MAX_MB);
        String waterLabel = waterMB + " / " + SteamGeneratorState.WATER_MAX_MB + " mB";

        int steamFill = (int)(288.0 * steamMB / SteamGeneratorState.STEAM_MAX_MB);
        String steamLabel = steamMB + " / " + SteamGeneratorState.STEAM_MAX_MB + " mB";

        double heatC = state != null ? state.heatCelsius : SteamGeneratorState.HEAT_MAX_CELSIUS;
        int heatFill = state != null ? (int)(288.0 * state.heatPct() / 100.0) : 0;
        String heatLabel = state != null ? state.heatLabel() : "20.0 / 500 \u00b0C";
        String heatBarColor = heatC >= SteamGeneratorState.HEAT_THRESHOLD ? "#ff7043" : "#ff9800";

        String nextVal = gen ? Math.max(0, ticksUntilNext / 30) + "s" : "\u2014";

        String leftPanel = """
                <div style="layout-mode: Top; anchor-width: 330; padding-top: 8; padding-bottom: 8;
                            padding-left: 16; padding-right: 16;">
                    <p class="title-label">&#128166; Steam Generator</p>
                    <div class="separator"></div>

                    <p class="section-label">Status</p>
                    <p id="sg-status-val" class="info-label" style="color: %s;">%s</p>

                    <p class="section-label">Heat (threshold: 100 \u00b0C)</p>
                    <div style="layout-mode: Left; anchor-width: 288; anchor-height: 18;
                                background-color: #2a1500; border-radius: 9; margin-top: 4; margin-bottom: 4;">
                        <div id="sg-heat-fill" style="anchor-width: %d; anchor-height: 18;
                             background-color: %s; border-radius: 9;"></div>
                    </div>
                    <p id="sg-heat-label" class="info-label">%s</p>

                    <p class="section-label">Water Input</p>
                    <div style="layout-mode: Left; anchor-width: 288; anchor-height: 18;
                                background-color: #0d2a3d; border-radius: 9; margin-top: 4; margin-bottom: 4;">
                        <div id="sg-water-fill" style="anchor-width: %d; anchor-height: 18;
                             background-color: #2196f3; border-radius: 9;"></div>
                    </div>
                    <p id="sg-water-label" class="info-label">%s</p>

                    <p class="section-label">Steam Output</p>
                    <div style="layout-mode: Left; anchor-width: 288; anchor-height: 18;
                                background-color: #1a2030; border-radius: 9; margin-top: 4; margin-bottom: 4;">
                        <div id="sg-steam-fill" style="anchor-width: %d; anchor-height: 18;
                             background-color: #78909c; border-radius: 9;"></div>
                    </div>
                    <p id="sg-steam-label" class="info-label">%s</p>

                    <div style="layout-mode: Left; padding-top: 6;">
                        <p class="hint-label">Every 30 ticks: 1 crystal + 1 000 mB water &#8594; 1 000 mB steam</p>
                    </div>
                    <div style="layout-mode: Left; padding-top: 2;">
                        <p class="hint-label">Next in: </p>
                        <p id="sg-next-val" class="info-label" style="padding-left: 6;">%s</p>
                    </div>

                    <div class="separator"></div>
                    <div style="layout-mode: Top; horizontal-align: center; padding-top: 8;">
                        <button id="sg-close-btn" class="secondary-button"
                            style="anchor-width: 120; anchor-height: 30; font-size: 13;
                                   color: #e57373;">&#x2715; Close</button>
                    </div>
                </div>
                """.formatted(
                statusColor, statusText,
                heatFill, heatBarColor, heatLabel,
                waterFill, waterLabel,
                steamFill, steamLabel,
                nextVal);

        String slot0Html = buildSlotHtml(slot0Id, slot0Qty, "Crystal", "sg-slot0");

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
                    <p class="hint-label">Water: via pipes / buckets</p>
                </div>
                """.formatted(slot0Html);

        String inventoryHtml = buildInventoryHtml(inv, slotsOut);

        return STYLE + """
                <div style="anchor-width: 100%%; anchor-height: 100%%;
                            horizontal-align: center; vertical-align: middle;">
                    <div class="decorated-container" data-hyui-title="Steam Generator"
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
                """.formatted(leftPanel, rightPanel, inventoryHtml);
    }

    private static String buildSlotHtml(String itemId, int qty, String label, String containerId) {
        String dispId  = itemId != null ? itemId : "";
        String name    = itemId != null ? prettify(itemId) : "(empty)";
        String qtyText = itemId != null ? "x" + qty : "";
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

    // ── Item transfer ─────────────────────────────────────────────────────────

    private static void transferItem(PlayerRef playerRef, Ref<EntityStore> entityRef,
                                     Store<?> store, Vector3i pos,
                                     ItemContainer srcContainer, short srcSlot, short targetSlot) {
        try {
            ItemStack moving = srcContainer.getItemStack(srcSlot);
            if (moving == null || moving.isEmpty()) return;
            SteamGeneratorState state = lookupState(store, pos);
            if (state == null) return;
            ItemContainer blockIc = state.getItemContainer();
            if (blockIc == null) return;
            blockIc.setItemStackForSlot(targetSlot, moving);
            srcContainer.setItemStackForSlot(srcSlot, ItemStack.EMPTY);
            state.uiDirty = true;

            PlayerSession s = SESSIONS.get(playerRef);
            if (s != null && s.page() != null) {
                partialRefresh(playerRef, s, state);
            }
        } catch (Throwable ignored) {}
    }

    // ── State lookup ──────────────────────────────────────────────────────────

    private static SteamGeneratorState lookupState(Store<?> store, Vector3i pos) {
        try {
            Object node = com.Ev0sMods.PhosphorTech.fluid.FluidNetwork.getAt(pos);
            if (node instanceof SteamGeneratorState sg) return sg;
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
                        Object comp = store.getComponent((Ref) ref, (com.hypixel.hytale.component.ComponentType) SteamGeneratorState.COMPONENT_TYPE);
                        if (comp instanceof SteamGeneratorState s) {
                            com.hypixel.hytale.logger.HytaleLogger.getLogger().atInfo().log("[SteamGeneratorUI] lookupState: found via chunk-scan at %s", pos);
                            return s;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        com.hypixel.hytale.logger.HytaleLogger.getLogger().atWarning().log("[SteamGeneratorUI] lookupState: node not found for %s", pos);
        return null;
    }

    private static volatile Method entityRefsMethod;
    private static volatile boolean entityRefsMethodResolved;

    private static Map<?, ?> entityRefsViaReflection(BlockComponentChunk bcc) {
        try {
            if (!entityRefsMethodResolved) {
                synchronized (SteamGeneratorUIPage.class) {
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

    private static String posKey(Vector3i v) { return v.x + "," + v.y + "," + v.z; }

    private static void decrementWatcher(Vector3i pos) {
        String key = posKey(pos);
        WATCHER_COUNT.merge(key, -1, (a, b) -> (a + b <= 0) ? null : a + b);
    }

    private static boolean hasResourcesReady(SteamGeneratorState state) {
        if (state == null) return false;
        ItemContainer ic = state.getItemContainer();
        if (ic == null) return false;
        ItemStack s0 = ic.getItemStack((short) 0);
        boolean crystal = s0 != null && !s0.isEmpty() && SteamGeneratorState.isCrystal(s0.getItemId());
        return crystal && state.waterMB >= SteamGeneratorState.WATER_PER_CYCLE;
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
                .slot-label {
                    font-weight: bold;
                    color: #bdcbd3;
                    font-size: 13;
                    padding-bottom: 2;
                    horizontal-align: center;
                }
                .slot-item-name { color: #c8dbe8; font-size: 13; font-weight: bold; padding-bottom: 2; }
                .slot-item-qty  { color: #a0b8c8; font-size: 12; }
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
                .secondary-button {
                    background-color: #1e1e1e;
                    border-color: #555555;
                    border-width: 1;
                    border-radius: 4;
                    padding-left: 8; padding-right: 8;
                }
            </style>
            """;
}
