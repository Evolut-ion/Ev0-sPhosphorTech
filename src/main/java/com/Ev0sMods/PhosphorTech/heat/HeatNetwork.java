package com.Ev0sMods.PhosphorTech.heat;

import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.compat.VectorCompat;

/**
 * Static registry and routing hub for the thermal network.
 *
 * <p>Every block implementing {@link HeatCapable} calls {@link #register} on
 * its first tick once its world position is resolved, and {@link #unregister}
 * (or {@link #unregisterExact}) from its {@code invalidate()} hook.
 *
 * <p><b>Heat propagation model:</b>
 * <ol>
 *   <li>A heat source calls {@link #pushHeat(Vector3i, double)} each tick,
 *       which distributes heat to all connected nodes (BFS, max 64 hops),
 *       attenuating by {@value #ATTENUATION_PER_HOP}°C per hop.</li>
 *   <li>Every tick, registered nodes cool towards {@link HeatCapable#AMBIENT_CELSIUS}
 *       at rate {@value #COOLING_RATE_PER_TICK}°C/tick (call
 *       {@link #tickCooling(Vector3i)} from your block's tick).</li>
 *   <li>{@link #getNetworkAverageHeat(Vector3i)} BFS-averages the heat of all
 *       connected nodes for UI display.</li>
 * </ol>
 *
 * <p>Thread safety: mutations use {@link ConcurrentHashMap}.
 */
public final class HeatNetwork {

    private HeatNetwork() {}

    /** How much heat is lost per BFS hop when propagating from a source. */
    public static final double ATTENUATION_PER_HOP = 5.0;

    /** Passive cooling rate towards ambient per tick (for nodes near a provider). */
    public static final double COOLING_RATE_PER_TICK = 0.5;

    /**
     * Convective heat transfer coefficient {@code h} used in Q = h·A·ΔT.
     * Applied per tick to nodes farther than {@value #MAX_HOPS_FROM_PROVIDER} hops
     * from any registered provider.
     */
    public static final double CONVECTION_H = 0.05;

    /** Effective surface area {@code A} used in Q = h·A·ΔT (normalised to 1 block face). */
    public static final double CONVECTION_A = 1.0;

    /**
     * BFS-hop radius within which passive cooling applies instead of convection.
     * Nodes further than this from every provider lose heat via Q = h·A·ΔT.
     */
    public static final int MAX_HOPS_FROM_PROVIDER = 3;

    /** Maximum BFS distance a heat signal travels. */
    private static final int MAX_PROPAGATION_HOPS = 64;

    private static final ConcurrentHashMap<String, HeatCapable> NODES =
            new ConcurrentHashMap<>();

    /**
     * Positions of active heat providers (Powered Heater, Mechanical Heater, etc.).
     * Only providers may call {@link #pushHeat}; the enforcement is inside that method.
     */
    private static final ConcurrentHashMap<String, Boolean> PROVIDERS =
            new ConcurrentHashMap<>();

    // ── Registration ─────────────────────────────────────────────────────────

    public static void register(Vector3i pos, HeatCapable node) {
        NODES.put(VectorCompat.posKey(pos), node);
    }

    public static void unregister(Vector3i pos) {
        NODES.remove(VectorCompat.posKey(pos));
    }

    public static void unregisterExact(Vector3i pos, Object expected) {
        NODES.remove(VectorCompat.posKey(pos), expected);
    }

    public static HeatCapable getAt(Vector3i pos) {
        return NODES.get(VectorCompat.posKey(pos));
    }

    // ── Provider registration ─────────────────────────────────────────────────

    /**
     * Marks {@code pos} as an active heat provider.
     * Must be called from the provider block's tick once its position is resolved.
     */
    public static void registerProvider(Vector3i pos) {
        PROVIDERS.put(VectorCompat.posKey(pos), Boolean.TRUE);
    }

    public static void unregisterProvider(Vector3i pos) {
        PROVIDERS.remove(VectorCompat.posKey(pos));
    }

    public static boolean isProvider(Vector3i pos) {
        return PROVIDERS.containsKey(VectorCompat.posKey(pos));
    }

