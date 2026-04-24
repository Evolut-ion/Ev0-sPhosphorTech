package com.Ev0sMods.PhosphorTech.mechanical;

/**
 * Implemented by blocks that transmit rotation along a fixed axis.
 *
 * <p>Shafts only connect to nodes that share the same axis or that are
 * positioned directly along their axis (i.e. neighbours in the ±axis direction).
 * They do <em>not</em> bend; a shaft on the X axis may only connect to
 * nodes at (±1, 0, 0) relative to it.
 */
public interface ShaftConnectable {

    /** The fixed axis this block is aligned along. */
    ShaftAxis getShaftAxis();
}
