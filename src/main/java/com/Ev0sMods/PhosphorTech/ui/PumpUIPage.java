package com.Ev0sMods.PhosphorTech.ui;

import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.HyUIStyle;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PageBuilder;
import au.ellie.hyui.builders.PanelBuilder;
import com.Ev0sMods.PhosphorTech.blocks.PumpState;
import com.Ev0sMods.PhosphorTech.fluid.FluidNetwork;
import com.Ev0sMods.PhosphorTech.fluid.FluidType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3i;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.concurrent.ConcurrentHashMap;

/**
 * HyUI page for the Fluid Pump block.
 *
 * <p>Layout: centred panel showing CF level bar and fluid buffer bar.
 *
 * <p>Called from {@link PumpUI} via reflection so that HyUI remains
 * an optional dependency.
 */
@SuppressWarnings({"removal", "unchecked"})
public final class PumpUIPage {

    private PumpUIPage() {}

    // ── Session tracking ──────────────────────────────────────────────────────

    private record PlayerSession(Ref<EntityStore> entityRef, Store<EntityStore> store,
                                  Vector3i blockPos, HyUIPage page) {}

    private static final ConcurrentHashMap<PlayerRef, PlayerSession> SESSIONS      = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,    Integer>        WATCHER_COUNT = new ConcurrentHashMap<>();

    // ── Open / close ──────────────────────────────────────────────────────────

