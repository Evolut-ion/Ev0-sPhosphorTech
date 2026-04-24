package com.Ev0sMods.PhosphorTech.ui;

import java.util.concurrent.ConcurrentHashMap;
import org.joml.Vector3i;
import com.Ev0sMods.PhosphorTech.blocks.SolarPanelState;
import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxNetwork;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.math.util.ChunkUtil;
import java.util.Map;
import java.lang.reflect.Method;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PageBuilder;
import au.ellie.hyui.builders.PanelBuilder;

public final class SolarPanelUIPage {
    private SolarPanelUIPage() {}
    private record PlayerSession(Ref<?> entityRef, Store<?> store, Vector3i blockPos, HyUIPage page) {}
    private static final ConcurrentHashMap<PlayerRef, PlayerSession> SESSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> WATCHER_COUNT = new ConcurrentHashMap<>();

    public static void openForced(PlayerRef playerRef, Ref<?> entityRef, Store<?> store, Vector3i pos) {
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

    public static void tickRefresh(SolarPanelState state, Store<?> store, Vector3i pos) {
        SESSIONS.forEach((playerRef, session) -> {
            if (!posKey(session.blockPos()).equals(posKey(pos))) return;
            HyUIPage page = session.page();
            if (page == null) return;
            partialRefresh(playerRef, session, state);
        });
    }

    private static void renderPage(PlayerRef playerRef, Ref<?> entityRef, Store<?> store, Vector3i pos) {
        try {
            SolarPanelState state = lookupState(store, pos);
            PageBuilder builder = PageBuilder.pageForPlayer(playerRef)
                    .fromHtml(buildHtml(state))
                    .withLifetime(CustomPageLifetime.CanDismissOrCloseThroughInteraction);
            builder.addEventListener("sp-close-btn", CustomUIEventBindingType.Activating,
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
            HyUIPage page = builder.open((Store)store);
            SESSIONS.compute(playerRef, (k, s) -> s == null ? null : new PlayerSession(s.entityRef(), s.store(), s.blockPos(), page));
        } catch (Throwable t) {
            SESSIONS.remove(playerRef);
        }
    }

    private static void partialRefresh(PlayerRef playerRef, PlayerSession session, SolarPanelState state) {
        HyUIPage page = session.page();
        if (page == null) return;
        long cf = state != null ? state.getCFStored() : 0;
        String cfLabel = cf + " CF";
        int height = state != null ? state.getPosition().y : 0;
        boolean sun = height > 120;
        String sunLabel = sun ? "Yes" : "No";
        String heightLabel = Integer.toString(height);
        String perTick = sun ? "8 CF/tick" : "0 CF/tick";
        page.getById("sp-height-label", LabelBuilder.class).ifPresent(lb -> lb.withText(heightLabel));
        page.getById("sp-sun-label", LabelBuilder.class).ifPresent(lb -> lb.withText(sunLabel));
        page.getById("sp-pertick-label", LabelBuilder.class).ifPresent(lb -> lb.withText(perTick));
        page.getById("sp-cf-label", LabelBuilder.class).ifPresent(lb -> lb.withText(cfLabel));
        page.updatePage(false);
    }

    private static String buildHtml(SolarPanelState state) {
        long cf = state != null ? state.getCFStored() : 0;
        String cfLabel = cf + " CF";
        int height = state != null ? state.getPosition().y : 0;
        boolean sun = height > 120;
        String sunLabel = sun ? "Yes" : "No";
        String perTick = sun ? "8 CF/tick" : "0 CF/tick";
        String leftPanel = "<div style='layout-mode: Top; anchor-width: 330; padding-top: 8; padding-bottom: 8; padding-left: 16; padding-right: 16;'>"
            + "<p class='title-label'>Solar Panel</p>"
            + "<div class='separator'></div>"
            + "<p class='section-label'>Status</p>"
            + "<div style='layout-mode: Left; horizontal-align: space-between; margin-bottom: 8;'><p class='info-label'>Height</p><p id='sp-height-label' class='info-label'>" + height + "</p></div>"
            + "<div style='layout-mode: Left; horizontal-align: space-between; margin-bottom: 8;'><p class='info-label'>Sun Out</p><p id='sp-sun-label' class='info-label'>" + sunLabel + "</p></div>"
            + "<div style='layout-mode: Left; horizontal-align: space-between; margin-bottom: 8;'><p class='info-label'>Generating</p><p id='sp-pertick-label' class='info-label'>" + perTick + "</p></div>"
            + "<div style='layout-mode: Left; horizontal-align: space-between; margin-bottom: 8;'><p class='info-label'>CF Stored</p><p id='sp-cf-label' class='info-label'>" + cfLabel + "</p></div>"
            + "<div class='separator'></div>"
            + "<div style='layout-mode: Top; horizontal-align: center; padding-top: 8;'><button id='sp-close-btn' class='secondary-button' style='anchor-width: 120; anchor-height: 30; font-size: 13;'>Close</button></div>"
            + "</div>";

        return STYLE + "<div style='anchor-width:100%;anchor-height:100%;horizontal-align:center;vertical-align:middle;'>"
            + "<div class='decorated-container' data-hyui-title='Solar Panel' style='anchor-height:400;anchor-width:480;'>"
            + "<div class='container-contents' style='layout-mode: Top; padding-top:12; padding-bottom:12; padding-left:16; padding-right:16; horizontal-align: center;'>"
            + "<div style='layout-mode: Left; horizontal-align: center;'>" + leftPanel + "</div>"
            + "</div></div></div>";
    }

        private static final String STYLE = """
            <style>
            .title-label { font-weight: bold; color: #d4aaff; font-size: 18; padding-top: 8; padding-bottom: 6; }
            .section-label { font-weight: bold; color: #bdcbd3; font-size: 14; padding-top: 6; padding-bottom: 2; }
            .info-label { color: #a0b8c8; font-size: 12; padding-top: 2; padding-bottom: 2; }
            .hint-label { color: #7a9aaa; font-size: 11; padding-top: 2; padding-bottom: 2; }
            .separator { layout-mode: Full; anchor-height: 1; background-color: #ffffff(0.15); margin-top: 6; margin-bottom: 6; }
            .secondary-button { background-color: #1e1e1e; border-color: #555555; border-width: 1; border-radius: 4; padding-left: 8; padding-right: 8; }
            </style>
            """;

    private static String posKey(Vector3i pos) {
        return pos.x + "," + pos.y + "," + pos.z;
    }
    private static void decrementWatcher(Vector3i pos) {
        String key = posKey(pos);
        WATCHER_COUNT.computeIfPresent(key, (k, v) -> v > 1 ? v - 1 : null);
    }
    private static SolarPanelState lookupState(Store<?> store, Vector3i pos) {
        try {
            Object node = CrystallineFluxNetwork.getAt(pos);
            if (node instanceof SolarPanelState s) return s;
        } catch (Throwable ignored) {}

        // Fallback: scan chunk store to find the block component reference at pos
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
                        Object comp = store.getComponent((Ref) ref, (com.hypixel.hytale.component.ComponentType) SolarPanelState.COMPONENT_TYPE);
                        if (comp instanceof SolarPanelState s) {
                            com.hypixel.hytale.logger.HytaleLogger.getLogger().atInfo().log("[SolarPanelUI] lookupState: found via chunk-scan at %s", pos);
                            return s;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        com.hypixel.hytale.logger.HytaleLogger.getLogger().atWarning().log("[SolarPanelUI] lookupState: node not found for %s", pos);
        return null;
    }

    private static volatile Method entityRefsMethod;
    private static volatile boolean entityRefsMethodResolved;

    private static Map<?, ?> entityRefsViaReflection(BlockComponentChunk bcc) {
        try {
            if (!entityRefsMethodResolved) {
                synchronized (SolarPanelUIPage.class) {
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
}
