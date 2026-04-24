package com.Ev0sMods.PhosphorTech.mechanical;

/**
 * Marker interface for blocks that participate in the Gear / Joule network.
 *
 * <p>Implementing this interface allows a block to receive
 * {@link #onNeighborGearChanged()} notifications when adjacent nodes are
 * added or removed from the {@link GearNetwork}.
 */
public interface MechanicalCapable {

    /**
     * Called by {@link GearNetwork} whenever a neighbour registers or
     * unregisters.  Implementors should refresh connection-model visuals on
     * their next tick.
     */
    void onNeighborGearChanged();
}
