package com.Ev0sMods.PhosphorTech.ui;

import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.blocks.GenericFluidTankState;
import com.Ev0sMods.PhosphorTech.fluid.FluidNetwork;
import com.Ev0sMods.PhosphorTech.fluid.FluidType;
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
 * HyUI page for the Generic Fluid Tank block.
 *
 * <p>Shows current fluid type, level bar, and status.
 * Colours adapt to the stored {@link FluidType}.
 */
@SuppressWarnings({"removal", "unchecked"})
public final class GenericFluidTankUIPage {

    private GenericFluidTankUIPage() {}

    // ── Session tracking ──────────────────────────────────────────────────────

    private record PlayerSession(Ref<EntityStore> entityRef, Store<EntityStore> store,
                                  Vector3i blockPos, HyUIPage page) {}

    private static final ConcurrentHashMap<PlayerRef, PlayerSession> SESSIONS      = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,    Integer>        WATCHER_COUNT = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

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
    public static void tickRefresh(GenericFluidTankState state,
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
            GenericFluidTankState state = lookupState(pos);

            PageBuilder builder = PageBuilder.pageForPlayer(playerRef)
                    .fromHtml(buildHtml(state))
                    .withLifetime(CustomPageLifetime.CanDismissOrCloseThroughInteraction);

            builder.addEventListener("gft-close-btn", CustomUIEventBindingType.Activating,
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

    private static void partialRefresh(PlayerSession session, GenericFluidTankState state) {
        HyUIPage page = session.page();
        if (page == null || state == null) return;

        String statusText;
        String statusColor;
        if (state.fluidMB <= 0) {
            statusText  = "Empty";
            statusColor = "#546e7a";
        } else if (state.fluidMB >= GenericFluidTankState.CAPACITY) {
            statusText  = "Full";
            statusColor = "#ef9a9a";
        } else {
            FluidType t = state.storedType();
            boolean hasSinkBelow = hasAdjacentSink(session.blockPos(), t);
            statusText  = hasSinkBelow ? "Draining" : "Storing";
            statusColor = hasSinkBelow ? "#ffd54f"  : "#81c784";
        }

        page.getById("gft-status-val", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(statusText)
                        .withStyle(new HyUIStyle().setTextColor(statusColor)));

        page.getById("gft-type-val", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(state.fluidDisplayName())
                        .withStyle(new HyUIStyle().setTextColor(state.fluidColor())));

        int barWidth = barFill(state.fluidPct());
        page.getById("gft-fluid-fill", PanelBuilder.class)
                .ifPresent(p -> p.withContentWidth(barWidth));

        page.getById("gft-fluid-label", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(state.fluidLabel()));

        page.updatePage(false);
    }

    // ── HTML builder ──────────────────────────────────────────────────────────

    private static final String STYLE = """
            <style>
            .gft-label { font-size: 12; color: #90a4ae; }
            .gft-value { font-size: 12; color: #eceff1; }
            </style>
            """;

    private static String buildHtml(GenericFluidTankState state) {
        int    mb       = state != null ? state.fluidMB   : 0;
        float  pct      = state != null ? state.fluidPct() : 0f;
        String lbl      = state != null ? state.fluidLabel()       : "0 / 16,000 mB";
        String typeName = state != null ? state.fluidDisplayName() : "Empty";
        String typeClr  = state != null ? state.fluidColor()       : "#546e7a";
        String barClr   = typeClr;
        int    barW     = barFill(pct);

        String statTxt, statClr;
        if (mb <= 0) {
            statTxt = "Empty";  statClr = "#546e7a";
        } else if (mb >= GenericFluidTankState.CAPACITY) {
            statTxt = "Full";   statClr = "#ef9a9a";
        } else {
            statTxt = "Storing"; statClr = "#81c784";
        }

        return STYLE + "<div style=\"anchor-width: 100%; anchor-height: 100%;"
                + " horizontal-align: center; vertical-align: middle;\">\n"
                + "<div id=\"gft-root\" style=\"layout-mode: Top; anchor-width: 420; anchor-height: 430;"
                + " background-color: #0d1f2d; border-radius: 16; padding: 20;\">\n"

                // Header
                + "<div style=\"layout-mode: Left; horizontal-align: center; margin-bottom: 12;\">"
                + "<p style=\"font-size: 18; color: #80cbc4; font-weight: bold;\">&#9881; Fluid Tank</p></div>\n"
                + "<div style=\"anchor-height: 2; background-color: #00695c; margin-bottom: 14;\"></div>\n"

                // Fluid type row
                + "<div style=\"layout-mode: Left; horizontal-align: space-between; margin-bottom: 10;\">"
                + "<p class=\"gft-label\">Fluid</p>"
                + "<p id=\"gft-type-val\" class=\"gft-value\" style=\"color: " + typeClr + ";\">"
                + typeName + "</p></div>\n"

                // Status row
                + "<div style=\"layout-mode: Left; horizontal-align: space-between; margin-bottom: 10;\">"
                + "<p class=\"gft-label\">Status</p>"
                + "<p id=\"gft-status-val\" class=\"gft-value\" style=\"color: " + statClr + ";\">"
                + statTxt + "</p></div>\n"

                // Capacity row
                + "<div style=\"layout-mode: Left; horizontal-align: space-between; margin-bottom: 16;\">"
                + "<p class=\"gft-label\">Capacity</p>"
                + "<p class=\"gft-value\" style=\"color: #90caf9;\">"
                + String.format("%,d", GenericFluidTankState.CAPACITY) + " mB</p></div>\n"

                // Fluid level bar
                + "<p class=\"gft-label\" style=\"margin-bottom: 6;\">Fluid Level</p>\n"
                + "<div style=\"layout-mode: Left; anchor-width: 380; anchor-height: 24;"
                + " background-color: #0d1117; border-radius: 12; margin-bottom: 6;\">"
                + "<div id=\"gft-fluid-fill\" style=\"anchor-width: " + barW
                + "; anchor-height: 24; background-color: " + barClr
                + "; border-radius: 12;\"></div></div>\n"
                + "<div style=\"layout-mode: Left; horizontal-align: center;\">"
                + "<p id=\"gft-fluid-label\" class=\"gft-value\" style=\"color: #b2dfdb;\">"
                + lbl + "</p></div>\n"

                // Hint
                + "<div style=\"anchor-height: 2; background-color: #00695c; margin-top: 16; margin-bottom: 10;\"></div>\n"
                + "<div style=\"layout-mode: Left; horizontal-align: center;\">"
                + "<p style=\"font-size: 10; color: #546e7a;\">Accepts any single fluid from top. Drains downward.</p></div>\n"

                // Close button
                + "<div style=\"layout-mode: Left; horizontal-align: center; margin-top: 12;\">"
                + "<button id=\"gft-close-btn\" style=\"background-color: #00695c;"
                + " border-radius: 8; padding: 8 20;\"><p style=\"color: #ffffff; font-size: 12;\">Close</p>"
                + "</button></div>\n"

                + "</div></div>";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int barFill(float pct) {
        return (int) Math.max(0, Math.min(380, 380 * pct));
    }

    private static String posKey(Vector3i p) {
        return p.x + "," + p.y + "," + p.z;
    }

    private static void decrementWatcher(Vector3i pos) {
        WATCHER_COUNT.computeIfPresent(posKey(pos), (k, v) -> v <= 1 ? null : v - 1);
    }

    private static GenericFluidTankState lookupState(Vector3i pos) {
        var capable = FluidNetwork.getAt(pos);
        return capable instanceof GenericFluidTankState gft ? gft : null;
    }

    private static boolean hasAdjacentSink(Vector3i pos, FluidType type) {
        if (type == null) return false;
        Vector3i below = new Vector3i(pos.x, pos.y - 1, pos.z);
        var sink = FluidNetwork.getAt(below);
        return sink != null && sink.canAcceptFluidFrom(type, pos);
    }
}
