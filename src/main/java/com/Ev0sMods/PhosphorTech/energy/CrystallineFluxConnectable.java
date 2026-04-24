package com.Ev0sMods.PhosphorTech.energy;

/**
 * Implemented by CF network nodes that need immediate notification when the
 * network topology around them changes (i.e. an adjacent node is registered
 * or unregistered).
 *
 * <p>Currently implemented by {@code WireState} so that its connection-model
 * visual updates the moment a neighbouring wire or machine is placed or
 * destroyed, rather than waiting for the wire's own next tick.
 */
public interface CrystallineFluxConnectable {

    /**
     * Called by {@link CrystallineFluxNetwork#notifyNeighbors} immediately
     * after an adjacent node is registered or unregistered.
     *
     * <p>Implementations should mark their visual/connection state as dirty so
     * it is refreshed on the current or next tick. Implementations must
     * <em>not</em> throw exceptions.
     */
    void onNeighborCFChanged();
}
