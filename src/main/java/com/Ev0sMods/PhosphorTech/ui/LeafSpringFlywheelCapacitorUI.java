package com.Ev0sMods.PhosphorTech.ui;

import com.Ev0sMods.PhosphorTech.blocks.LeafSpringFlywheelCapacitorState;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.lang.reflect.Method;

/**
 * Reflection-based bridge to {@link LeafSpringFlywheelCapacitorUIPage}.
 *
 * <p>Safe no-op when HyUI is absent at runtime.
 */
public final class LeafSpringFlywheelCapacitorUI {

    private LeafSpringFlywheelCapacitorUI() {}

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
                        "com.Ev0sMods.PhosphorTech.ui.LeafSpringFlywheelCapacitorUIPage");
                mOpenForced  = page.getMethod("openForced",
                        PlayerRef.class, Ref.class, Store.class, Vector3i.class);
                mHasWatcher  = page.getMethod("hasWatcher", Vector3i.class);
                mTickRefresh = page.getMethod("tickRefresh",
                        LeafSpringFlywheelCapacitorState.class, Store.class, Vector3i.class);
            } catch (Exception e) {
                HytaleLogger.getLogger().atWarning().log(
                        "[LeafSpringFlywheelCapacitorUI] Reflection init failed: " + e.getMessage());
            }
        } else {
            HytaleLogger.getLogger().atInfo().log(
                    "[LeafSpringFlywheelCapacitorUI] HyUI not present; UI is disabled.");
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
                    "[LeafSpringFlywheelCapacitorUI] openForced failed: " + t.getMessage());
        }
    }

    public static boolean hasWatcher(Vector3i pos) {
        if (!isAvailable()) return false;
        try {
            return Boolean.TRUE.equals(mHasWatcher.invoke(null, pos));
        } catch (Throwable t) {
            HytaleLogger.getLogger().atWarning().log(
                    "[LeafSpringFlywheelCapacitorUI] hasWatcher failed: " + t.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public static void tickRefresh(LeafSpringFlywheelCapacitorState state, Store<?> store, Vector3i pos) {
        if (!isAvailable()) return;
        try {
            mTickRefresh.invoke(null, state, store, pos);
        } catch (Throwable t) {
            HytaleLogger.getLogger().atWarning().log(
                    "[LeafSpringFlywheelCapacitorUI] tickRefresh failed: " + t.getMessage());
        }
    }
}
