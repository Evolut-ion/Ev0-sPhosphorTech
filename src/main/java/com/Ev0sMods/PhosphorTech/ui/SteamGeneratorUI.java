package com.Ev0sMods.PhosphorTech.ui;

import com.Ev0sMods.PhosphorTech.blocks.SteamGeneratorState;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.lang.reflect.Method;

/**
 * Reflection-based bridge to {@link SteamGeneratorUIPage}.
 *
 * <p>All code paths that need to open or update the Steam Generator UI go
 * through this class.  If HyUI is absent at runtime every call is a safe no-op.
 */
public final class SteamGeneratorUI {

    private SteamGeneratorUI() {}

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
                        "com.Ev0sMods.PhosphorTech.ui.SteamGeneratorUIPage");
                mOpenForced  = page.getMethod("openForced",
                        PlayerRef.class, Ref.class, Store.class, Vector3i.class);
                mHasWatcher  = page.getMethod("hasWatcher", Vector3i.class);
                mTickRefresh = page.getMethod("tickRefresh",
                        SteamGeneratorState.class, Store.class, Vector3i.class);
            } catch (Exception e) {
                HytaleLogger.getLogger().atWarning().log(
                        "[SteamGeneratorUI] Reflection init failed: " + e.getMessage());
            }
        } else {
            HytaleLogger.getLogger().atInfo().log(
                    "[SteamGeneratorUI] HyUI not present; UI is disabled.");
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
                    "[SteamGeneratorUI] openForced failed: " + t.getMessage());
        }
    }

    public static boolean hasWatcher(Vector3i pos) {
        if (!isAvailable()) return false;
        try {
            return Boolean.TRUE.equals(mHasWatcher.invoke(null, pos));
        } catch (Throwable t) {
            HytaleLogger.getLogger().atWarning().log(
                    "[SteamGeneratorUI] hasWatcher failed: " + t.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public static void tickRefresh(SteamGeneratorState state, Store<?> store, Vector3i pos) {
        if (!isAvailable()) return;
        try {
            mTickRefresh.invoke(null, state, store, pos);
        } catch (Throwable t) {
            HytaleLogger.getLogger().atWarning().log(
                    "[SteamGeneratorUI] tickRefresh failed: " + t.getMessage());
        }
    }
}
