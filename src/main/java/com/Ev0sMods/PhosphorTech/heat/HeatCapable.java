package com.Ev0sMods.PhosphorTech.heat;

/**
 * Implemented by any block that participates in the thermal network.
 *
 * <p>Heat is measured in <b>degrees Celsius</b>.  Ambient temperature is
 * {@value #AMBIENT_CELSIUS}°C; blocks cool towards ambient naturally.
 *
 * <p>Typical max temperatures by material:
 * <ul>
 *   <li>Clay pipe      —  600°C</li>
 *   <li>Copper pipe    —  900°C</li>
 *   <li>Potin pipe     — 1 000°C</li>
 *   <li>Mechanical Heater — 800°C</li>
 *   <li>Powered Heater    — 1 200°C</li>
 * </ul>
 */
public interface HeatCapable {

    /** Ambient (room) temperature in Celsius.  Blocks cool towards this. */
    double AMBIENT_CELSIUS = 20.0;

    /** Returns the current thermal energy of this block, in °C. */
    double getHeat();

    /**
     * Sets the current thermal energy of this block, clamped to
     * [{@value #AMBIENT_CELSIUS}, {@link #getMaxHeat()}].
     */
    void setHeat(double celsius);

    /** Maximum temperature this block can safely hold. */
    double getMaxHeat();

    /**
     * Whether this block can both receive and donate heat to neighbours
     * via the {@link HeatNetwork}.  Defaults to {@code true}.
     */
    default boolean canTransferHeat() { return true; }
}
