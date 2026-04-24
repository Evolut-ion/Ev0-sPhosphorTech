package com.Ev0sMods.PhosphorTech.ui;

import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.blocks.CrystallineCapacitorState;
import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxNetwork;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.HyUIStyle;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PageBuilder;
import au.ellie.hyui.builders.PanelBuilder;

/**
 * HyUI page for the Crystalline Capacitor block.
 *
 * <p>Layout:
 * <ul>
 *   <li>A centred panel showing:
 *       <ul>
 *         <li>Title + status (Charging / Exporting / Idle)</li>
 *         <li>CF buffer bar (0 – 24 000 CF)</li>
 *         <li>Capacity + per-tick output-rate info</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p>Called from {@link CrystallineCapacitorUI} via reflection so that HyUI
 * remains an optional dependency.
 */
@SuppressWarnings({"removal", "unchecked"})
public final class CrystallineCapacitorUIPage {

    private CrystallineCapacitorUIPage() {}

    // ── Session tracking ──────────────────────────────────────────────────────

    private record PlayerSession(Ref<EntityStore> entityRef, Store<EntityStore> store,
                                  Vector3i blockPos, HyUIPage page) {}

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

    /** True when at least one player has this block's UI open. */
    public static boolean hasWatcher(Vector3i pos) {
        Integer c = WATCHER_COUNT.get(posKey(pos));
        return c != null && c > 0;
    }

