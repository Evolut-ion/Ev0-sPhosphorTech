package com.Ev0sMods.PhosphorTech.mechanical;

/**
 * Implemented by gear blocks that have a defined spin axis, allowing
 * {@link GearNetwork} to route connections correctly based on orientation.
 *
 * <p>The gear axis is the shaft axis (spine) — the axis the gear spins around.
 * Gear teeth mesh with neighbours in the <em>plane perpendicular</em> to this axis:
 * <ul>
 *   <li>{@link ShaftAxis#Y} — horizontal/flat gear; teeth in XZ plane (±X, ±Z)</li>
 *   <li>{@link ShaftAxis#X} — vertical gear, spine along X; teeth in YZ plane (±Y, ±Z)</li>
 *   <li>{@link ShaftAxis#Z} — vertical gear, spine along Z; teeth in XY plane (±X, ±Y)</li>
 * </ul>
 */
public interface GearConnectable {

    /** Returns the spin axis of this gear (the axis of its shaft / spine). */
    ShaftAxis getGearAxis();
}
