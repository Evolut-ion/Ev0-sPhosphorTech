package com.Ev0sMods.PhosphorTech.recipe;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;

/**
 * Static registry for {@link ProcessingRecipe} instances.
 *
 * <p>Recipes are keyed by {@code inputItemId} for fast lookup during machine ticks.
 * Pattern recipes (regex-matched, e.g. Metallurgy ore → dust) are stored separately
 * and checked as a fallback in {@link #findByInput(String)}.
 *
 * <h3>JSON recipe format</h3>
 * Files live at {@code Server/Recipes/<MachineName>/<outputItemId>.json}:
 * <pre>{@code
 * { "input": "Rock_Calcite", "cfCost": 500, "ticks": 90 }
 * }</pre>
 *
 * <h3>Pattern recipe format</h3>
 * Files live at {@code Server/Recipes/<MachineName>/Pattern_<n>.json}:
 * <pre>{@code
 * { "inputRegex": "(?:Oni_)?(\\w+)_Ore", "outputTemplate": "Ore_$1_Dust", "cfCost": 1000, "ticks": 90 }
 * }</pre>
 */
public final class ProcessingRecipeRegistry<T extends ProcessingRecipe> {

    // ── Per-machine registries ─────────────────────────────────────────────────

    /** Recipes registered for the Crusher. */
    public static final ProcessingRecipeRegistry<CrusherRecipe>   CRUSHER   = new ProcessingRecipeRegistry<>("Crusher",    CrusherRecipe.class,   CrusherRecipe.CODEC);
    /** Recipes registered for the Extractor. */
    public static final ProcessingRecipeRegistry<ExtractorRecipe> EXTRACTOR = new ProcessingRecipeRegistry<>("Extractor",  ExtractorRecipe.class, ExtractorRecipe.CODEC);
    /** Recipes registered for the Centrifuge. */
    public static final ProcessingRecipeRegistry<CentrifugeRecipe> CENTRIFUGE = new ProcessingRecipeRegistry<>("Centrifuge", CentrifugeRecipe.class, CentrifugeRecipe.CODEC);
    /** Recipes registered for the Sieve. */
    public static final ProcessingRecipeRegistry<SieveRecipe>     SIEVE     = new ProcessingRecipeRegistry<>("Sieve",      SieveRecipe.class,     SieveRecipe.CODEC);
    /** Recipes registered for the Press. */
    public static final ProcessingRecipeRegistry<PressRecipe>     PRESS     = new ProcessingRecipeRegistry<>("Press",      PressRecipe.class,     PressRecipe.CODEC);
    /** Recipes registered for the Lathe and Rod Puller (shared registry). */
    public static final ProcessingRecipeRegistry<LatheRecipe>     LATHE     = new ProcessingRecipeRegistry<>("Lathe",      LatheRecipe.class,     LatheRecipe.CODEC);

    // ── Instance fields ────────────────────────────────────────────────────────

