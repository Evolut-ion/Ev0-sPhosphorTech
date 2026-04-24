package com.Ev0sMods.PhosphorTech.ui;

import java.text.DecimalFormat;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.blocks.MechanicalHeaterState;
import com.Ev0sMods.PhosphorTech.heat.HeatNetwork;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
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
 * HyUI page for the Mechanical Heater block.
 *
 * <p>Shows Joule buffer, heat level, and heating status.
 */
@SuppressWarnings({"removal", "unchecked"})
public final class MechanicalHeaterUIPage {

    private MechanicalHeaterUIPage() {}

    private static final DecimalFormat DEC1 = new DecimalFormat("0.0");

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

    public static boolean hasWatcher(Vector3i pos) {
        Integer c = WATCHER_COUNT.get(posKey(pos));
        return c != null && c > 0;
    }

    public static void tickRefresh(MechanicalHeaterState state, Store<?> store, Vector3i pos) {
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
            MechanicalHeaterState state = lookupState(pos);

            PageBuilder builder = PageBuilder.pageForPlayer(playerRef)
                    .fromHtml(buildHtml(state))
                    .withLifetime(CustomPageLifetime.CanDismissOrCloseThroughInteraction);

            builder.addEventListener("mh-close-btn", CustomUIEventBindingType.Activating,
                    (ign, ctx) -> {
                        PlayerSession s = SESSIONS.remove(playerRef);
                        if (s != null) decrementWatcher(s.blockPos());
                        ctx.getPage().ifPresent(HyUIPage::close);
                    });

            builder.onDismiss((page, playerInitiated) -> {
                PlayerSession s = SESSIONS.remove(playerRef);
                if (s != null) decrementWatcher(s.blockPos());
            });

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

    private static void partialRefresh(PlayerSession session, MechanicalHeaterState state) {
        HyUIPage page = session.page();
        if (page == null || state == null) return;

        String statusText  = state.isHeating ? "Heating" : (state.joulesStored > 0 ? "Standby" : "No Power");
        String statusColor = state.isHeating ? "#ff8a65" : (state.joulesStored > 0 ? "#ffd54f" : "#e57373");
        page.getById("mh-status-val", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(statusText)
                        .withStyle(new HyUIStyle().setTextColor(statusColor)));

        float jPct   = (float) (state.joulesStored / MechanicalHeaterState.J_CAPACITY);
        int   jBarW  = barFill(jPct);
        page.getById("mh-j-fill", PanelBuilder.class)
                .ifPresent(p -> p.withStyle(
                        "anchor-width: " + jBarW + "; anchor-height: 20; "
                        + "background-color: #558b2f; border-radius: 10;"));
        page.getById("mh-j-label", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(DEC1.format(state.joulesStored)
                        + " / " + (int) MechanicalHeaterState.J_CAPACITY + " J"));

        float heatPct = (float) (state.heatCelsius / MechanicalHeaterState.MAX_HEAT_CELSIUS);
        int   heatBarW = barFill(heatPct);
        page.getById("mh-heat-fill", PanelBuilder.class)
                .ifPresent(p -> p.withStyle(
                        "anchor-width: " + heatBarW + "; anchor-height: 20; "
                        + "background-color: " + heatBarColor(heatPct) + "; border-radius: 10;"));
        page.getById("mh-heat-label", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(DEC1.format(state.heatCelsius) + " °C"));

        page.updatePage(false);
    }

    // ── HTML builder ──────────────────────────────────────────────────────────

    private static String buildHtml(MechanicalHeaterState state) {
        double joules = state != null ? state.joulesStored : 0.0;
        double heat   = state != null ? state.heatCelsius  : 20.0;
        boolean htg   = state != null && state.isHeating;

        float jPct    = (float) (joules / MechanicalHeaterState.J_CAPACITY);
        float heatPct = (float) (heat   / MechanicalHeaterState.MAX_HEAT_CELSIUS);
        int jBarW     = barFill(jPct);
        int heatBarW  = barFill(heatPct);

        String statusText  = htg ? "Heating" : (joules > 0 ? "Standby" : "No Power");
        String statusColor = htg ? "#ff8a65" : (joules > 0 ? "#ffd54f" : "#e57373");

        return STYLE + """
                <div style="anchor-width: 100%%; anchor-height: 100%%;
                            horizontal-align: center; vertical-align: middle;">
                <div id="mh-root" style="layout-mode: Top; anchor-width: 400; anchor-height: 440;
                     background-color: #0f1a0a; border-radius: 16; padding: 20;">

                    <!-- Header -->
                    <div style="layout-mode: Left; horizontal-align: center; margin-bottom: 12;">
                        <p style="font-size: 18; color: #aed581; font-weight: bold;">
                            &#9881; Mechanical Heater
                        </p>
                    </div>
                    <div style="anchor-height: 2; background-color: #295209; margin-bottom: 14;"></div>

                    <!-- Status row -->
                    <div style="layout-mode: Left; horizontal-align: space-between; margin-bottom: 10;">
                        <p class="mh-label">Status</p>
                        <p id="mh-status-val" class="mh-value" style="color: \
                """ + statusColor + ";\">" + statusText + """
                        </p>
                    </div>

                    <!-- Joule buffer bar -->
                    <p class="mh-label" style="margin-bottom: 6;">Joule Buffer</p>
                    <div style="layout-mode: Left; anchor-width: 360; anchor-height: 20;
                         background-color: #0a1000; border-radius: 10; margin-bottom: 4;">
                        <div id="mh-j-fill" style="anchor-width: \
                """ + jBarW + "; anchor-height: 20; background-color: #558b2f; border-radius: 10;\"></div>\n" + """
                    </div>
                    <div style="layout-mode: Left; horizontal-align: center; margin-bottom: 14;">
                        <p id="mh-j-label" class="mh-value" style="color: #dcedc8;">""" + DEC1.format(joules) + " / " + (int) MechanicalHeaterState.J_CAPACITY + " J</p>\n" + """
                    </div>

                    <!-- Heat bar -->
                    <p class="mh-label" style="margin-bottom: 6;">Heat</p>
                    <div style="layout-mode: Left; anchor-width: 360; anchor-height: 20;
                         background-color: #200a00; border-radius: 10; margin-bottom: 4;">
                        <div id="mh-heat-fill" style="anchor-width: \
                """ + heatBarW + "; anchor-height: 20; background-color: " + heatBarColor(heatPct) + "; border-radius: 10;\"></div>\n" + """
                    </div>
                    <div style="layout-mode: Left; horizontal-align: center; margin-bottom: 14;">
                        <p id="mh-heat-label" class="mh-value" style="color: #ffccbc;">""" + DEC1.format(heat) + " °C</p>\n" + """
                    </div>

                    <!-- Max heat -->
                    <div style="layout-mode: Left; horizontal-align: space-between; margin-bottom: 10;">
                        <p class="mh-label">Max Heat</p>
                        <p class="mh-value" style="color: #ff7043;">""" + (int) MechanicalHeaterState.MAX_HEAT_CELSIUS + " °C</p>\n" + """
                    </div>

                    <div style="anchor-height: 2; background-color: #295209; margin-top: 10; margin-bottom: 10;"></div>

                    <div style="layout-mode: Left; horizontal-align: center; margin-top: 8;">
                        <button id="mh-close-btn" style="background-color: #295209;
                             border-radius: 8; padding: 6 16; color: #aed581;">
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

    private static String heatBarColor(float pct) {
        if (pct >= 0.8f) return "#f4511e";
        if (pct >= 0.5f) return "#e64a19";
        if (pct >= 0.2f) return "#bf360c";
        return "#555555";
    }

    private static MechanicalHeaterState lookupState(Vector3i pos) {
        Object node = HeatNetwork.getAt(pos);
        return node instanceof MechanicalHeaterState s ? s : null;
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
              .mh-label { font-size: 12; color: #8d6e63; }
              .mh-value { font-size: 13; color: #eeeeee; }
            </style>
            """;
}
