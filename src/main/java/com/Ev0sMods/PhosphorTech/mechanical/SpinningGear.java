package com.Ev0sMods.PhosphorTech.mechanical;

/**
 * Interface implemented by gear blocks ({@code SmallGearState},
 * {@code LargeGearState}) that can be set spinning by the {@link GearNetwork}.
 *
 * <p>The network calls {@link #receiveSpinSignal} every tick while a connected
 * provider is active.  Each gear tracks a {@code spinTimer}: while the timer
 * is positive the block renders as spinning.  The timer is refreshed every
 * propagation tick so it only expires once all providers disconnect.
 */
public interface SpinningGear {

    /**
     * Inform this gear that it should spin this tick.
     *
     * @param speed speed multiplier (≥ 1.0) from the upstream provider
     */
    void receiveSpinSignal(double speed);

    /**
     * Inform this gear that it should spin this tick, with a direction hint.
     *
     * @param speed     speed multiplier (≥ 1.0) from the upstream provider
     * @param direction spin direction: {@code +1} = forward, {@code -1} = reverse
     */
    default void receiveSpinSignal(double speed, int direction) {
        receiveSpinSignal(speed);
    }

    /**
     * Returns the spin direction this node was last assigned.
     * {@code +1} = forward, {@code -1} = reverse.  Used by
     * {@link GearNetwork#propagateFrom} so that when a node re-propagates
     * from its own tick, downstream nodes receive the correct direction.
     */
    default int getSpinDirection() { return 1; }

    /**
     * Immediately stop this gear spinning.  Called when the network detects
     * that the power path to this node has been severed (e.g. an intermediate
     * block was destroyed).  The gear will resume if a new power path is
     * established on a subsequent tick.
     */
    default void stopSpin() {}

    /**
     * Reset animation timing so this node re-fires its animation on the
     * next tick boundary.  Called when a new component joins the network
     * so that all connected nodes visually sync up.
     * <p>Must NOT cause a block re-placement (no flip) — only animation timing.
     */
    default void resetAnimation() {}
}
