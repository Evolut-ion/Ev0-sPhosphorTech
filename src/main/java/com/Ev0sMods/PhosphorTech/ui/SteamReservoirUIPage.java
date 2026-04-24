package com.Ev0sMods.PhosphorTech.ui;

import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.HyUIStyle;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PageBuilder;
import au.ellie.hyui.builders.PanelBuilder;
import com.Ev0sMods.PhosphorTech.blocks.SteamReservoirState;
import com.Ev0sMods.PhosphorTech.fluid.FluidNetwork;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3i;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.concurrent.ConcurrentHashMap;

/**
 * HyUI page for the Steam Reservoir block.
 *
 * <p>Layout: centred panel showing steam level bar, status, and capacity.
 *
 * <p>Called from {@link SteamReservoirUI} via reflection so that HyUI remains
 * an optional dependency.
 */
@SuppressWarnings({"removal", "unchecked"})
public final class SteamReservoirUIPage {

    private SteamReservoirUIPage() {}

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
    public static void tickRefresh(SteamReservoirState state,
                                   Store<?> store, Vector3i pos) {
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
            SteamReservoirState state = lookupState(pos);

            PageBuilder builder = PageBuilder.pageForPlayer(playerRef)
                    .fromHtml(buildHtml(state))
                    .withLifetime(CustomPageLifetime.CanDismissOrCloseThroughInteraction);

            // Close button
            builder.addEventListener("res-close-btn", CustomUIEventBindingType.Activating,
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
        }
    }

    // ── Incremental update ────────────────────────────────────────────────────

    private static void partialRefresh(PlayerSession session, SteamReservoirState state) {
        HyUIPage page = session.page();
        if (page == null || state == null) return;

        boolean providing = state.steamMB > 0 && hasAdjacentSink(session.blockPos());
        boolean filling   = state.steamMB < SteamReservoirState.STEAM_CAPACITY;
        String statusText  = providing ? "Providing" : (filling ? "Filling" : "Full");
        String statusColor = providing ? "#b0bec5"   : (filling ? "#ffd54f" : "#81c784");

        page.getById("res-status-val", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(statusText)
                        .withStyle(new HyUIStyle().setTextColor(statusColor)));

        int barWidth = barFill(state.steamPct());
        page.getById("res-steam-fill", PanelBuilder.class)
                .ifPresent(p -> p.withContentWidth(barWidth));

        page.getById("res-steam-label", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(state.steamLabel()));

        page.updatePage(false);
    }

    // ── HTML builder ──────────────────────────────────────────────────────────

    private static String buildHtml(SteamReservoirState state) {
        int steamMB    = state != null ? state.steamMB : 0;
        float pct      = state != null ? state.steamPct() : 0f;
        String lbl     = state != null ? state.steamLabel() : "0 / 10,000 mB";
        String statTxt = "Idle";
        String statClr = "#aaaaaa";
        if (state != null && steamMB > 0) {
            statTxt = "Filling";
            statClr = "#ffd54f";
        }
        int barW = barFill(pct);

        return STYLE + """
                <div style="anchor-width: 100%%; anchor-height: 100%%;
                            horizontal-align: center; vertical-align: middle;">
                <div id="res-root" style="layout-mode: Top; anchor-width: 400; anchor-height: 400;
                     background-color: #1a1e24; border-radius: 16; padding: 20;">

                    <!-- Header -->
                    <div style="layout-mode: Left; horizontal-align: center; margin-bottom: 12;">
                        <p style="font-size: 18; color: #b0bec5; font-weight: bold;">
                            &#128168; Steam Reservoir
                        </p>
                    </div>
                    <div style="anchor-height: 2; background-color: #37474f; margin-bottom: 14;"></div>

                    <!-- Status -->
                    <div style="layout-mode: Left; horizontal-align: space-between; margin-bottom: 10;">
                        <p class="res-label">Status</p>
                        <p id="res-status-val" class="res-value" style="color: \
                """ + statClr + ";"  + "\">" + statTxt + """
                        </p>
                    </div>

                    <!-- Capacity row -->
                    <div style="layout-mode: Left; horizontal-align: space-between; margin-bottom: 16;">
                        <p class="res-label">Capacity</p>
                        <p class="res-value" style="color: #90a4ae;">10,000 mB</p>
                    </div>

                    <!-- Steam bar -->
                    <p class="res-label" style="margin-bottom: 6;">Steam Level</p>
                    <div style="layout-mode: Left; anchor-width: 360; anchor-height: 24;
                         background-color: #0d1117; border-radius: 12; margin-bottom: 6;">
                        <div id="res-steam-fill" style="anchor-width: \
                """ + barW + "; anchor-height: 24; background-color: #78909c; border-radius: 12;\"></div>\n" + """
                    </div>
                    <div style="layout-mode: Left; horizontal-align: center;">
                        <p id="res-steam-label" class="res-value" style="color: #cfd8dc;">
                """ + lbl + """
                        </p>
                    </div>

                    <!-- Hint -->
                    <div style="anchor-height: 2; background-color: #37474f; margin-top: 16; margin-bottom: 10;"></div>
                    <div style="layout-mode: Left; horizontal-align: center;">
                        <p style="font-size: 10; color: #546e7a;">
                            Connects to adjacent pipes and Crystal Generators.
                        </p>
                    </div>

                    <!-- Close button -->
                    <div style="layout-mode: Left; horizontal-align: center; margin-top: 12;">
                        <button id="res-close-btn" style="background-color: #37474f;
                             border-radius: 8; padding: 6 16; color: #b0bec5;">
                            Close
                        </button>
                    </div>
                </div>
                </div>
                """;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int barFill(float pct) {
        return (int) Math.max(0, Math.min(360, 360.0f * pct));
    }

    /** True if any adjacent FluidCapable can accept steam from this reservoir. */
    private static boolean hasAdjacentSink(Vector3i pos) {
        if (pos == null) return false;
        int[][] offsets = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int[] o : offsets) {
            Vector3i adj = new Vector3i(pos.x + o[0], pos.y + o[1], pos.z + o[2]);
            var cap = FluidNetwork.getAt(adj);
            if (cap != null && cap.canAcceptFluid(
                    com.Ev0sMods.PhosphorTech.fluid.FluidType.STEAM)) return true;
        }
        return false;
    }

    private static SteamReservoirState lookupState(Vector3i pos) {
        Object node = FluidNetwork.getAt(pos);
        return node instanceof SteamReservoirState s ? s : null;
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
              .res-label  { font-size: 12; color: #78909c; }
              .res-value  { font-size: 13; color: #eeeeee; }
            </style>
            """;
}
