package com.Ev0sMods.PhosphorTech.mechanical;

/**
 * Marker interface for gear-network nodes that can act as a break in the line.
 *
 * <p>When {@link #isGearBlocked()} returns {@code true}, {@link GearNetwork}
 * BFS will neither propagate spin signals <em>through</em> this node nor pass
 * Joules through it as a conduit.  All machines on the far side of a locked
 * {@code GearBlocker} are effectively decoupled from their power source.
 *
 * <p>Implemented by {@code ClutchState}.
 */
public interface GearBlocker {

    /**
     * @return {@code true} when this node is currently blocking — i.e. the
     *         clutch is engaged / locked and no mechanical power should pass.
     */
    boolean isGearBlocked();
}
