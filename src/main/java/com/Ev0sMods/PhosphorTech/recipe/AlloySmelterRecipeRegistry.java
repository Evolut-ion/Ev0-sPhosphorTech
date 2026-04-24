package com.Ev0sMods.PhosphorTech.recipe;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.hypixel.hytale.logger.HytaleLogger;

/**
 * Registry for {@link AlloySmelterRecipe} instances.
 *
 * <p>Recipes are keyed by a canonical order-independent pair key so that
 * {@code findByInputs("IronBar", "CopperBar")} and
 * {@code findByInputs("CopperBar", "IronBar")} return the same recipe.
 */
public final class AlloySmelterRecipeRegistry {

    /** Singleton instance used throughout the plugin. */
    public static final AlloySmelterRecipeRegistry INSTANCE = new AlloySmelterRecipeRegistry();

    private final Map<String, AlloySmelterRecipe> byInputs = new ConcurrentHashMap<>();

    private AlloySmelterRecipeRegistry() {}

    // ── Registration ──────────────────────────────────────────────────────────

    public void register(AlloySmelterRecipe recipe) {
        byInputs.put(pairKey(recipe.input1ItemId(), recipe.input2ItemId()), recipe);
    }

    // ── Lookup ────────────────────────────────────────────────────────────────

    /**
     * Returns the recipe that matches both input items, or {@code null} when
     * no recipe applies.  The lookup is order-independent.
     */
    public AlloySmelterRecipe findByInputs(String id1, String id2) {
        return byInputs.get(pairKey(id1, id2));
    }

    // ── JSON loading ──────────────────────────────────────────────────────────

    /**
     * Loads recipe JSON files bundled under
     * {@code Server/Recipes/AlloySmelter/} from the classpath.
     *
     * @param pluginClass    class used to locate the classloader
     * @param resourceNames  file names relative to {@code Server/Recipes/AlloySmelter/}
     */
    public void loadFromClasspath(Class<?> pluginClass, String... resourceNames) {
        for (String name : resourceNames) {
            String path = "Server/Recipes/AlloySmelter/" + name;
            try (InputStream is = pluginClass.getClassLoader().getResourceAsStream(path)) {
                if (is == null) {
                    HytaleLogger.getLogger().atWarning()
                            .log("[AlloySmelterRegistry] Resource not found: " + path);
                    continue;
                }
                String json = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
                AlloySmelterRecipe recipe = parseJson(json);
                if (recipe != null) {
                    register(recipe);
                } else {
                    HytaleLogger.getLogger().atWarning()
                            .log("[AlloySmelterRegistry] Failed to parse recipe: " + name);
                }
            } catch (Throwable t) {
                HytaleLogger.getLogger().atWarning()
                        .log("[AlloySmelterRegistry] Error loading " + name + ": " + t.getMessage());
            }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** Canonical order-independent key: the lexicographically smaller ID comes first. */
    private static String pairKey(String a, String b) {
        return (a.compareTo(b) <= 0) ? (a + '\u0000' + b) : (b + '\u0000' + a);
    }

    private static AlloySmelterRecipe parseJson(String json) {
        String input1    = extractString(json, "Input1");
        String input2    = extractString(json, "Input2");
        String output1   = extractString(json, "Output1");
        int    out1Qty   = extractInt(json, "Output1Qty", 1);
        String output2   = extractString(json, "Output2");
        int    out2Qty   = extractInt(json, "Output2Qty", 1);
        int    cfCost    = extractInt(json, "CfCost", 1000);
        int    ticks     = extractInt(json, "Ticks", 120);

        if (input1 == null || input2 == null || output1 == null) return null;

        return new AlloySmelterRecipe(input1, input2, output1, out1Qty, output2, out2Qty, cfCost, ticks);
    }

    private static String extractString(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    private static int extractInt(String json, String key, int def) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return def;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return def;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        try { return Integer.parseInt(json.substring(start, end)); }
        catch (NumberFormatException e) { return def; }
    }
}