    public static void openForced(PlayerRef playerRef, Ref<EntityStore> entityRef,
                                  Store<EntityStore> store, Vector3i pos) {
        String posKey  = posKey(pos);
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

    /** Called from the pump tick system to push incremental updates. */
    public static void tickRefresh(PumpState state,
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
            PumpState state = lookupState(pos);

            PageBuilder builder = PageBuilder.pageForPlayer(playerRef)
                    .fromHtml(buildHtml(state))
                    .withLifetime(CustomPageLifetime.CanDismissOrCloseThroughInteraction);

            builder.addEventListener("pu-close-btn", CustomUIEventBindingType.Activating,
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

    private static void partialRefresh(PlayerSession session, PumpState state) {
        HyUIPage page = session.page();
        if (page == null || state == null) return;

        // CF bar
        float cfPct = state.getCFCapacity() > 0
                ? (float) state.getCFStored() / state.getCFCapacity() : 0f;
        int cfBarW = barFill(cfPct);
        page.getById("pu-cf-fill", PanelBuilder.class)
                .ifPresent(p -> p.withContentWidth(cfBarW));
        page.getById("pu-cf-label", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(cfLabel(state)));

        // Fluid bar
        float flPct = (float) state.bufferMB / PumpState.PUMP_BUFFER;
        int flBarW = barFill(flPct);
        page.getById("pu-fl-fill", PanelBuilder.class)
                .ifPresent(p -> p.withContentWidth(flBarW));
        page.getById("pu-fl-label", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(fluidLabel(state)));
        page.getById("pu-fl-type", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(fluidTypeName(state))
                        .withStyle(new HyUIStyle().setTextColor(fluidColor(state))));

        // Status
        String[] sv = statusTextAndColor(state);
        page.getById("pu-status-val", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(sv[0])
                        .withStyle(new HyUIStyle().setTextColor(sv[1])));

        page.updatePage(false);
    }

    // ── HTML builder ──────────────────────────────────────────────────────────

    private static String buildHtml(PumpState state) {
        float cfPct  = state != null && state.getCFCapacity() > 0
                ? (float) state.getCFStored() / state.getCFCapacity() : 0f;
        float flPct  = state != null ? (float) state.bufferMB / PumpState.PUMP_BUFFER : 0f;
        int cfBarW   = barFill(cfPct);
        int flBarW   = barFill(flPct);
        String cfLbl = state != null ? cfLabel(state) : "0 / 50,000 CF";
        String flLbl = state != null ? fluidLabel(state) : "0 / 5,000 mB";
        String flTyp = state != null ? fluidTypeName(state) : "None";
        String flClr = state != null ? fluidColor(state) : "#aaaaaa";
        String[] sv  = statusTextAndColor(state);

        return STYLE + """
                <div style="anchor-width: 100%%; anchor-height: 100%%;
                            horizontal-align: center; vertical-align: middle;">
                <div id="pu-root" style="layout-mode: Top; anchor-width: 420; anchor-height: 460;
                     background-color: #0d1f2d; border-radius: 16; padding: 20;">

                    <!-- Header -->
                    <div style="layout-mode: Left; horizontal-align: center; margin-bottom: 12;">
                        <p style="font-size: 18; color: #90caf9; font-weight: bold;">
                            &#9637; Fluid Pump
                        </p>
                    </div>
                    <div style="anchor-height: 2; background-color: #1565c0; margin-bottom: 14;"></div>

                    <!-- Status row -->
                    <div style="layout-mode: Left; horizontal-align: space-between; margin-bottom: 10;">
                        <p class="pu-label">Status</p>
                        <p id="pu-status-val" class="pu-value" style="color: \
                """ + sv[1] + ";"  + "\">" + sv[0] + """
                        </p>
                    </div>

                    <!-- CF divider -->
                    <div style="anchor-height: 1; background-color: #263238; margin-bottom: 10;"></div>

                    <!-- CF section -->
                    <p class="pu-label" style="margin-bottom: 6;">Crystalline Flux</p>
                    <div style="layout-mode: Left; anchor-width: 380; anchor-height: 20;
                         background-color: #0d1117; border-radius: 10; margin-bottom: 6;">
                        <div id="pu-cf-fill" style="anchor-width: \
                """ + cfBarW + "; anchor-height: 20; background-color: #f9a825; border-radius: 10;\"></div>\n" + """
                    </div>
                    <div style="layout-mode: Left; horizontal-align: center; margin-bottom: 14;">
                        <p id="pu-cf-label" class="pu-value" style="color: #ffe082;">
                """ + cfLbl + """
                        </p>
                    </div>

                    <!-- Fluid divider -->
                    <div style="anchor-height: 1; background-color: #263238; margin-bottom: 10;"></div>

                    <!-- Fluid section -->
                    <div style="layout-mode: Left; horizontal-align: space-between; margin-bottom: 6;">
                        <p class="pu-label">Fluid Buffer</p>
                        <p id="pu-fl-type" class="pu-value" style="color: \
                """ + flClr + ";\">" + flTyp + """
                        </p>
                    </div>
                    <div style="layout-mode: Left; anchor-width: 380; anchor-height: 20;
                         background-color: #0d1117; border-radius: 10; margin-bottom: 6;">
                        <div id="pu-fl-fill" style="anchor-width: \
                """ + flBarW + "; anchor-height: 20; background-color: #1565c0; border-radius: 10;\"></div>\n" + """
                    </div>
                    <div style="layout-mode: Left; horizontal-align: center; margin-bottom: 6;">
                        <p id="pu-fl-label" class="pu-value" style="color: #bbdefb;">
                """ + flLbl + """
                        </p>
                    </div>

                    <!-- Hint -->
                    <div style="anchor-height: 2; background-color: #1565c0; margin-top: 14; margin-bottom: 10;"></div>
                    <div style="layout-mode: Left; horizontal-align: center;">
                        <p style="font-size: 10; color: #546e7a;">
                            Every 150 ticks: draws 100 mB from below, costs 5,000 CF.
                        </p>
                    </div>

                    <!-- Close button -->
                    <div style="layout-mode: Left; horizontal-align: center; margin-top: 12;">
                        <button id="pu-close-btn" style="background-color: #1565c0;
                             border-radius: 8; padding: 6 16; color: #e3f2fd;">
                            Close
                        </button>
                    </div>
                </div>
                </div>
                """;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int barFill(float pct) {
        return (int) Math.max(0, Math.min(380, 380.0f * pct));
    }

    private static String cfLabel(PumpState s) {
        return String.format("%,d / %,d CF", s.getCFStored(), s.getCFCapacity());
    }

    private static String fluidLabel(PumpState s) {
        return String.format("%,d / %,d mB", s.bufferMB, PumpState.PUMP_BUFFER);
    }

    private static String fluidTypeName(PumpState s) {
        return s == null || s.bufferFluidKey == null ? "Empty"
                : capitalize(s.bufferFluidKey.toLowerCase().replace('_', ' '));
    }

    private static String fluidColor(PumpState s) {
        if (s == null || s.bufferFluidKey == null) return "#546e7a";
        try {
            FluidType ft = FluidType.valueOf(s.bufferFluidKey);
            return ft.getHexColor();
        } catch (Throwable ignored) {}
        return "#90caf9";
    }

    private static String[] statusTextAndColor(PumpState s) {
        if (s == null) return new String[]{"Offline", "#546e7a"};
        if (s.bufferMB >= PumpState.PUMP_BUFFER) return new String[]{"Buffer Full", "#ef9a9a"};
        if (s.getCFStored() < PumpState.CF_COST) return new String[]{"Low CF", "#ff8f00"};
        if (s.bufferMB > 0) return new String[]{"Pumping", "#66bb6a"};
        return new String[]{"Idle", "#aaaaaa"};
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static PumpState lookupState(Vector3i pos) {
        Object node = FluidNetwork.getAt(pos);
        return node instanceof PumpState s ? s : null;
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
              .pu-label  { font-size: 12; color: #90caf9; }
              .pu-value  { font-size: 13; color: #e3f2fd; }
            </style>
            """;
}
