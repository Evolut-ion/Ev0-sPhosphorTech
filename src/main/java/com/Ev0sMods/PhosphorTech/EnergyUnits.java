package com.Ev0sMods.PhosphorTech;

/**
 * Energy unit conversion reference for PhosphorTech.
 *
 * <pre>
 *   1 J (Joule) = 100 CF (Crystalline Flux)
 * </pre>
 *
 * All internal values in PhosphorTech are stored and processed in CF.
 * Use the constants below when converting between external energy systems and CF.
 */
public final class EnergyUnits {

    /** Crystalline Flux units equivalent to 1 Joule. */
    public static final int CF_PER_JOULE = 100;

    /** Joules equivalent to 1 Crystalline Flux unit. */
    public static final double JOULES_PER_CF = 1.0 / CF_PER_JOULE;

    private EnergyUnits() {}

    /** Convert Joules to Crystalline Flux. */
    public static long joulesToCF(double joules) {
        return Math.round(joules * CF_PER_JOULE);
    }

    /** Convert Crystalline Flux to Joules. */
    public static double cfToJoules(long cf) {
        return cf * JOULES_PER_CF;
    }
}