    // ── Facing-direction utility ──────────────────────────────────────────────

    /**
     * Converts a cardinal/vertical facing name to a unit block offset.
     * Accepted values (case-insensitive): North, South, East, West, Up, Down.
     * Defaults to South if the name is unrecognised.
     */
    public static Vector3i facingDelta(String facing) {
        if (facing == null) return new Vector3i(0, 0, 1);
        return switch (facing.toLowerCase(java.util.Locale.ROOT)) {
            case "north" -> new Vector3i(0,  0, -1);
            case "south" -> new Vector3i(0,  0,  1);
            case "east"  -> new Vector3i(1,  0,  0);
            case "west"  -> new Vector3i(-1, 0,  0);
            case "up"    -> new Vector3i(0,  1,  0);
            case "down"  -> new Vector3i(0, -1,  0);
            default      -> new Vector3i(0,  0,  1);
        };
    }

    // ── Heat propagation ─────────────────────────────────────────────────────

    /**
     * BFS from {@code sourcePos}, pushing {@code heat}°C to every connected
     * {@link HeatCapable} node, attenuating by {@value #ATTENUATION_PER_HOP}°C
     * per hop.  Stops when heat falls below ambient or the hop limit is reached.
     *
     * @param sourcePos BFS origin (usually the heater block position)
     * @param heat      temperature to push, in °C
     */
    public static void pushHeat(Vector3i sourcePos, double heat) {
        if (heat <= HeatCapable.AMBIENT_CELSIUS) return;
        // Only registered providers may initiate heat propagation.
        if (!PROVIDERS.containsKey(VectorCompat.posKey(sourcePos))) return;

        java.util.ArrayDeque<Vector3i> queue   = new java.util.ArrayDeque<>();
        java.util.Map<String, Double>  visited = new java.util.HashMap<>();

        String startKey = VectorCompat.posKey(sourcePos);
        visited.put(startKey, heat);
        // Seed with direct neighbours only; the source block itself is not propagated to
        for (int[] d : OFFSETS) {
            Vector3i nb = new Vector3i(sourcePos.x + d[0], sourcePos.y + d[1], sourcePos.z + d[2]);
            String   k  = VectorCompat.posKey(nb);
            HeatCapable node = NODES.get(k);
            if (node == null || !node.canTransferHeat()) continue;
            double arrivedHeat = heat - ATTENUATION_PER_HOP;
            if (arrivedHeat <= HeatCapable.AMBIENT_CELSIUS) continue;
            if (!visited.containsKey(k)) {
                visited.put(k, arrivedHeat);
                queue.add(nb);
            }
        }

        int limit = MAX_PROPAGATION_HOPS;
        while (!queue.isEmpty() && limit-- > 0) {
            Vector3i cur  = queue.poll();
            double   curH = visited.get(VectorCompat.posKey(cur));
            HeatCapable node = NODES.get(VectorCompat.posKey(cur));
            if (node != null) {
                // Raise to the higher of current or arrived heat (don't cool via push)
                if (curH > node.getHeat()) {
                    node.setHeat(Math.min(curH, node.getMaxHeat()));
                }
            }
            double nextHeat = curH - ATTENUATION_PER_HOP;
            if (nextHeat <= HeatCapable.AMBIENT_CELSIUS) continue;
            for (int[] d : OFFSETS) {
                Vector3i nb = new Vector3i(cur.x + d[0], cur.y + d[1], cur.z + d[2]);
                String   k  = VectorCompat.posKey(nb);
                HeatCapable nbNode = NODES.get(k);
                if (nbNode == null || !nbNode.canTransferHeat()) continue;
                if (!visited.containsKey(k)) {
                    visited.put(k, nextHeat);
                    queue.add(nb);
                }
            }
        }
    }

