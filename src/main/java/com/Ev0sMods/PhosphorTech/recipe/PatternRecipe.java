package com.Ev0sMods.PhosphorTech.recipe;

import java.util.regex.Pattern;

/**
 * A pattern-based processing recipe for Metallurgy integration.
 *
 * <p>The {@link #inputPattern} is matched against an item's ID.
 * If it matches, {@link #outputTemplate} is used as the replacement
 * string (supports {@code $1}, {@code $2} capture-group references).
 *
 * <p>Example:
 * <pre>
 *   "Oni_(\w+)_Ore"  +  "Ore_$1_Dust"
 *   input:  Oni_Iron_Ore  →  output: Ore_Iron_Dust
 * </pre>
 */
public final class PatternRecipe {

    public final Pattern inputPattern;
    public final String  outputTemplate;
    public final int     cfCost;
    public final int     tickDuration;
    public final int     outputQty;
    public final int     inputQty;

    public PatternRecipe(String inputRegex, String outputTemplate, int cfCost, int tickDuration, int outputQty, int inputQty) {
        this.inputPattern   = Pattern.compile(inputRegex);
        this.outputTemplate = outputTemplate;
        this.cfCost         = cfCost;
        this.tickDuration   = tickDuration;
        this.outputQty      = outputQty;
        this.inputQty       = inputQty;
    }

    public PatternRecipe(String inputRegex, String outputTemplate, int cfCost, int tickDuration, int outputQty) {
        this(inputRegex, outputTemplate, cfCost, tickDuration, outputQty, 1);
    }

    /**
     * Attempts to produce a {@link ProcessingRecipe} for the given input ID.
     *
     * @return a concrete recipe, or {@code null} if the pattern does not match.
     */
    public ProcessingRecipe tryMatch(String inputItemId) {
        var matcher = inputPattern.matcher(inputItemId);
        if (!matcher.matches()) return null;
        String outputItemId = matcher.replaceFirst(outputTemplate);
        ProcessingRecipe r = new ProcessingRecipe(inputItemId, outputItemId, cfCost, tickDuration, outputQty);
        r.inputQty = inputQty;
        return r;
    }
}
