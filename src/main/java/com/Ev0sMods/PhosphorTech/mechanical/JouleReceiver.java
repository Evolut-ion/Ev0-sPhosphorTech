package com.Ev0sMods.PhosphorTech.mechanical;

/**
 * Interface for blocks that consume Joules from the {@link GearNetwork}.
 *
 * <p><b>Unit note:</b> 1 J = 100 CF (see {@code EnergyUnits}).
 */
public interface JouleReceiver {

    /** Current Joule buffer. */
    double getJoulesStored();

    /** Maximum Joule buffer. */
    double getJoulesCapacity();

    /**
     * Accept up to {@code amount} Joules from an adjacent provider.
     *
     * @param amount   Joules offered
     * @param speed    speed multiplier from the upstream provider chain
     * @param simulate {@code true} → dry-run only
     * @return Joules actually accepted
     */
    double receiveJoules(double amount, double speed, boolean simulate);
}