    /**
     * Cools the node at {@code pos} towards {@link HeatCapable#AMBIENT_CELSIUS}
     * by {@value #COOLING_RATE_PER_TICK}°C.  Call once per tick from the block's
     * {@code tick()} method.
     */
    /**
     * Cools the node at {@code pos} by one tick.
     *
     * <ul>
     *   <li>Nodes that are themselves providers, or are within
     *       {@value #MAX_HOPS_FROM_PROVIDER} BFS hops of a provider, use the
     *       standard passive rate ({@value #COOLING_RATE_PER_TICK}°C/tick).</li>
     *   <li>Nodes beyond that radius lose heat via the convection formula
     *       Q&nbsp;=&nbsp;h·A·ΔT (where ΔT = current − ambient).</li>
     * </ul>
     */
    public static void tickCooling(Vector3i pos) {
        HeatCapable node = NODES.get(VectorCompat.posKey(pos));
        if (node == null) return;
        double current = node.getHeat();
        if (current <= HeatCapable.AMBIENT_CELSIUS) return;

        String key = VectorCompat.posKey(pos);
        double loss;
        if (PROVIDERS.containsKey(key) || isWithinHopsOfProvider(pos, MAX_HOPS_FROM_PROVIDER)) {
            // Warm zone: standard passive radiation cooling.
            loss = COOLING_RATE_PER_TICK;
        } else {
            // Outside warm zone: convective loss Q = h · A · ΔT
            double deltaT = current - HeatCapable.AMBIENT_CELSIUS;
            loss = CONVECTION_H * CONVECTION_A * deltaT;
        }
        node.setHeat(Math.max(HeatCapable.AMBIENT_CELSIUS, current - loss));
    }

    /**
     * Returns {@code true} if any registered provider is reachable from {@code pos}
     * within {@code maxHops} hops through {@link #NODES}-connected blocks.
     */
    private static boolean isWithinHopsOfProvider(Vector3i pos, int maxHops) {
        java.util.ArrayDeque<Vector3i> queue   = new java.util.ArrayDeque<>();
        java.util.Set<String>          visited = new java.util.HashSet<>();
        String startKey = VectorCompat.posKey(pos);
        visited.add(startKey);
        if (PROVIDERS.containsKey(startKey)) return true;
        queue.add(pos);
        for (int hop = 0; hop < maxHops; hop++) {
            int sz = queue.size();
            if (sz == 0) break;
            while (sz-- > 0) {
                Vector3i cur = queue.poll();
                for (int[] d : OFFSETS) {
                    Vector3i nb  = new Vector3i(cur.x + d[0], cur.y + d[1], cur.z + d[2]);
                    String   nbk = VectorCompat.posKey(nb);
                    if (!visited.add(nbk)) continue;
                    if (PROVIDERS.containsKey(nbk)) return true;
                    if (NODES.containsKey(nbk))     queue.add(nb);
                }
            }
        }
        return false;
    }

    /**
     * Returns the average heat (°C) across all connected {@link HeatCapable}
     * nodes reachable from {@code pos} via a BFS.  Used for UI display.
     */
    public static double getNetworkAverageHeat(Vector3i pos) {
        java.util.ArrayDeque<Vector3i> queue   = new java.util.ArrayDeque<>();
        java.util.Set<String>          visited = new java.util.HashSet<>();
        String startKey = VectorCompat.posKey(pos);
        if (!NODES.containsKey(startKey)) return HeatCapable.AMBIENT_CELSIUS;
        visited.add(startKey);
        queue.add(pos);
        double sum   = 0.0;
        int    count = 0;
        int    limit = MAX_PROPAGATION_HOPS * 2;
        while (!queue.isEmpty() && limit-- > 0) {
            Vector3i cur  = queue.poll();
            HeatCapable node = NODES.get(VectorCompat.posKey(cur));
            if (node != null) { sum += node.getHeat(); count++; }
            for (int[] d : OFFSETS) {
                Vector3i nb = new Vector3i(cur.x + d[0], cur.y + d[1], cur.z + d[2]);
                String   k  = VectorCompat.posKey(nb);
                if (NODES.containsKey(k) && visited.add(k)) queue.add(nb);
            }
        }
        return count > 0 ? sum / count : HeatCapable.AMBIENT_CELSIUS;
    }

    // ── Adjacency ─────────────────────────────────────────────────────────────

    private static final int[][] OFFSETS = {
        {1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}
    };
}
