package com.Ev0sMods.PhosphorTech.ui;

import com.Ev0sMods.PhosphorTech.blocks.PumpState;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.lang.reflect.Method;

/**
 * Reflection-based bridge to {@link PumpUIPage}.
 *
 * <p>All code that needs to open or update the Fluid Pump UI goes through
 * this class so that HyUI remains an optional compile-time dependency.
 */
public final class PumpUI {

    private PumpUI() {}

    private static volatile boolean sInitDone = false;
    private static boolean HYUI_PRESENT = false;
    private static Method mOpenForced;
    private static Method mHasWatcher;
    private static Method mTickRefresh;

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
                Class<?> page = Class.forName(
                        "com.Ev0sMods.PhosphorTech.ui.PumpUIPage");
                mOpenForced  = page.getMethod("openForced",
                        PlayerRef.class, Ref.class, Store.class, Vector3i.class);
                mHasWatcher  = page.getMethod("hasWatcher",  Vector3i.class);
                mTickRefresh = page.getMethod("tickRefresh",
                        PumpState.class, Store.class, Vector3i.class);
            } catch (Exception e) {
                HytaleLogger.getLogger().atWarning().log(
                        "[PumpUI] Reflection init failed: " + e.getMessage());
            }
        } else {
            HytaleLogger.getLogger().atInfo().log(
                    "[PumpUI] HyUI not present; UI is disabled.");
        }
    }

    public static boolean isAvailable() {
        return HYUI_PRESENT && mOpenForced != null;
    }

    @SuppressWarnings("unchecked")
    public static void openForced(PlayerRef playerRef, Ref<?> entityRef,
                                  Store<?> store, Vector3i pos) {
        if (!isAvailable()) return;
        try {
            mOpenForced.invoke(null, playerRef, entityRef, store, pos);
        } catch (Throwable t) {
            HytaleLogger.getLogger().atWarning().log(
                    "[PumpUI] openForced failed: " + t.getMessage());
        }
    }

    public static boolean hasWatcher(Vector3i pos) {
        if (!isAvailable()) return false;
        try {
            return Boolean.TRUE.equals(mHasWatcher.invoke(null, pos));
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public static void tickRefresh(PumpState state, Store<?> store, Vector3i pos) {
        if (!isAvailable()) return;
        try {
            mTickRefresh.invoke(null, state, store, pos);
        } catch (Throwable t) {
            HytaleLogger.getLogger().atWarning().log(
                    "[PumpUI] tickRefresh failed: " + t.getMessage());
        }
    }
}
