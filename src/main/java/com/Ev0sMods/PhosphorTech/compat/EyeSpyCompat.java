package com.Ev0sMods.PhosphorTech.compat;

import com.hypixel.hytale.logger.HytaleLogger;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Reflection-based bridge that registers PhosphorTech's
 * {@link PhosphorTechInfoProvider} with EyeSpy's block HUD system.
 *
 * <p>Safe no-op when EyeSpy is absent at runtime — the provider class is
 * only loaded if EyeSpy's classes can be resolved first.
 */
public final class EyeSpyCompat {

    private EyeSpyCompat() {}

    private static final HytaleLogger LOG = HytaleLogger.getLogger();

    /**
     * Attempts to register our {@link PhosphorTechInfoProvider} into
     * EyeSpy's {@code EyeSpyHud.blockInfoProviders} list.
     *
     * <p>Must be called during plugin {@code setup()} — after all
     * PhosphorTech component types have been initialised.
     */
    @SuppressWarnings("unchecked")
    public static void register() {
        try {
            // 1. Check whether EyeSpy is on the classpath
            Class.forName("com.jarhax.eyespy.EyeSpy");
        } catch (ClassNotFoundException ignored) {
            LOG.atInfo().log("[PhosphorTech] EyeSpy not detected; HUD integration disabled.");
            return;
        }

        try {
            // 2. Load our provider (only possible because EyeSpy classes are present)
            Object provider = Class.forName(
                    "com.Ev0sMods.PhosphorTech.compat.PhosphorTechInfoProvider")
                    .getDeclaredConstructor().newInstance();

            // 3. Inject into EyeSpyHud.blockInfoProviders via reflection
            //    (EyeSpy has no public registration API yet — see the TODO in EyeSpyHud.java)
            Class<?> hudClass = Class.forName("com.jarhax.eyespy.impl.hud.EyeSpyHud");
            Field field = hudClass.getDeclaredField("blockInfoProviders");
            field.setAccessible(true);

            List<Object> providers = (List<Object>) field.get(null);
            providers.add(provider);

            LOG.atInfo().log("[PhosphorTech] EyeSpy integration registered successfully.");
        } catch (Exception e) {
            LOG.atWarning().log("[PhosphorTech] Failed to register EyeSpy provider: " + e.getMessage());
        }
    }
}
