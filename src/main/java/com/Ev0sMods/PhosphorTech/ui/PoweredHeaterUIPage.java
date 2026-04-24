package com.Ev0sMods.PhosphorTech.ui;

import java.text.DecimalFormat;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.blocks.PoweredHeaterState;
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
 * HyUI page for the Powered Heater block.
 *
 * <p>Shows CF buffer, heat level, and heating status.
 */
@SuppressWarnings({"removal", "unchecked"})
public final class PoweredHeaterUIPage {

    private PoweredHeaterUIPage() {}

    private static final DecimalFormat ROUNDED = new DecimalFormat("#,###");
    private static final DecimalFormat DEC1    = new DecimalFormat("0.0");

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
    public static void tickRefresh(PoweredHeaterState state, Store<?> store, Vector3i pos) {
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
            PoweredHeaterState state = lookupState(pos);

            PageBuilder builder = PageBuilder.pageForPlayer(playerRef)
                    .fromHtml(buildHtml(state))
                    .withLifetime(CustomPageLifetime.CanDismissOrCloseThroughInteraction);

            builder.addEventListener("ph-close-btn", CustomUIEventBindingType.Activating,
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

    private static void partialRefresh(PlayerSession session, PoweredHeaterState state) {
        HyUIPage page = session.page();
        if (page == null || state == null) return;

        // Status
        String statusText  = state.isHeating ? "Heating" : (state.cfStored > 0 ? "Standby" : "No Power");
        String statusColor = state.isHeating ? "#ff8a65" : (state.cfStored > 0 ? "#ffd54f" : "#e57373");
        page.getById("ph-status-val", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(statusText)
                        .withStyle(new HyUIStyle().setTextColor(statusColor)));

        // CF bar
        float cfPct = PoweredHeaterState.CF_CAPACITY > 0
                ? (float) state.cfStored / PoweredHeaterState.CF_CAPACITY : 0f;
        int cfBarW = barFill(cfPct);
        page.getById("ph-cf-fill", PanelBuilder.class)
                .ifPresent(p -> p.withStyle(
                        "anchor-width: " + cfBarW + "; anchor-height: 20; "
                        + "background-color: #7b1fa2; border-radius: 10;"));
        page.getById("ph-cf-label", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(
                        ROUNDED.format(state.cfStored) + " / "
                        + ROUNDED.format(PoweredHeaterState.CF_CAPACITY) + " CF"));

        // Heat bar
        float heatPct = (float) (state.heatCelsius / PoweredHeaterState.MAX_HEAT_CELSIUS);
        int heatBarW = barFill(heatPct);
        page.getById("ph-heat-fill", PanelBuilder.class)
                .ifPresent(p -> p.withStyle(
                        "anchor-width: " + heatBarW + "; anchor-height: 20; "
                        + "background-color: " + heatBarColor(heatPct) + "; border-radius: 10;"));
        page.getById("ph-heat-label", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(DEC1.format(state.heatCelsius) + " °C"));

        page.updatePage(false);
    }

    // ── HTML builder ──────────────────────────────────────────────────────────

    private static String buildHtml(PoweredHeaterState state) {
        long   cfStored  = state != null ? state.cfStored : 0L;
        double heat      = state != null ? state.heatCelsius : 20.0;
        boolean heating  = state != null && state.isHeating;

        float cfPct   = (float) cfStored / PoweredHeaterState.CF_CAPACITY;
        float heatPct = (float) (heat / PoweredHeaterState.MAX_HEAT_CELSIUS);
        int cfBarW    = barFill(cfPct);
        int heatBarW  = barFill(heatPct);

        String statusText  = heating ? "Heating" : (cfStored > 0 ? "Standby" : "No Power");
        String statusColor = heating ? "#ff8a65" : (cfStored > 0 ? "#ffd54f" : "#e57373");

        return STYLE + """
                <div style="anchor-width: 100%%; anchor-height: 100%%;
                            horizontal-align: center; vertical-align: middle;">
                <div id="ph-root" style="layout-mode: Top; anchor-width: 400; anchor-height: 450;
                     background-color: #1a0f0a; border-radius: 16; padding: 20;">

                    <!-- Header -->
                    <div style="layout-mode: Left; horizontal-align: center; margin-bottom: 12;">
                        <p style="font-size: 18; color: #ff8a65; font-weight: bold;">
                            &#128293; Powered Heater
                        </p>
                    </div>
                    <div style="anchor-height: 2; background-color: #5d2209; margin-bottom: 14;"></div>

                    <!-- Status row -->
                    <div style="layout-mode: Left; horizontal-align: space-between; margin-bottom: 10;">
                        <p class="ph-label">Status</p>
                        <p id="ph-status-val" class="ph-value" style="color: \
                """ + statusColor + ";\">" + statusText + """
                        </p>
                    </div>

                    <!-- CF rate row -->
                    <div style="layout-mode: Left; horizontal-align: space-between; margin-bottom: 16;">
                        <p class="ph-label">CF per Tick</p>
                        <p class="ph-value" style="color: #ce93d8;">""" + PoweredHeaterState.CF_PER_TICK + " CF/t</p>\n" + """
                    </div>

                    <!-- CF buffer bar -->
                    <p class="ph-label" style="margin-bottom: 6;">CF Buffer</p>
                    <div style="layout-mode: Left; anchor-width: 360; anchor-height: 20;
                         background-color: #0d0020; border-radius: 10; margin-bottom: 4;">
                        <div id="ph-cf-fill" style="anchor-width: \
                """ + cfBarW + "; anchor-height: 20; background-color: #7b1fa2; border-radius: 10;\"></div>\n" + """
                    </div>
                    <div style="layout-mode: Left; horizontal-align: center; margin-bottom: 14;">
                        <p id="ph-cf-label" class="ph-value" style="color: #e1bee7;">""" + ROUNDED.format(cfStored) + " / " + ROUNDED.format(PoweredHeaterState.CF_CAPACITY) + " CF</p>\n" + """
                    </div>

                    <!-- Heat bar -->
                    <p class="ph-label" style="margin-bottom: 6;">Heat</p>
                    <div style="layout-mode: Left; anchor-width: 360; anchor-height: 20;
                         background-color: #200a00; border-radius: 10; margin-bottom: 4;">
                        <div id="ph-heat-fill" style="anchor-width: \
                """ + heatBarW + "; anchor-height: 20; background-color: " + heatBarColor(heatPct) + "; border-radius: 10;\"></div>\n" + """
                    </div>
                    <div style="layout-mode: Left; horizontal-align: center; margin-bottom: 14;">
                        <p id="ph-heat-label" class="ph-value" style="color: #ffccbc;">""" + DEC1.format(heat) + " °C</p>\n" + """
                    </div>

                    <!-- Max heat -->
                    <div style="layout-mode: Left; horizontal-align: space-between; margin-bottom: 10;">
                        <p class="ph-label">Max Heat</p>
                        <p class="ph-value" style="color: #ff7043;">""" + (int) PoweredHeaterState.MAX_HEAT_CELSIUS + " °C</p>\n" + """
                    </div>

                    <div style="anchor-height: 2; background-color: #5d2209; margin-top: 10; margin-bottom: 10;"></div>

                    <!-- Close button -->
                    <div style="layout-mode: Left; horizontal-align: center; margin-top: 8;">
                        <button id="ph-close-btn" style="background-color: #5d2209;
                             border-radius: 8; padding: 6 16; color: #ff8a65;">
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

    private static PoweredHeaterState lookupState(Vector3i pos) {
        Object node = HeatNetwork.getAt(pos);
        return node instanceof PoweredHeaterState s ? s : null;
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
              .ph-label  { font-size: 12; color: #a1887f; }
              .ph-value  { font-size: 13; color: #eeeeee; }
            </style>
            """;
}
