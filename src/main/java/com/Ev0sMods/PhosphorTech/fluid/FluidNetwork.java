package com.Ev0sMods.PhosphorTech.fluid;

import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.compat.VectorCompat;
import com.Ev0sMods.PhosphorTech.heat.HeatCapable;

/**
 * Static registry of all fluid-capable blocks in the world, keyed by world position.
 *
 * <p>Mirrors the design of {@code CrystallineFluxNetwork} for CF.  Every
 * block implementing {@link FluidCapable} must call {@link #register} on its
 * first tick (once the position is resolved) and {@link #unregister} when it
 * is invalidated/removed.
 *
 * <p>Thread safety: all mutations use {@link ConcurrentHashMap}.
 */
public final class FluidNetwork {

    private FluidNetwork() {}

    private static final ConcurrentHashMap<String, FluidCapable> NODES =
            new ConcurrentHashMap<>();

    // ── Registration ─────────────────────────────────────────────────────────

    public static void register(Vector3i pos, FluidCapable capable) {
        NODES.put(VectorCompat.posKey(pos), capable);
    }

    public static void unregister(Vector3i pos) {
        NODES.remove(VectorCompat.posKey(pos));
    }

    /**
     * Remove the entry at {@code pos} only if the registered value is exactly
     * {@code expectedNode} (identity comparison).  Prevents a stale or cloned
     * component from evicting a live registration at the same position.
     */
    public static void unregisterExact(Vector3i pos, Object expectedNode) {
        NODES.remove(VectorCompat.posKey(pos), expectedNode);
    }

    /** Returns the {@link FluidCapable} at {@code pos}, or {@code null}. */
    public static FluidCapable getAt(Vector3i pos) {
        return NODES.get(VectorCompat.posKey(pos));
    }

    // ── Transfer helper ───────────────────────────────────────────────────────

    /**
     * Transfer fluid from {@code source} at {@code sourcePos} to all adjacent
     * {@link FluidCapable} sinks that can accept the given type.
     *
     * <p>At most {@code maxAmount} mB is transferred in total.</p>
     *
     * @param fluidType  the fluid type to transfer
     * @param sourcePos  world position of the source block
     * @param source     the source {@link FluidCapable}
     * @param maxAmount  maximum mB to transfer per call
     * @return total mB transferred
     */
    public static int pushToAdjacent(FluidType fluidType, Vector3i sourcePos,
                                     FluidCapable source, int maxAmount) {
        return pushToAdjacent(fluidType, sourcePos, source, maxAmount, null);
    }

    /**
     * Transfer fluid from {@code source} to adjacent sinks, optionally
     * excluding one position (to prevent oscillation with the pull source).
     */
    public static int pushToAdjacent(FluidType fluidType, Vector3i sourcePos,
                                     FluidCapable source, int maxAmount,
                                     Vector3i excludePos) {
        if (maxAmount <= 0) return 0;

        int[][] offsets = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        int totalMoved = 0;
        int budget = maxAmount;

        for (int[] o : offsets) {
            if (budget <= 0) break;
            Vector3i adj = VectorCompat.vec3i(
                    sourcePos.x + o[0], sourcePos.y + o[1], sourcePos.z + o[2]);
            if (excludePos != null
                    && adj.x == excludePos.x
                    && adj.y == excludePos.y
                    && adj.z == excludePos.z) continue;
            FluidCapable sink = NODES.get(VectorCompat.posKey(adj));
            if (sink == null || sink == source) continue;
            if (!sink.canAcceptFluidFrom(fluidType, sourcePos)) continue;

            int canExtract = source.extractFluid(fluidType, budget, true);
            if (canExtract <= 0) break;

            int accepted = sink.acceptFluid(fluidType, canExtract, false);
            if (accepted > 0) {
                source.extractFluid(fluidType, accepted, false);
                totalMoved += accepted;
                budget     -= accepted;
            }
        }
        return totalMoved;
    }

    /**
     * Same as {@link #pushToAdjacent} but also sets the temperature on any
     * {@link HeatCapable} sink that receives fluid.  Used when the source has
     * a temperature that should be inherited by the carrying pipe (e.g. hot
     * water leaving a Steam Generator).
     */
    public static int pushToAdjacentWithHeat(FluidType fluidType, Vector3i sourcePos,
                                             FluidCapable source, int maxAmount,
                                             double transferTempCelsius) {
        if (maxAmount <= 0) return 0;

        int[][] offsets = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        int totalMoved = 0;
        int budget = maxAmount;

        for (int[] o : offsets) {
            if (budget <= 0) break;
            Vector3i adj = VectorCompat.vec3i(
                    sourcePos.x + o[0], sourcePos.y + o[1], sourcePos.z + o[2]);
            FluidCapable sink = NODES.get(VectorCompat.posKey(adj));
            if (sink == null || sink == source) continue;
            if (!sink.canAcceptFluidFrom(fluidType, sourcePos)) continue;

            int canExtract = source.extractFluid(fluidType, budget, true);
            if (canExtract <= 0) break;

            int accepted = sink.acceptFluid(fluidType, canExtract, false);
            if (accepted > 0) {
                source.extractFluid(fluidType, accepted, false);
                totalMoved += accepted;
                budget     -= accepted;
                // Apply the fluid temperature to the receiving pipe
                if (sink instanceof HeatCapable hc) {
                    hc.setHeat(Math.max(hc.getHeat(), transferTempCelsius));
                }
            }
        }
        return totalMoved;
    }
}