    /** Called from the tick system to push incremental updates. */
    public static void tickRefresh(CrystallineCapacitorState state, Store<?> store,
                                   Vector3i pos) {
        SESSIONS.forEach((playerRef, session) -> {
            if (!posKey(session.blockPos()).equals(posKey(pos))) return;
            HyUIPage page = session.page();
            if (page == null) return;
            partialRefresh(session, state);
        });
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private static void renderPage(PlayerRef playerRef, Ref<EntityStore> entityRef,
                                   Store<EntityStore> store, Vector3i pos) {
        try {
            CrystallineCapacitorState state = lookupState(pos);

            PageBuilder builder = PageBuilder.pageForPlayer(playerRef)
                    .fromHtml(buildHtml(state))
                    .withLifetime(CustomPageLifetime.CanDismissOrCloseThroughInteraction);

            // Close button
            builder.addEventListener("cap-close-btn", CustomUIEventBindingType.Activating,
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

            // Close any existing page for this player
            PlayerSession prev = SESSIONS.get(playerRef);
            if (prev != null && prev.page() != null) {
                try { prev.page().close(); } catch (Throwable ignored) {}
            }

            HyUIPage page = builder.open(store);
            SESSIONS.compute(playerRef, (k, s) -> s == null ? null
                    : new PlayerSession(s.entityRef(), s.store(), s.blockPos(), page));
        } catch (Throwable t) {
            SESSIONS.remove(playerRef);
            HytaleLogger.getLogger().atWarning().log("[CrystallineCapacitorUI] renderPage failed: " + t);
        }
    }

    // ── Incremental update ────────────────────────────────────────────────────

    private static void partialRefresh(PlayerSession session, CrystallineCapacitorState state) {
        HyUIPage page = session.page();
        if (page == null || state == null) return;

        // Status
        boolean exporting  = state.getCFStored() > 0 && hasExportTarget(session.blockPos());
        boolean charging   = state.getCFStored() < CrystallineCapacitorState.CF_CAPACITY;
        String statusText  = exporting ? "Exporting" : (charging ? "Charging" : "Full");
        String statusColor = exporting ? "#81c784"   : (charging ? "#ffd54f"  : "#ce93d8");

        page.getById("cap-status-val", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(statusText)
                        .withStyle(new HyUIStyle().setTextColor(statusColor)));

        // CF fill bar
        int barWidth = barFill(state.cfPct());
        page.getById("cap-cf-fill", PanelBuilder.class)
                .ifPresent(p -> p.withStyle(
                        "anchor-width: " + barWidth + "; anchor-height: 24; "
                        + "background-color: " + cfBarColor(state.cfPct()) + "; border-radius: 12;"));

        // CF label
        page.getById("cap-cf-label", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(state.cfLabel()));

        page.updatePage(false);
    }

    // ── HTML builder ──────────────────────────────────────────────────────────

    private static String buildHtml(CrystallineCapacitorState state) {
        int cfStored     = state != null ? (int) state.getCFStored() : 0;
        float cfPct      = state != null ? state.cfPct() : 0f;
        String cfLbl     = state != null ? state.cfLabel() : "0 / 24,000 CF";
        String statText  = "Idle";
        String statColor = "#aaaaaa";
        if (state != null && cfStored > 0) {
            statText  = "Charging";
            statColor = "#ffd54f";
        }
        int barW = barFill(cfPct);
        String barColor = cfBarColor(cfPct);

        return STYLE + """
                <div style="anchor-width: 100%%; anchor-height: 100%%;
                            horizontal-align: center; vertical-align: middle;">
                <div id="cap-root" style="layout-mode: Top; anchor-width: 400; anchor-height: 400; \
                     background-color: #1a1124; border-radius: 16; padding: 20;">

                    <!-- Header -->
                    <div style="layout-mode: Left; horizontal-align: center; margin-bottom: 12;">
                        <p style="font-size: 18; color: #d4aaff; font-weight: bold;">
                            &#128267; Crystalline Capacitor
                        </p>
                    </div>
                    <div style="anchor-height: 2; background-color: #3d2060; margin-bottom: 14;"></div>

                    <!-- Status row -->
                    <div style="layout-mode: Left; horizontal-align: space-between; margin-bottom: 10;">
                        <p class="cap-label">Status</p>
                        <p id="cap-status-val" class="cap-value" style="color: \
                """ + statColor + ";"  + "\">" + statText + """
                        </p>
                    </div>

                    <!-- Output rate row -->
                    <div style="layout-mode: Left; horizontal-align: space-between; margin-bottom: 10;">
                        <p class="cap-label">Max Export Rate</p>
                        <p class="cap-value" style="color: #ce93d8;">1,024 CF / tick</p>
                    </div>

                    <!-- Capacity row -->
                    <div style="layout-mode: Left; horizontal-align: space-between; margin-bottom: 16;">
                        <p class="cap-label">Capacity</p>
                        <p class="cap-value" style="color: #b39ddb;">24,000 CF</p>
                    </div>

                    <!-- CF buffer bar -->
                    <p class="cap-label" style="margin-bottom: 6;">CF Buffer</p>
                    <div style="layout-mode: Left; anchor-width: 360; anchor-height: 24; \
                         background-color: #0d0020; border-radius: 12; margin-bottom: 6;">
                        <div id="cap-cf-fill" style="anchor-width: \
                """ + barW + "; anchor-height: 24; background-color: " + barColor + "; border-radius: 12;\"></div>\n" + """
                    </div>
                    <div style="layout-mode: Left; horizontal-align: center;">
                        <p id="cap-cf-label" class="cap-value" style="color: #e1bee7;\">
                """ + cfLbl + """
                        </p>
                    </div>

                    <!-- Hint -->
                    <div style="anchor-height: 2; background-color: #3d2060; margin-top: 16; margin-bottom: 10;"></div>
                    <div style="layout-mode: Left; horizontal-align: center;">
                        <p style="font-size: 10; color: #7e57c2;">
                            Outputs when an adjacent wire or machine is connected.
                        </p>
                    </div>

                    <!-- Close button -->
                    <div style="layout-mode: Left; horizontal-align: center; margin-top: 12;">
                        <button id="cap-close-btn" style="background-color: #3d2060; \
                             border-radius: 8; padding: 6 16; color: #d4aaff;">
                            Close
                        </button>
                    </div>
                </div>
                </div>
                """;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Bar pixel width (0 – 360) for the given fill fraction. */
    private static int barFill(float pct) {
        return (int) Math.max(0, Math.min(360, 360.0f * pct));
    }

    /** Bar colour — transitions from grey → purple → bright purple as CF fills. */
    private static String cfBarColor(float pct) {
        if (pct >= 0.75f) return "#9c27b0";
        if (pct >= 0.40f) return "#7b1fa2";
        if (pct >= 0.10f) return "#6a1b9a";
        return "#444444";
    }

    /** True if there is at least one adjacent network node that can receive CF. */
    private static boolean hasExportTarget(Vector3i pos) {
        if (pos == null) return false;
        int[][] offsets = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int[] o : offsets) {
            Vector3i adj = new Vector3i(pos.x + o[0], pos.y + o[1], pos.z + o[2]);
            Object node = CrystallineFluxNetwork.getAt(adj);
            if (node instanceof com.Ev0sMods.PhosphorTech.energy.CrystallineFluxReceiver) return true;
        }
        return false;
    }

    private static CrystallineCapacitorState lookupState(Vector3i pos) {
        Object node = CrystallineFluxNetwork.getAt(pos);
        return node instanceof CrystallineCapacitorState s ? s : null;
    }

    private static String posKey(Vector3i pos) {
        return pos.x + "," + pos.y + "," + pos.z;
    }

    private static void decrementWatcher(Vector3i pos) {
        String key = posKey(pos);
        WATCHER_COUNT.compute(key, (k, v) -> (v == null || v <= 1) ? null : v - 1);
    }

    // ── CSS ───────────────────────────────────────────────────────────────────

    private static final String STYLE = """
            <style>
              .cap-label  { font-size: 12; color: #9575cd; }
              .cap-value  { font-size: 13; color: #eeeeee; }
            </style>
            """;
}
