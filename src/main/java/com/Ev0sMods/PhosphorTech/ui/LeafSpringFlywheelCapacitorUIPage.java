package com.Ev0sMods.PhosphorTech.ui;

import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.HyUIStyle;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PageBuilder;
import au.ellie.hyui.builders.PanelBuilder;
import com.Ev0sMods.PhosphorTech.blocks.LeafSpringFlywheelCapacitorState;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3i;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.concurrent.ConcurrentHashMap;

/**
 * HyUI page for the Leaf Spring Flywheel Capacitor block.
 *
 * <p>Displays current J storage, capacity (300 J), and live speed.
 * Called via reflection from {@link LeafSpringFlywheelCapacitorUI}.
 */
@SuppressWarnings({"removal", "unchecked"})
public final class LeafSpringFlywheelCapacitorUIPage {

    private LeafSpringFlywheelCapacitorUIPage() {}

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

    public static void tickRefresh(LeafSpringFlywheelCapacitorState state,
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
            LeafSpringFlywheelCapacitorState state = lookupState(pos);

            PageBuilder builder = PageBuilder.pageForPlayer(playerRef)
                    .fromHtml(buildHtml(state))
                    .withLifetime(CustomPageLifetime.CanDismissOrCloseThroughInteraction);

            builder.addEventListener("fly-close-btn", CustomUIEventBindingType.Activating,
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

    private static void partialRefresh(PlayerSession session, LeafSpringFlywheelCapacitorState state) {
        HyUIPage page = session.page();
        if (page == null || state == null) return;

        float jPct = jPct(state);
        boolean active = state.currentSpeed > 0;
        String statusText  = active ? "Charging" : (state.joulesStored > 0 ? "Stored" : "Idle");
        String statusColor = active ? "#aed581"  : (state.joulesStored > 0 ? "#ffd54f" : "#aaaaaa");

        page.getById("fly-status-val", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(statusText)
                        .withStyle(new HyUIStyle().setTextColor(statusColor)));

        page.getById("fly-j-fill", PanelBuilder.class)
                .ifPresent(p -> p.withStyle(
                        "anchor-width: " + barFill(jPct) + "; anchor-height: 24; "
                        + "background-color: " + jBarColor(jPct) + "; border-radius: 12;"));

        page.getById("fly-j-label", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(jLabel(state)));

        page.getById("fly-speed-val", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(
                        state.currentSpeed > 0
                                ? String.format("%.1f RPM", state.currentSpeed)
                                : "Idle"));

        page.updatePage(false);
    }

    // ── HTML builder ──────────────────────────────────────────────────────────

    private static String buildHtml(LeafSpringFlywheelCapacitorState state) {
        float  jPct      = jPct(state);
        String jLbl      = jLabel(state);
        double speed     = state != null ? state.currentSpeed : 0.0;
        boolean active   = speed > 0;
        String statText  = active ? "Charging" : (state != null && state.joulesStored > 0 ? "Stored" : "Idle");
        String statColor = active ? "#aed581"  : (state != null && state.joulesStored > 0 ? "#ffd54f" : "#aaaaaa");
        int barW         = barFill(jPct);
        String barColor  = jBarColor(jPct);

        return "<style>"
                + ".fly-label { font-size: 12; color: #9e9e9e; }"
                + ".fly-value { font-size: 13; color: #f5f5f5; font-weight: bold; }"
                + "</style>"
                + "<div style=\"anchor-width: 100%; anchor-height: 100%;"
                + "horizontal-align: center; vertical-align: middle;\">"
                + "<div style=\"layout-mode: Top; anchor-width: 380; anchor-height: 340;"
                + " background-color: #1a1f12; border-radius: 16; padding: 20;\">"

                // Header
                + "<div style=\"layout-mode: Left; horizontal-align: center; margin-bottom: 12;\">"
                + "<p style=\"font-size: 18; color: #c5e1a5; font-weight: bold;\">&#9881; Leaf Spring Flywheel Capacitor</p>"
                + "</div>"
                + "<div style=\"anchor-height: 2; background-color: #2a3d1a; margin-bottom: 14;\"></div>"

                // Status
                + "<div style=\"layout-mode: Left; horizontal-align: space-between; margin-bottom: 10;\">"
                + "<p class=\"fly-label\">Status</p>"
                + "<p id=\"fly-status-val\" class=\"fly-value\" style=\"color: " + statColor + ";\">" + statText + "</p>"
                + "</div>"

                // Speed
                + "<div style=\"layout-mode: Left; horizontal-align: space-between; margin-bottom: 10;\">"
                + "<p class=\"fly-label\">Gear Speed</p>"
                + "<p id=\"fly-speed-val\" class=\"fly-value\" style=\"color: #a5d6a7;\">"
                + (active ? String.format("%.1f RPM", speed) : "Idle") + "</p>"
                + "</div>"

                // Capacity
                + "<div style=\"layout-mode: Left; horizontal-align: space-between; margin-bottom: 16;\">"
                + "<p class=\"fly-label\">Capacity</p>"
                + "<p class=\"fly-value\" style=\"color: #aed581;\">300 J</p>"
                + "</div>"

                // J bar
                + "<p class=\"fly-label\" style=\"margin-bottom: 6;\">Energy Stored</p>"
                + "<div style=\"layout-mode: Left; anchor-width: 340; anchor-height: 24;"
                + " background-color: #0d1208; border-radius: 12; margin-bottom: 6;\">"
                + "<div id=\"fly-j-fill\" style=\"anchor-width: " + barW + "; anchor-height: 24;"
                + " background-color: " + barColor + "; border-radius: 12;\"></div>"
                + "</div>"
                + "<div style=\"layout-mode: Left; horizontal-align: center;\">"
                + "<p id=\"fly-j-label\" class=\"fly-value\" style=\"color: #dcedc8;\">" + jLbl + "</p>"
                + "</div>"

                // Divider + close
                + "<div style=\"anchor-height: 2; background-color: #2a3d1a; margin-top: 16; margin-bottom: 10;\"></div>"
                + "<div style=\"layout-mode: Left; horizontal-align: center;\">"
                + "<button id=\"fly-close-btn\" style=\"background-color: #2a3d1a;"
                + " border-radius: 8; padding: 6 16; color: #c5e1a5;\">Close</button>"
                + "</div>"
                + "</div></div>";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static float jPct(LeafSpringFlywheelCapacitorState s) {
        if (s == null) return 0f;
        return (float) (s.joulesStored / LeafSpringFlywheelCapacitorState.J_CAPACITY);
    }

    private static String jLabel(LeafSpringFlywheelCapacitorState s) {
        if (s == null) return "0.0 / 300.0 J";
        return String.format("%.1f / %.0f J", s.joulesStored, LeafSpringFlywheelCapacitorState.J_CAPACITY);
    }

    private static int barFill(float pct) {
        return Math.round(Math.max(0f, Math.min(1f, pct)) * 340);
    }

    private static String jBarColor(float pct) {
        if (pct >= 0.75f) return "#aed581";
        if (pct >= 0.40f) return "#dce775";
        if (pct >= 0.15f) return "#fff176";
        return "#ef9a9a";
    }

    private static LeafSpringFlywheelCapacitorState lookupState(Vector3i pos) {
        // State is passed directly via tickRefresh; this is only used at open time.
        // Return null safely — buildHtml handles null gracefully.
        return null;
    }

    private static void decrementWatcher(Vector3i pos) {
        String key = posKey(pos);
        WATCHER_COUNT.computeIfPresent(key, (k, v) -> v <= 1 ? null : v - 1);
    }

    private static String posKey(Vector3i pos) {
        return pos.x + "," + pos.y + "," + pos.z;
    }
}
