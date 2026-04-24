package com.Ev0sMods.PhosphorTech.ui;

import com.Ev0sMods.PhosphorTech.blocks.ExtractorState;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.lang.reflect.Method;

/** Reflection-based bridge to {@link ExtractorUIPage}. Safe no-op when HyUI is absent. */
public final class ExtractorUI {

    private ExtractorUI() {}

    private static volatile boolean sInitDone = false;
    private static boolean HYUI_PRESENT = false;
    private static Method mOpenForced;
    private static Method mHasWatcher;
    private static Method mTickRefresh;
    private static Method mPing;

    private static synchronized void ensureInit() {
        if (sInitDone) { return; }
        sInitDone = true;
        boolean hyui = false;
        try {
            Class.forName("au.ellie.hyui.builders.PageBuilder", true, Thread.currentThread().getContextClassLoader());
            hyui = true;
        } catch (ClassNotFoundException ignored) {}
        HYUI_PRESENT = hyui;

        if (HYUI_PRESENT) {
            try {
                Class<?> page = Class.forName("com.Ev0sMods.PhosphorTech.ui.ExtractorUIPage", true, Thread.currentThread().getContextClassLoader());
                mOpenForced  = page.getMethod("openForced",
                        PlayerRef.class, Ref.class, Store.class, Vector3i.class);
                mHasWatcher  = page.getMethod("hasWatcher", Vector3i.class);
                mTickRefresh = page.getMethod("tickRefresh",
                        ExtractorState.class, Store.class, Vector3i.class);
                mPing        = page.getMethod("ping", Vector3i.class);
            } catch (Exception e) {
                HytaleLogger.getLogger().atWarning().log(
                        "[ExtractorUI] Reflection init failed: " + e.getMessage());
            }
        } else {
            HytaleLogger.getLogger().atInfo().log("[ExtractorUI] HyUI not present; UI is disabled.");
        }
    }

    public static boolean isAvailable() { ensureInit(); return HYUI_PRESENT && mOpenForced != null; }

    public static void openForced(PlayerRef playerRef, Ref<?> entityRef,
                                  Store<?> store, Vector3i pos) {
        if (!isAvailable()) return;
        try { mOpenForced.invoke(null, playerRef, entityRef, store, pos); }
        catch (Throwable t) {
            HytaleLogger.getLogger().atWarning().log("[ExtractorUI] openForced failed: " + t.getMessage());
        }
    }

    public static boolean hasWatcher(Vector3i pos) {
        if (!isAvailable()) return false;
        try { return Boolean.TRUE.equals(mHasWatcher.invoke(null, pos)); }
        catch (Throwable t) { return false; }
    }

    public static void tickRefresh(ExtractorState state, Store<?> store, Vector3i pos) {
        if (!isAvailable()) return;
        try { mTickRefresh.invoke(null, state, store, pos); }
        catch (Throwable t) {
            HytaleLogger.getLogger().atWarning().log("[ExtractorUI] tickRefresh failed: " + t.getMessage());
        }
    }

    public static void ping(Vector3i pos) {
        if (!isAvailable() || mPing == null) return;
        try { mPing.invoke(null, pos); }
        catch (Throwable ignored) {}
    }
}
