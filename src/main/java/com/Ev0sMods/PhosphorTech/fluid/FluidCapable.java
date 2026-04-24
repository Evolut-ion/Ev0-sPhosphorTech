package com.Ev0sMods.PhosphorTech.fluid;

import org.joml.Vector3i;

/**
 * Implemented by any block that can store, receive, or provide fluids.
 *
 * <p>Both the Crystal Generator and the Steam Reservoir implement this
 * interface; pipes query it to route fluid through the network.
 */
public interface FluidCapable {

    // ── Acceptance (input) ────────────────────────────────────────────────────

    /** True if this block can currently accept fluid of the given type. */
    boolean canAcceptFluid(FluidType type);

    /**
     * Direction-aware variant of {@link #canAcceptFluid}.  {@code fromPos} is
     * the world position of the block trying to push fluid into this one.
     * Override to restrict which faces may supply fluid.
     */
    default boolean canAcceptFluidFrom(FluidType type, Vector3i fromPos) {
        return canAcceptFluid(type);
    }

    /**
     * Push up to {@code amount} mB of the given type into this block.
     *
     * @param simulate if {@code true}, do not modify state
     * @return mB actually accepted
     */
    int acceptFluid(FluidType type, int amount, boolean simulate);

    // ── Provision (output) ────────────────────────────────────────────────────

    /** True if this block currently has fluid of the given type to provide. */
    boolean canProvideFluid(FluidType type);

    /**
     * Direction-aware variant of {@link #canProvideFluid}.  {@code toPos} is
     * the world position of the block requesting fluid from this one.
     * Override to restrict which faces may extract fluid.
     */
    default boolean canProvideFluidTo(FluidType type, Vector3i toPos) {
        return canProvideFluid(type);
    }

    /**
     * Pull up to {@code amount} mB of the given type from this block.
     *
     * @param simulate if {@code true}, do not modify state
     * @return mB actually extracted
     */
    int extractFluid(FluidType type, int amount, boolean simulate);
}
