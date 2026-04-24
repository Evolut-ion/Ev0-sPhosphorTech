package com.Ev0sMods.PhosphorTech.ui;

import java.text.DecimalFormat;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.blocks.DynamoState;
import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxNetwork;
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
 * HyUI page for the Dynamo block.
 *
 * <p>Shows Joule buffer, CF output buffer, and conversion status.
 */
@SuppressWarnings({"removal", "unchecked"})
public final class DynamoUIPage {

    private DynamoUIPage() {}

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

    public static void tickRefresh(DynamoState state, Store<?> store, Vector3i pos) {
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
            DynamoState state = lookupState(pos);

            PageBuilder builder = PageBuilder.pageForPlayer(playerRef)
                    .fromHtml(buildHtml(state))
                    .withLifetime(CustomPageLifetime.CanDismissOrCloseThroughInteraction);

            builder.addEventListener("dy-close-btn", CustomUIEventBindingType.Activating,
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

    private static void partialRefresh(PlayerSession session, DynamoState state) {
        HyUIPage page = session.page();
        if (page == null || state == null) return;

        String statusText  = state.isConverting ? "Converting" : (state.joulesStored > 0 ? "Standby" : "No Power");
        String statusColor = state.isConverting ? "#64b5f6" : (state.joulesStored > 0 ? "#ffd54f" : "#e57373");
        page.getById("dy-status-val", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(statusText)
                        .withStyle(new HyUIStyle().setTextColor(statusColor)));

        float jPct  = (float) (state.joulesStored / DynamoState.J_CAPACITY);
        int   jBarW = barFill(jPct);
        page.getById("dy-j-fill", PanelBuilder.class)
                .ifPresent(p -> p.withStyle(
                        "anchor-width: " + jBarW + "; anchor-height: 20; "
                        + "background-color: #558b2f; border-radius: 10;"));
        page.getById("dy-j-label", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(DEC1.format(state.joulesStored)
                        + " / " + (int) DynamoState.J_CAPACITY + " J"));

        float cfPct  = (float) ((double) state.cfStored / DynamoState.CF_MAX_STORED);
        int   cfBarW = barFill(cfPct);
        page.getById("dy-cf-fill", PanelBuilder.class)
                .ifPresent(p -> p.withStyle(
                        "anchor-width: " + cfBarW + "; anchor-height: 20; "
                        + "background-color: #ab47bc; border-radius: 10;"));
        page.getById("dy-cf-label", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(state.cfStored + " / " + DynamoState.CF_MAX_STORED + " CF"));

        page.updatePage(false);
    }

    // ── HTML builder ──────────────────────────────────────────────────────────

    private static String buildHtml(DynamoState state) {
        double joules = state != null ? state.joulesStored  : 0.0;
        long   cf     = state != null ? state.cfStored      : 0L;
        boolean conv  = state != null && state.isConverting;

        float jPct  = (float) (joules / DynamoState.J_CAPACITY);
        float cfPct = (float) ((double) cf / DynamoState.CF_MAX_STORED);
        int jBarW   = barFill(jPct);
        int cfBarW  = barFill(cfPct);

        String statusText  = conv ? "Converting" : (joules > 0 ? "Standby" : "No Power");
        String statusColor = conv ? "#64b5f6"    : (joules > 0 ? "#ffd54f" : "#e57373");

        return STYLE + """
                <div style="anchor-width: 100%%; anchor-height: 100%%;
                            horizontal-align: center; vertical-align: middle;">
                <div id="dy-root" style="layout-mode: Top; anchor-width: 400; anchor-height: 460;
                     background-color: #0d1520; border-radius: 16; padding: 20;">

                    <!-- Header -->
                    <div style="layout-mode: Left; horizontal-align: center; margin-bottom: 12;">
                        <p style="font-size: 18; color: #90caf9; font-weight: bold;">
                            &#9889; Dynamo
                        </p>
                    </div>
                    <div style="anchor-height: 2; background-color: #1565c0; margin-bottom: 14;"></div>

                    <!-- Status row -->
                    <div style="layout-mode: Left; horizontal-align: space-between; margin-bottom: 10;">
                        <p class="dy-label">Status</p>
                        <p id="dy-status-val" class="dy-value" style="color: \
                """ + statusColor + ";\">" + statusText + """
                        </p>
                    </div>

                    <!-- Joule buffer bar -->
                    <p class="dy-label" style="margin-bottom: 6;">Joule Buffer</p>
                    <div style="layout-mode: Left; anchor-width: 360; anchor-height: 20;
                         background-color: #0a1500; border-radius: 10; margin-bottom: 4;">
                        <div id="dy-j-fill" style="anchor-width: \
                """ + jBarW + "; anchor-height: 20; background-color: #558b2f; border-radius: 10;\"></div>\n" + """
                    </div>
                    <div style="layout-mode: Left; horizontal-align: center; margin-bottom: 14;">
                        <p id="dy-j-label" class="dy-value" style="color: #dcedc8;">""" + DEC1.format(joules) + " / " + (int) DynamoState.J_CAPACITY + " J</p>\n" + """
                    </div>

                    <!-- CF buffer bar -->
                    <p class="dy-label" style="margin-bottom: 6;">CF Output Buffer</p>
                    <div style="layout-mode: Left; anchor-width: 360; anchor-height: 20;
                         background-color: #150020; border-radius: 10; margin-bottom: 4;">
                        <div id="dy-cf-fill" style="anchor-width: \
                """ + cfBarW + "; anchor-height: 20; background-color: #ab47bc; border-radius: 10;\"></div>\n" + """
                    </div>
                    <div style="layout-mode: Left; horizontal-align: center; margin-bottom: 14;">
                        <p id="dy-cf-label" class="dy-value" style="color: #e1bee7;">""" + cf + " / " + DynamoState.CF_MAX_STORED + " CF</p>\n" + """
                    </div>

                    <!-- Conversion rate info -->
                    <div style="layout-mode: Left; horizontal-align: space-between; margin-bottom: 10;">
                        <p class="dy-label">Conversion Rate</p>
                        <p class="dy-value" style="color: #b0bec5;">1 J = \
                """ + DynamoState.CF_PER_JOULE + " CF</p>\n" + """
                    </div>

                    <div style="anchor-height: 2; background-color: #1565c0; margin-top: 10; margin-bottom: 10;"></div>

                    <div style="layout-mode: Left; horizontal-align: center; margin-top: 8;">
                        <button id="dy-close-btn" style="background-color: #1565c0;
                             border-radius: 8; padding: 6 16; color: #90caf9;">
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

    private static DynamoState lookupState(Vector3i pos) {
        Object node = CrystallineFluxNetwork.getAt(pos);
        return node instanceof DynamoState s ? s : null;
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
              .dy-label { font-size: 12; color: #546e7a; }
              .dy-value { font-size: 13; color: #eeeeee; }
            </style>
            """;
}
