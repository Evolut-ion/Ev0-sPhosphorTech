package com.Ev0sMods.PhosphorTech.rotary;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attempts to inject a dynamically-generated .blockymodel JSON string into
 * the engine's live model asset registry so the client can load it by path.
 *
 * <p>The injection strategy is best-effort via reflection, mirroring the
 * pattern already used in PhosphorTechPlugin for Item resource-type injection.
 * The engine's model registry class name is not part of the public plugin API,
 * so we probe several candidates.
 *
 * <p>Generated model paths follow the form:
 * {@code "Items/Icons/ItemsGenerated/RotaryPivot_Active.blockymodel"}
 * which is also the path declared in RotaryPivot.json's Active state.
 * Multiple simultaneous rotating pivots use position-scoped paths:
 * {@code "Items/Icons/ItemsGenerated/RP_<x>_<y>_<z>.blockymodel"}
 *
 * <p><b>If injection fails</b> the pivot block's fallback placeholder model
 * is shown during rotation instead of the actual block group geometry.
 * All mechanical behaviour (block removal, rotation math, restore) is
 * unaffected by injection success or failure.
 */
public final class RotaryAssetInjector {

    private RotaryAssetInjector() {}

    /** Path template for a per-pivot model slot. */
    public static String modelPath(int x, int y, int z) {
        return "Items/Icons/ItemsGenerated/RP_" + x + "_" + y + "_" + z + ".blockymodel";
    }

    /** Shared slot used when only one pivot rotates at a time. */
    public static final String SHARED_MODEL_PATH =
            "Items/Icons/ItemsGenerated/RotaryPivot_Active.blockymodel";

    // ── In-process virtual resource map ─────────────────────────────────────
    // If the engine's model loader queries a plugin-accessible provider, entries
    // written here will be served instead of reading from the JAR.
    private static final ConcurrentHashMap<String, String> VIRTUAL = new ConcurrentHashMap<>();

    /** Returns a previously injected model JSON, or null if not present. */
    public static String getVirtual(String path) {
        return VIRTUAL.get(path);
    }

    /**
     * Inject {@code modelJson} under {@code path}.
     *
     * <p>Three injection strategies are attempted in order:
     * <ol>
     *   <li>Write to the in-process virtual map (works if the engine calls
     *       {@link #getVirtual} via a registered asset provider — requires
     *       engine-side support that must be verified in-game).</li>
     *   <li>Reflectively locate the engine's model/prop asset map and call
     *       its put/register method directly.</li>
     *   <li>Write the file to the Hytale UserData directory so a subsequent
     *       mod-directory scan can pick it up (requires engine to watch for
     *       hot-reload changes).</li>
     * </ol>
     *
     * @return true if at least the virtual map entry was written
     */
    public static boolean inject(String path, String modelJson) {
        VIRTUAL.put(path, modelJson);
        tryReflectiveInject(path, modelJson);
        tryFileSystemFallback(path, modelJson);
        return true;
    }

    /** Remove a previously injected entry (call when rotation ends). */
    public static void evict(String path) {
        VIRTUAL.remove(path);
    }

    // ── Reflective injection ─────────────────────────────────────────────────

    private static void tryReflectiveInject(String path, String json) {
        // Candidate class names for the engine's model/prop asset registry.
        String[] candidates = {
            "com.hypixel.hytale.server.core.asset.type.prop.config.Prop",
            "com.hypixel.hytale.client.asset.model.ModelRegistry",
            "com.hypixel.hytale.client.asset.prop.PropRegistry",
            "com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockModel",
            "com.hypixel.hytale.asset.model.HytalePropModel",
        };
        for (String candidate : candidates) {
            try {
                Class<?> cls = Class.forName(candidate);
                Method getMap = null;
                for (String nm : new String[]{"getAssetMap", "getRegistry", "getMap", "getInstance"}) {
                    try { getMap = cls.getMethod(nm); break; } catch (NoSuchMethodException ignored) {}
                }
                if (getMap == null) continue;
                Object map = getMap.invoke(null);
                if (map == null) continue;

                Method putMethod = null;
                for (String pm : new String[]{"put", "register", "add", "putAsset", "registerAsset"}) {
                    try { putMethod = map.getClass().getMethod(pm, String.class, Object.class); break; } catch (NoSuchMethodException ignored) {}
                    try { putMethod = map.getClass().getMethod(pm, String.class, String.class); break; } catch (NoSuchMethodException ignored) {}
                }
                if (putMethod == null) continue;

                putMethod.invoke(map, path, json);
                System.out.println("[RotaryPivot] Model injected via " + candidate);
                return;
            } catch (Throwable ignored) {}
        }
    }

    // ── File-system fallback ─────────────────────────────────────────────────

    private static void tryFileSystemFallback(String path, String json) {
        try {
            String hytaleHome = System.getenv("HYTALE_HOME");
            if (hytaleHome == null) {
                String os = System.getProperty("os.name", "").toLowerCase();
                if (os.contains("win")) {
                    hytaleHome = System.getProperty("user.home") + "/AppData/Roaming/Hytale";
                } else if (os.contains("mac")) {
                    hytaleHome = System.getProperty("user.home") + "/Library/Application Support/Hytale";
                } else {
                    hytaleHome = System.getProperty("user.home") + "/.local/share/Hytale";
                }
            }
            // Write under UserData/Mods/PhosphorTech/Common/<path>
            java.io.File target = new java.io.File(
                    hytaleHome + "/UserData/Mods/PhosphorTech/Common/" + path);
            target.getParentFile().mkdirs();
            try (java.io.FileWriter fw = new java.io.FileWriter(target)) {
                fw.write(json);
            }
        } catch (Throwable ignored) {}
    }
}