    private final String machineName;
    private final Class<T> recipeClass;
    private final AssetBuilderCodec<String, T> codec;
    private final Map<String, ProcessingRecipe>  byInput          = new ConcurrentHashMap<>();
    private final List<PatternRecipe>            patterns         = new ArrayList<>();
    private final java.util.Set<String>          loadedResources  = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * The Hytale Asset Store for this machine's recipes.
     * Populated by {@code PhosphorTechPlugin.setup()} via {@link #buildAssetStore()}.
     */
    @SuppressWarnings("rawtypes")
    public HytaleAssetStore assetStore;

    // ── Constructor ────────────────────────────────────────────────────────────

    public ProcessingRecipeRegistry(String machineName, Class<T> recipeClass, AssetBuilderCodec<String, T> codec) {
        this.machineName = machineName;
        this.recipeClass = recipeClass;
        this.codec = codec;
    }

    // ── Asset store builder ────────────────────────────────────────────────────

    /**
     * Builds and stores the {@link HytaleAssetStore} for this machine.
     * Call once from the plugin's {@code setup()} method before registering
     * the store with the asset registry.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public HytaleAssetStore buildAssetStore() {
        HytaleAssetStore.Builder b = new HytaleAssetStore.Builder(
                String.class, recipeClass, new DefaultAssetMap()
        );
        b.setPath("Server/Recipes/" + machineName);
        b.setExtension(".json");
        b.setCodec(codec);
        b.setKeyFunction(r -> ((ProcessingRecipe) r).getId());
        assetStore = b.build();
        return assetStore;
    }

    // ── Registration API ───────────────────────────────────────────────────────

    /** Registers a concrete (non-pattern) recipe. */
    public void register(ProcessingRecipe recipe) {
        byInput.put(recipe.inputItemId(), recipe);
    }

    /** Registers a pattern-based (regex) recipe. */
    public void registerPattern(PatternRecipe pattern) {
        patterns.add(pattern);
    }

    // ── Lookup API ─────────────────────────────────────────────────────────────

    /**
     * Finds the recipe for the given input item ID.
     *
     * <p>Checks concrete recipes first, then pattern recipes.
     * Returns {@code null} when no recipe applies.
     */
    public ProcessingRecipe findByInput(String inputItemId) {
        ProcessingRecipe exact = byInput.get(inputItemId);
        if (exact != null) return exact;
        for (PatternRecipe pat : patterns) {
            ProcessingRecipe matched = pat.tryMatch(inputItemId);
            if (matched != null) return matched;
        }
        return null;
    }

    // ── JSON loading ───────────────────────────────────────────────────────────

    /**
     * Loads all recipe JSON files bundled under
     * {@code Server/Recipes/<machineName>/} from the classpath.
     *
     * <p>Call this once from {@code PhosphorTechPlugin#setup()} for each machine.
     *
     * <p>Files whose names start with {@code Pattern_} are loaded as pattern recipes.
     * All other {@code .json} files are treated as concrete recipes whose output item
     * ID is derived from the file name (without extension).
     */
    public void loadFromClasspath(Class<?> pluginClass, String... resourceNames) {
        for (String resourceName : resourceNames) {
            String path = "Server/Recipes/" + machineName + "/" + resourceName;
            try (InputStream is = pluginClass.getClassLoader().getResourceAsStream(path)) {
                if (is == null) {
                    HytaleLogger.getLogger().atWarning().log(
                            "[RecipeRegistry] Resource not found: " + path);
                    continue;
                }
                String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                loadedResources.add(resourceName);
                loadResource(resourceName, json);
            } catch (Exception e) {
                HytaleLogger.getLogger().atWarning().log(
                        "[RecipeRegistry] Failed to load " + path + ": " + e.getMessage());
            }
        }
    }

    /**
     * Scans every JAR and directory on the system classloader for recipe JSON files
     * under {@code Server/Recipes/<machineName>/} and loads them automatically.
     *
     * <p>This allows other mods to contribute recipes by simply bundling JSON files
     * at the correct path — no registration call needed in this plugin.
     * Files starting with {@code Pattern_} are loaded as pattern recipes; all others
     * as concrete recipes (filename without {@code .json} = output item ID, unless the
     * JSON contains an {@code "Output"} field).
     */
    public void scanAndLoad() {
        String prefix = "Server/Recipes/" + machineName + "/";
        try {
            Enumeration<URL> roots = ClassLoader.getSystemResources(prefix.substring(0, prefix.length() - 1));
            while (roots.hasMoreElements()) {
                URL url = roots.nextElement();
                String urlStr = url.toString();
                if (urlStr.startsWith("jar:")) {
                    // Strip "jar:" prefix and the "!/<path>" suffix to get the jar path
                    String jarPath = urlStr.substring(4, urlStr.indexOf("!/"));
                    if (jarPath.startsWith("file:")) jarPath = new java.net.URI(jarPath).getPath();
                    try (JarFile jar = new JarFile(new File(jarPath))) {
                        Enumeration<JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            JarEntry entry = entries.nextElement();
                            String name = entry.getName();
                            if (!name.startsWith(prefix) || !name.endsWith(".json")) continue;
                            String resourceName = name.substring(prefix.length());
                            if (resourceName.contains("/")) continue; // skip subdirectories
                            if (loadedResources.contains(resourceName)) continue;
                            try (InputStream is = jar.getInputStream(entry)) {
                                String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                                loadResource(resourceName, json);
                            } catch (Exception e) {
                                HytaleLogger.getLogger().atWarning().log(
                                        "[RecipeRegistry] Failed to load " + name + ": " + e.getMessage());
                            }
                        }
                    }
                } else if (urlStr.startsWith("file:")) {
                    File dir = new File(new java.net.URI(urlStr));
                    File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
                    if (files == null) continue;
                    for (File f : files) {
                        if (loadedResources.contains(f.getName())) continue;
                        try (InputStream is = new java.io.FileInputStream(f)) {
                            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                            loadResource(f.getName(), json);
                        } catch (Exception e) {
                            HytaleLogger.getLogger().atWarning().log(
                                    "[RecipeRegistry] Failed to load " + f + ": " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                    "[RecipeRegistry] scanAndLoad failed for " + machineName + ": " + e.getMessage());
        }
    }

    private void loadResource(String resourceName, String json) {
        if (resourceName.startsWith("Pattern_")) {
            parsePatternJson(json, resourceName);
        } else {
            String outputItemId = resourceName.replace(".json", "");
            parseRecipeJson(json, outputItemId);
        }
    }

    // ── Private JSON parsing ───────────────────────────────────────────────────

    /** Minimal JSON parser — avoids a full JSON library dependency. */
    private void parseRecipeJson(String json, String outputItemId) {
        String override = extractString(json, "Output");
        if (override != null && !override.isEmpty()) outputItemId = override;
        String input    = extractString(json, "Input");
        int    cfCost   = extractInt(json, "CfCost");
        int    ticks    = extractInt(json, "Ticks");
        int    qty      = extractInt(json, "OutputQty"); if (qty < 1) qty = 1;
        int    inputQty = extractInt(json, "InputQty");  if (inputQty < 1) inputQty = 1;
        if (input == null || cfCost <= 0 || ticks <= 0) {
            HytaleLogger.getLogger().atWarning().log(
                    "[RecipeRegistry] Invalid recipe JSON for output=" + outputItemId);
            return;
        }
        String bonusOutput = extractString(json, "BonusOutput");
        int    bonusQty    = extractInt(json, "BonusQty"); if (bonusQty < 1) bonusQty = 1;
        float  bonusChance = extractFloat(json, "BonusChance");
        String bonusOutput2 = extractString(json, "BonusOutput2");
        int    bonusQty2    = extractInt(json, "BonusQty2"); if (bonusQty2 < 1) bonusQty2 = 1;
        float  bonusChance2 = extractFloat(json, "BonusChance2");
        String bonusOutput3 = extractString(json, "BonusOutput3");
        int    bonusQty3    = extractInt(json, "BonusQty3"); if (bonusQty3 < 1) bonusQty3 = 1;
        float  bonusChance3 = extractFloat(json, "BonusChance3");
        String bonusOutput4 = extractString(json, "BonusOutput4");
        int    bonusQty4    = extractInt(json, "BonusQty4"); if (bonusQty4 < 1) bonusQty4 = 1;
        float  bonusChance4 = extractFloat(json, "BonusChance4");
        ProcessingRecipe recipe = new ProcessingRecipe(input, outputItemId, cfCost, ticks, qty, bonusOutput, bonusQty, bonusChance);
        recipe.inputQty = inputQty;
        recipe.bonusOutputItemId2 = bonusOutput2; recipe.bonusOutputQty2 = bonusQty2; recipe.bonusChance2 = bonusChance2;
        recipe.bonusOutputItemId3 = bonusOutput3; recipe.bonusOutputQty3 = bonusQty3; recipe.bonusChance3 = bonusChance3;
        recipe.bonusOutputItemId4 = bonusOutput4; recipe.bonusOutputQty4 = bonusQty4; recipe.bonusChance4 = bonusChance4;
        register(recipe);
        HytaleLogger.getLogger().atInfo().log(
                "[RecipeRegistry] Loaded: " + machineName + " " + input + " -> " + qty + "x " + outputItemId
                + " (" + cfCost + " CF, " + ticks + "t)"
                + (bonusOutput != null ? " +bonus: " + bonusQty + "x " + bonusOutput + " @" + bonusChance : ""));
    }

    private void parsePatternJson(String json, String resourceName) {
        String inputRegex      = extractString(json, "InputRegex");
        String outputTemplate  = extractString(json, "OutputTemplate");
        int    cfCost          = extractInt(json, "CfCost");
        int    ticks           = extractInt(json, "Ticks");
        int    qty             = extractInt(json, "OutputQty"); if (qty < 1) qty = 1;
        int    inputQty        = extractInt(json, "InputQty");  if (inputQty < 1) inputQty = 1;
        if (inputRegex == null || outputTemplate == null || cfCost <= 0 || ticks <= 0) {
            HytaleLogger.getLogger().atWarning().log(
                    "[RecipeRegistry] Invalid pattern JSON: " + resourceName);
            return;
        }
        registerPattern(new PatternRecipe(inputRegex, outputTemplate, cfCost, ticks, qty, inputQty));
        HytaleLogger.getLogger().atInfo().log(
                "[RecipeRegistry] Loaded pattern: " + machineName
                + " /" + inputRegex + "/ -> " + qty + "x " + outputTemplate
                + " (" + cfCost + " CF, " + ticks + "t)");
    }

    /** Extracts a JSON string value for the given key (simple, no-dependency impl). */
    private static String extractString(String json, String key) {
        String search = "\"" + key + "\"";
        int ki = json.indexOf(search);
        if (ki < 0) return null;
        int colon = json.indexOf(':', ki + search.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        String val = json.substring(q1 + 1, q2);
        // Unescape backslash sequences that may appear in regex fields
        return val.replace("\\\\", "\\");
    }

    /** Extracts a JSON integer value for the given key (simple, no-dependency impl). */
    private static int extractInt(String json, String key) {
        String search = "\"" + key + "\"";
        int ki = json.indexOf(search);
        if (ki < 0) return -1;
        int colon = json.indexOf(':', ki + search.length());
        if (colon < 0) return -1;
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\n'
                || json.charAt(start) == '\r' || json.charAt(start) == '\t')) start++;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        if (end == start) return -1;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Extracts a JSON float value for the given key; returns 0f if absent or invalid. */
    private static float extractFloat(String json, String key) {
        String search = "\"" + key + "\"";
        int ki = json.indexOf(search);
        if (ki < 0) return 0f;
        int colon = json.indexOf(':', ki + search.length());
        if (colon < 0) return 0f;
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\n'
                || json.charAt(start) == '\r' || json.charAt(start) == '\t')) start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.')) end++;
        if (end == start) return 0f;
        try {
            return Float.parseFloat(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0f;
        }
    }
}
