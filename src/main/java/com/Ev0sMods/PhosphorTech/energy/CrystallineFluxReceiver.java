package com.Ev0sMods.PhosphorTech.energy;

/**
 * Implemented by blocks that can receive Crystalline Flux (CF) from a network.
 *
 * <p>TODO(JOML-migration): no API changes expected here.
 */
public interface CrystallineFluxReceiver {

    /** Returns the amount of CF currently stored in this node. */
    long getCFStored();

    /** Returns the maximum CF this node can hold. */
    long getCFCapacity();

    /**
     * Attempt to insert {@code amount} CF into this node.
     *
     * @param amount   maximum CF to insert
     * @param simulate if {@code true}, the insertion is not committed
     * @return the amount actually accepted (≤ {@code amount})
     */
    long receiveCF(long amount, boolean simulate);
}
