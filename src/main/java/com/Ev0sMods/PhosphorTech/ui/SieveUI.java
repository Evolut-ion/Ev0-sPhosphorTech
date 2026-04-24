package com.Ev0sMods.PhosphorTech.ui;

import com.Ev0sMods.PhosphorTech.blocks.SieveState;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3i;
import com.hypixel.hytale.logger.HytaleLogger;

public final class SieveUI {
    private SieveUI() {}

    public static void tickRefresh(SieveState state, Store<?> store, Vector3i pos) {
        try {
            Class<?> page = Class.forName("com.Ev0sMods.PhosphorTech.ui.SieveUIPage", true, Thread.currentThread().getContextClassLoader());
            page.getMethod("tickRefresh", SieveState.class, Store.class, Vector3i.class)
                .invoke(null, state, store, pos);
        } catch (Throwable t) {
            HytaleLogger.getLogger().atWarning().log("[SieveUI] Reflection init failed: " + t.getMessage());
        }
    }
}
