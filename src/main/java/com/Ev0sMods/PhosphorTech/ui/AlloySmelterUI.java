package com.Ev0sMods.PhosphorTech.ui;

import com.Ev0sMods.PhosphorTech.blocks.AlloySmelterState;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.lang.reflect.Method;

/**
 * Reflection-based bridge to {@link AlloySmelterUIPage}.
 * Safe no-op when HyUI is absent at runtime.
 */
public final class AlloySmelterUI {

    private AlloySmelterUI() {}

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
                Class<?> page = Class.forName("com.Ev0sMods.PhosphorTech.ui.AlloySmelterUIPage", true, Thread.currentThread().getContextClassLoader());
                mOpenForced  = page.getMethod("openForced",
                        PlayerRef.class, Ref.class, Store.class, Vector3i.class);
                mHasWatcher  = page.getMethod("hasWatcher", Vector3i.class);
                mTickRefresh = page.getMethod("tickRefresh",
                        AlloySmelterState.class, Store.class, Vector3i.class);
                mPing        = page.getMethod("ping", Vector3i.class);
            } catch (Exception e) {
                HytaleLogger.getLogger().atWarning().log(
                        "[AlloySmelterUI] Reflection init failed: " + e.getMessage());
            }
        } else {
            HytaleLogger.getLogger().atInfo().log("[AlloySmelterUI] HyUI not present; UI disabled.");
        }
    }

    public static boolean isAvailable() { ensureInit(); return HYUI_PRESENT && mOpenForced != null; }

    public static void openForced(PlayerRef playerRef, Ref<?> entityRef,
                                   Store<?> store, Vector3i pos) {
        if (!isAvailable()) return;
        try { mOpenForced.invoke(null, playerRef, entityRef, store, pos); }
        catch (Throwable t) {
            HytaleLogger.getLogger().atWarning()
                    .log("[AlloySmelterUI] openForced failed: " + t.getMessage());
        }
    }

    public static boolean hasWatcher(Vector3i pos) {
        if (!isAvailable()) return false;
        try {
            Object r = mHasWatcher.invoke(null, pos);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) { return false; }
    }

    public static void tickRefresh(AlloySmelterState state, Store<?> store, Vector3i pos) {
        if (!isAvailable()) return;
        try { mTickRefresh.invoke(null, state, store, pos); }
        catch (Throwable t) {
            HytaleLogger.getLogger().atWarning()
                    .log("[AlloySmelterUI] tickRefresh failed: " + t.getMessage());
        }
    }

    public static void ping(Vector3i pos) {
        if (!isAvailable()) return;
        try { mPing.invoke(null, pos); }
        catch (Throwable ignored) {}
    }
}
