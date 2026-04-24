package com.Ev0sMods.PhosphorTech.mechanical;

/**
 * Interface for blocks that provide Joules to the {@link GearNetwork}.
 *
 * <p>A provider generates mechanical power.  Every tick the network calls
 * {@link #getJoulesStored()} to inspect available power and
 * {@link #extractJoules} to drain it into adjacent consumers.
 *
 * <p><b>Unit note:</b> 1 J = 100 CF (see {@code EnergyUnits}).
 */
public interface JouleProvider {

    /** Current Joule buffer. */
    double getJoulesStored();

    /** Maximum Joule buffer capacity. */
    double getJoulesCapacity();

    /**
     * Speed multiplier of this provider. All propagated nodes inherit the
     * maximum speed seen along their connected chain this tick.
     *
     * @return speed ≥ 1.0
     */
    double getSpeed();

    /**
     * Remove up to {@code amount} Joules from this provider.
     *
     * @param amount   requested Joules
     * @param simulate {@code true} → no state change, dry-run only
     * @return Joules actually extracted
     */
    double extractJoules(double amount, boolean simulate);
}
