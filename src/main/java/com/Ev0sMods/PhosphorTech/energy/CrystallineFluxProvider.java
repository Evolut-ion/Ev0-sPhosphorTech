package com.Ev0sMods.PhosphorTech.energy;

/**
 * Implemented by blocks that can supply Crystalline Flux (CF) to a network.
 *
 * <p>TODO(JOML-migration): no API changes expected here.
 */
public interface CrystallineFluxProvider {

    /** Returns the amount of CF currently stored in this node. */
    long getCFStored();

    /** Returns the maximum CF this node can store. */
    long getCFCapacity();

    /**
     * Attempt to extract {@code amount} CF from this node.
     *
     * @param amount   maximum CF to extract
     * @param simulate if {@code true}, the extraction is not committed
     * @return the amount actually extracted (≤ {@code amount})
     */
    long extractCF(long amount, boolean simulate);
}
