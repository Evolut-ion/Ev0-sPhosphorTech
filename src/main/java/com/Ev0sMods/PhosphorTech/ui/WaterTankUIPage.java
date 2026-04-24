package com.Ev0sMods.PhosphorTech.ui;

import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.HyUIStyle;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PageBuilder;
import au.ellie.hyui.builders.PanelBuilder;
import com.Ev0sMods.PhosphorTech.blocks.WaterTankState;
import com.Ev0sMods.PhosphorTech.fluid.FluidNetwork;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.math.util.ChunkUtil;
import java.util.Map;
import java.lang.reflect.Method;
import org.joml.Vector3i;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.concurrent.ConcurrentHashMap;

/**
 * HyUI page for the Water Tank block.
 *
 * <p>Layout: centred panel showing water level bar, status, and capacity.
 *
 * <p>Called from {@link WaterTankUI} via reflection so that HyUI remains
 * an optional dependency.
 */
@SuppressWarnings({"removal", "unchecked"})
public final class WaterTankUIPage {

    private WaterTankUIPage() {}

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
    public static void tickRefresh(WaterTankState state,
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
            WaterTankState state = lookupState(pos);

            PageBuilder builder = PageBuilder.pageForPlayer(playerRef)
                    .fromHtml(buildHtml(state))
                    .withLifetime(CustomPageLifetime.CanDismissOrCloseThroughInteraction);

            // Close button
            builder.addEventListener("wt-close-btn", CustomUIEventBindingType.Activating,
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

    private static void partialRefresh(PlayerSession session, WaterTankState state) {
        HyUIPage page = session.page();
        if (page == null || state == null) return;

        boolean providing = state.waterMB > 0 && hasAdjacentSink(session.blockPos());
        boolean filling   = state.waterMB < WaterTankState.WATER_CAPACITY;
        String statusText  = providing ? "Providing" : (filling ? "Filling" : "Full");
        String statusColor = providing ? "#64b5f6"   : (filling ? "#ffd54f" : "#81c784");

        page.getById("wt-status-val", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(statusText)
                        .withStyle(new HyUIStyle().setTextColor(statusColor)));

        int barWidth = barFill(state.waterPct());
        page.getById("wt-water-fill", PanelBuilder.class)
                .ifPresent(p -> p.withContentWidth(barWidth));

        page.getById("wt-water-label", LabelBuilder.class)
                .ifPresent(lb -> lb.withText(state.waterLabel()));

        page.updatePage(false);
    }

    // ── HTML builder ──────────────────────────────────────────────────────────

    private static String buildHtml(WaterTankState state) {
        int waterMB    = state != null ? state.waterMB : 0;
        float pct      = state != null ? state.waterPct() : 0f;
        String lbl     = state != null ? state.waterLabel() : "0 / 10,000 mB";
        String statTxt = "Idle";
        String statClr = "#aaaaaa";
        if (state != null && waterMB > 0) {
            statTxt = "Filling";
            statClr = "#ffd54f";
        }
        int barW = barFill(pct);

        return STYLE + """
                <div style="anchor-width: 100%%; anchor-height: 100%%;
                            horizontal-align: center; vertical-align: middle;">
                <div id="wt-root" style="layout-mode: Top; anchor-width: 400; anchor-height: 400;
                     background-color: #0d1f2d; border-radius: 16; padding: 20;">

                    <!-- Header -->
                    <div style="layout-mode: Left; horizontal-align: center; margin-bottom: 12;">
                        <p style="font-size: 18; color: #64b5f6; font-weight: bold;">
                            &#128167; Water Tank
                        </p>
                    </div>
                    <div style="anchor-height: 2; background-color: #1565c0; margin-bottom: 14;"></div>

                    <!-- Status -->
                    <div style="layout-mode: Left; horizontal-align: space-between; margin-bottom: 10;">
                        <p class="wt-label">Status</p>
                        <p id="wt-status-val" class="wt-value" style="color: \
                """ + statClr + ";"  + "\">" + statTxt + """
                        </p>
                    </div>

                    <!-- Capacity row -->
                    <div style="layout-mode: Left; horizontal-align: space-between; margin-bottom: 16;">
                        <p class="wt-label">Capacity</p>
                        <p class="wt-value" style="color: #90caf9;">10,000 mB</p>
                    </div>

                    <!-- Water bar -->
                    <p class="wt-label" style="margin-bottom: 6;">Water Level</p>
                    <div style="layout-mode: Left; anchor-width: 360; anchor-height: 24;
                         background-color: #0d1117; border-radius: 12; margin-bottom: 6;">
                        <div id="wt-water-fill" style="anchor-width: \
                """ + barW + "; anchor-height: 24; background-color: #1565c0; border-radius: 12;\"></div>\n" + """
                    </div>
                    <div style="layout-mode: Left; horizontal-align: center;">
                        <p id="wt-water-label" class="wt-value" style="color: #bbdefb;">
                """ + lbl + """
                        </p>
                    </div>

                    <!-- Hint -->
                    <div style="anchor-height: 2; background-color: #1565c0; margin-top: 16; margin-bottom: 10;"></div>
                    <div style="layout-mode: Left; horizontal-align: center;">
                        <p style="font-size: 10; color: #546e7a;">
                            Connects to adjacent pipes. Outputs water downward.
                        </p>
                    </div>

                    <!-- Close button -->
                    <div style="layout-mode: Left; horizontal-align: center; margin-top: 12;">
                        <button id="wt-close-btn" style="background-color: #1565c0;
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
        return (int) Math.max(0, Math.min(360, 360.0f * pct));
    }

    /** True if any adjacent FluidCapable can accept water from this tank. */
    private static boolean hasAdjacentSink(Vector3i pos) {
        if (pos == null) return false;
        int[][] offsets = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int[] o : offsets) {
            Vector3i adj = new Vector3i(pos.x + o[0], pos.y + o[1], pos.z + o[2]);
            var cap = FluidNetwork.getAt(adj);
            if (cap != null && cap.canAcceptFluid(
                    com.Ev0sMods.PhosphorTech.fluid.FluidType.WATER)) return true;
        }
        return false;
    }

    private static WaterTankState lookupState(Vector3i pos) {
        try {
            Object node = FluidNetwork.getAt(pos);
            if (node instanceof WaterTankState s) return s;
        } catch (Throwable ignored) {}

        // Fallback: scan ChunkStore for component at this position
        try {
            // store not available here; try to find via the global ChunkStore if accessible via Thread context
            // But WaterTank UI is typically called with no Store reference; so best-effort only.
            return null;
        } catch (Throwable ignored) { return null; }
    }

    private static volatile Method entityRefsMethod;
    private static volatile boolean entityRefsMethodResolved;

    private static Map<?, ?> entityRefsViaReflection(BlockComponentChunk bcc) {
        try {
            if (!entityRefsMethodResolved) {
                synchronized (WaterTankUIPage.class) {
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
              .wt-label  { font-size: 12; color: #64b5f6; }
              .wt-value  { font-size: 13; color: #e3f2fd; }
            </style>
            """;
}
