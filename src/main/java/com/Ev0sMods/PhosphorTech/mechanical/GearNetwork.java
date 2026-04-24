package com.Ev0sMods.PhosphorTech.mechanical;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.compat.VectorCompat;

/**
 * Static registry and routing hub for the mechanical Joule network.
 *
 * <p>Mirrors the design of {@code CrystallineFluxNetwork} for CF.  Every
 * gear / crank / grinder calls {@link #register} on its first tick once its
 * world position is resolved, and {@link #unregister} (or
 * {@link #unregisterExact}) from its {@code invalidate()} hook.
 *
 * <p>Providers propagate spinning state to all reachable connected nodes each
 * tick via {@link #propagateFrom}.  Speed is taken as the provider's speed
 * value; all connected nodes animate at that same speed (with per-size phase
 * offset for visual interlock).
 *
 * <p>Thread safety: all mutations use {@link ConcurrentHashMap}.
 */
public final class GearNetwork {
    // ── Heartbeat for periodic animation sync ───────────────────────────────
    private static int heartbeatCounter = 0;
    private static final int HEARTBEAT_INTERVAL = 1800; // 1800 ticks = 1 minute at 30 TPS

    /**
     * Call this once per tick from any gear/shaft tick to enable periodic sync.
     * Triggers a network-wide animation sync every HEARTBEAT_INTERVAL ticks.
     * Uses the first registered node as the sync origin.
     */
    public static void heartbeatTick() {
        heartbeatCounter++;
        if (heartbeatCounter >= HEARTBEAT_INTERVAL) {
            heartbeatCounter = 0;
            // Use any node as the sync origin (if present)
            if (!NODES.isEmpty()) {
                String anyKey = NODES.keySet().iterator().next();
                Vector3i origin = VectorCompat.parsePosKey(anyKey);
                syncAnimations(origin);
            }
        }
    }

    /**
     * Public sync trigger for use by block states on state change.
     * Restarts all animations in the connected network.
     */
    public static void syncAnimations(Vector3i pos) {
        resetConnectedAnimations(pos);
    }

    private GearNetwork() {}

    /** Strong-reference map so gears are always accessible (like FluidNetwork). */
    private static final ConcurrentHashMap<String, Object> NODES = new ConcurrentHashMap<>();

    /** Block-type IDs of every block that participates in the gear network. */
    private static final java.util.Set<String> CONNECTABLE_TYPE_IDS =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final AtomicInteger OPS_SINCE_PURGE = new AtomicInteger(0);
    private static final int PURGE_INTERVAL = 64;

    /**
     * Global tick counter advanced automatically inside {@link #propagateFrom}.
     * All gears read this to synchronise animation retrigger so every gear in a
     * network fires {@code playAnimation} on the same tick boundary.
     */
    private static final AtomicInteger GLOBAL_TICK    = new AtomicInteger(0);
    /** Nanotime frame index of the last {@link #propagateFrom} call that advanced the tick. */
    private static volatile long       LAST_TICK_FRAME = -1L;
    /** Server tick period in nanoseconds (50 ms at 20 TPS). */
    private static final long TICK_NS = 50_000_000L;

    /**
     * Per-tick direction claims used for conflict detection.
     * Maps posKey → direction (+1 or -1) or 0 if a conflict has been detected.
     * Cleared at the start of each new server tick (when {@link #LAST_TICK_FRAME} advances).
     * When two different power sources reach the same node with opposing directions
     * the node is flagged (0) and idled immediately.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> TICK_DIR_CLAIMS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Monotonically increasing generation counter.  Incremented whenever a
     * node is registered or unregistered so that cached BFS results are
     * automatically invalidated whenever the network topology changes.
     */
    private static final java.util.concurrent.atomic.AtomicLong NETWORK_GENERATION =
            new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * Cached result of a previous {@link #propagateFrom} BFS for a given
     * source position.  Re-used every tick when the network topology and
     * source speed have not changed, eliminating all per-tick HashMap
     * allocations and BFS traversal overhead.
     */
    private static final class PropagationCache {
        /** Value of {@link #NETWORK_GENERATION} when this cache was built. */
        long   generation;
        /** Source speed when this cache was built — invalidate if speed changes. */
        double speed;
        /** Delivery list: alternating [posKey, direction (+1/-1)] per entry. */
        String[] keys;
        int[]    directions;
    }

    /** Per-source BFS result cache.  Key = {@code VectorCompat.posKey(from)}. */
    private static final java.util.concurrent.ConcurrentHashMap<String, PropagationCache> PROP_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Clears the propagation cache for all sources (e.g. after a GearBlocker toggle). */
    public static void invalidatePropCache() {
        PROP_CACHE.clear();
    }

    /** Current global tick value. Read by gears to determine animation phase. */
    public static int getCurrentTick() { return GLOBAL_TICK.get(); }

    // ── Public configuration ──────────────────────────────────────────────────

    public static void registerConnectableType(String blockTypeId) {
        CONNECTABLE_TYPE_IDS.add(blockTypeId);
    }

    public static boolean isConnectableType(String blockTypeId) {
        return CONNECTABLE_TYPE_IDS.contains(blockTypeId);
    }

    // ── Registration ─────────────────────────────────────────────────────────

    /**
     * Register a mechanical node.  {@code node} should implement
     * {@link JouleProvider} and/or {@link JouleReceiver}.
     */
    public static void register(Vector3i pos, Object node) {
        NODES.put(VectorCompat.posKey(pos), node);
        NETWORK_GENERATION.incrementAndGet();
        if (OPS_SINCE_PURGE.incrementAndGet() >= PURGE_INTERVAL) purgeDeadEntries();
        notifyNeighbors(pos);
        resetConnectedAnimations(pos);
    }

    public static void unregister(Vector3i pos) {
        NODES.remove(VectorCompat.posKey(pos));
        NETWORK_GENERATION.incrementAndGet();
        PROP_CACHE.remove(VectorCompat.posKey(pos));
        disconnectFloodFill(pos);
        notifyNeighbors(pos);
    }

    public static void unregisterExact(Vector3i pos, Object expectedNode) {
        String key = VectorCompat.posKey(pos);
        if (NODES.remove(key, expectedNode)) {
            NETWORK_GENERATION.incrementAndGet();
            PROP_CACHE.remove(key);
            disconnectFloodFill(pos);
            notifyNeighbors(pos);
        }
    }

    /**
     * BFS from {@code removedPos} through previously-reachable nodes.
     * Any {@link SpinningGear} found that can no longer be reached from
     * a still-active path gets {@link SpinningGear#stopSpin()} called
     * immediately, rather than waiting for its {@code spinTimer} to expire.
     *
     * <p>Nodes that are still reachable from another provider will have their
     * timer refreshed on the next propagation tick anyway.
     */
    private static void disconnectFloodFill(Vector3i removedPos) {
        java.util.ArrayDeque<Vector3i> queue   = new java.util.ArrayDeque<>();
        java.util.Set<String>         visited = new java.util.HashSet<>();
        // Seed with all neighbours of the removed position that are still registered.
        for (int[] d : new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}) {
            Vector3i nb = new Vector3i(removedPos.x + d[0], removedPos.y + d[1], removedPos.z + d[2]);
            String   k  = VectorCompat.posKey(nb);
            if (NODES.containsKey(k) && visited.add(k)) queue.add(nb);
        }
        while (!queue.isEmpty()) {
            Vector3i cur  = queue.poll();
            Object   node = NODES.get(VectorCompat.posKey(cur));
            if (node instanceof SpinningGear g) g.stopSpin();
            for (Vector3i adj : neighborsOf(cur)) {
                String k = VectorCompat.posKey(adj);
                if (NODES.containsKey(k) && visited.add(k)) queue.add(adj);
            }
        }
    }

    /**
     * BFS from {@code sourcePos} through all connected nodes, calling
     * {@link SpinningGear#stopSpin()} on each.  Used when a provider stops
     * producing power but remains in the network (e.g. hand crank revolution ends).
     */
    public static void stopConnectedFrom(Vector3i sourcePos) {
        java.util.ArrayDeque<Vector3i> queue   = new java.util.ArrayDeque<>();
        java.util.Set<String>          visited = new java.util.HashSet<>();
        String startKey = VectorCompat.posKey(sourcePos);
        visited.add(startKey);
        for (Vector3i adj : neighborsOf(sourcePos)) {
            String k = VectorCompat.posKey(adj);
            if (NODES.containsKey(k) && visited.add(k)) queue.add(adj);
        }
        int limit = 128;
        while (!queue.isEmpty() && limit-- > 0) {
            Vector3i cur  = queue.poll();
            Object   node = NODES.get(VectorCompat.posKey(cur));
            if (node instanceof SpinningGear g) g.stopSpin();
            for (Vector3i adj : neighborsOf(cur)) {
                String k = VectorCompat.posKey(adj);
                if (NODES.containsKey(k) && visited.add(k)) queue.add(adj);
            }
        }
    }

    public static Object getAt(Vector3i pos) {
        return NODES.get(VectorCompat.posKey(pos));
    }

    /** Returns the number of gear-network nodes adjacent (horizontally) to {@code pos}. */
    public static int getConnectedCount(Vector3i pos) {
        if (pos == null) return 0;
        int count = 0;
        for (Vector3i adj : neighborsOf(pos)) {
            if (getAt(adj) != null) count++;
        }
        return count;
    }

    // ── Neighbor notification ─────────────────────────────────────────────────

    public static void notifyNeighbors(Vector3i pos) {
        // Always notify all 6 directions so shaft neighbours also receive the event.
        Vector3i[] all6 = {
            new Vector3i(pos.x + 1, pos.y, pos.z), new Vector3i(pos.x - 1, pos.y, pos.z),
            new Vector3i(pos.x, pos.y + 1, pos.z), new Vector3i(pos.x, pos.y - 1, pos.z),
            new Vector3i(pos.x, pos.y, pos.z + 1), new Vector3i(pos.x, pos.y, pos.z - 1),
        };
        for (Vector3i adj : all6) {
            Object node = getAt(adj);
            if (node instanceof MechanicalCapable c) {
                try { c.onNeighborGearChanged(); } catch (Throwable ignored) {}
            }
        }
    }

    // ── Animation restart on join ─────────────────────────────────────────────

    /**
     * BFS from {@code pos} through all reachable nodes, calling
     * {@link SpinningGear#resetAnimation()} on each so they re-fire their
     * looping animation in sync with the newly-joined component.
     */
    private static void resetConnectedAnimations(Vector3i pos) {
        java.util.ArrayDeque<Vector3i> queue   = new java.util.ArrayDeque<>();
        java.util.Set<String>          visited = new java.util.HashSet<>();
        String startKey = VectorCompat.posKey(pos);
        visited.add(startKey);
        // Seed with the newly registered position's neighbours.
        for (Vector3i adj : neighborsOf(pos)) {
            String k = VectorCompat.posKey(adj);
            if (NODES.containsKey(k) && visited.add(k)) queue.add(adj);
        }
        int limit = 128;
        while (!queue.isEmpty() && limit-- > 0) {
            Vector3i cur  = queue.poll();
            Object   node = NODES.get(VectorCompat.posKey(cur));
            if (node instanceof SpinningGear g) {
                try { g.resetAnimation(); } catch (Throwable ignored) {}
            }
            for (Vector3i adj : neighborsOf(cur)) {
                String k = VectorCompat.posKey(adj);
                if (NODES.containsKey(k) && visited.add(k)) queue.add(adj);
            }
        }
    }

    // ── Adjacency helpers ────────────────────────────────────────────────────

    /**
     * Returns the four horizontal face-adjacent positions (±X, ±Z) of {@code pos}.
     *
     * <p>Vertical neighbours (±Y) are intentionally excluded: stacked gears do
     * not share teeth and therefore cannot transfer mechanical power.
     */
    private static Vector3i[] horizontalAdjacent(Vector3i pos) {
        return new Vector3i[]{
            new Vector3i(pos.x + 1, pos.y, pos.z),
            new Vector3i(pos.x - 1, pos.y, pos.z),
            new Vector3i(pos.x,     pos.y, pos.z + 1),
            new Vector3i(pos.x,     pos.y, pos.z - 1),
        };
    }

    /**
     * Returns the two face-adjacent positions along the given shaft axis.
     * Shafts do not bend, so only the two positions along the axis direction
     * are returned.
     */
    private static Vector3i[] axisAdjacent(Vector3i pos, ShaftAxis axis) {
        return switch (axis) {
            case X -> new Vector3i[]{
                new Vector3i(pos.x + 1, pos.y, pos.z),
                new Vector3i(pos.x - 1, pos.y, pos.z)
            };
            case Y -> new Vector3i[]{
                new Vector3i(pos.x, pos.y + 1, pos.z),
                new Vector3i(pos.x, pos.y - 1, pos.z)
            };
            case Z -> new Vector3i[]{
                new Vector3i(pos.x, pos.y, pos.z + 1),
                new Vector3i(pos.x, pos.y, pos.z - 1)
            };
        };
    }

    /**
     * Returns the neighbours to visit from {@code pos}, taking shaft/gear axis into account.
     * If the node at {@code pos} is a {@link ShaftConnectable}, only the two
     * in-axis neighbours are returned.  If it is a {@link GearConnectable},
     * the four face-adjacent positions in the plane perpendicular to the gear's
     * spin axis are returned.  Otherwise, the standard four horizontal
     * neighbours are returned.
     */
    private static Vector3i[] neighborsOf(Vector3i pos) {
        Object node = NODES.get(VectorCompat.posKey(pos));
        if (node instanceof ShaftConnectable sc) return axisAdjacent(pos, sc.getShaftAxis());
        if (node instanceof GearConnectable gc) {
            // A gear can connect to:
            //  1. The four perpendicular (tooth-plane) neighbors for meshing.
            //  2. The two axial (spin-axis) neighbors for inline gears and for
            //     shafts running along the same axis as the gear.
            ShaftAxis axis = gc.getGearAxis();
            Vector3i[] perp = perpendicular(pos, axis);
            Vector3i[] axial = axisAdjacent(pos, axis);
            Vector3i[] all = new Vector3i[perp.length + axial.length];
            System.arraycopy(perp,  0, all, 0,           perp.length);
            System.arraycopy(axial, 0, all, perp.length, axial.length);
            return all;
        }
        return horizontalAdjacent(pos);
    }

    /**
     * Returns the four face-adjacent positions in the plane perpendicular to
     * the given axis (the meshing plane of a gear with that spin axis).
     */
    private static Vector3i[] perpendicular(Vector3i pos, ShaftAxis axis) {
        return switch (axis) {
            case Y -> new Vector3i[]{ // flat gear: teeth in XZ plane
                new Vector3i(pos.x + 1, pos.y, pos.z),
                new Vector3i(pos.x - 1, pos.y, pos.z),
                new Vector3i(pos.x, pos.y, pos.z + 1),
                new Vector3i(pos.x, pos.y, pos.z - 1)
            };
            case X -> new Vector3i[]{ // vertical X-spine gear: teeth in YZ plane
                new Vector3i(pos.x, pos.y + 1, pos.z),
                new Vector3i(pos.x, pos.y - 1, pos.z),
                new Vector3i(pos.x, pos.y, pos.z + 1),
                new Vector3i(pos.x, pos.y, pos.z - 1)
            };
            case Z -> new Vector3i[]{ // vertical Z-spine gear: teeth in XY plane
                new Vector3i(pos.x + 1, pos.y, pos.z),
                new Vector3i(pos.x - 1, pos.y, pos.z),
                new Vector3i(pos.x, pos.y + 1, pos.z),
                new Vector3i(pos.x, pos.y - 1, pos.z)
            };
        };
    }

    /**
     * Returns true when a step from {@code from} to {@code adj} is valid for a
     * {@link ShaftConnectable} at {@code adj}.  The step must be exactly along
     * the shaft's axis; otherwise the shaft cannot accept drive from that direction.
     */
    private static boolean isStepAlignedWithShaft(Vector3i from, Vector3i adj, ShaftAxis axis) {
        int dx = adj.x - from.x, dy = adj.y - from.y, dz = adj.z - from.z;
        return switch (axis) {
            case X -> dy == 0 && dz == 0;
            case Y -> dx == 0 && dz == 0;
            case Z -> dx == 0 && dy == 0;
        };
    }

    /**
     * Returns {@code true} when stepping from {@code from} to {@code adj}
     * is a valid shaft→gear (or gear→shaft) transition.  A shaft may only
     * drive a gear whose spin axis matches the shaft's axis; otherwise the
     * shaft runs perpendicular to the gear teeth and cannot impart rotation.
     */
    private static boolean isShaftGearAxisCompatible(Vector3i from, Vector3i adj) {
        Object nodeCur = NODES.get(VectorCompat.posKey(from));
        Object nodeAdj = NODES.get(VectorCompat.posKey(adj));
        ShaftConnectable shaft = null;
        GearConnectable  gear  = null;
        if (nodeCur instanceof ShaftConnectable sc && nodeAdj instanceof GearConnectable gc) {
            shaft = sc; gear = gc;
        } else if (nodeCur instanceof GearConnectable gc && nodeAdj instanceof ShaftConnectable sc) {
            gear = gc; shaft = sc;
        } else {
            return true; // not a shaft-gear pair — no axis constraint
        }
        // The shaft must run along the gear's spin axis.
        return shaft.getShaftAxis() == gear.getGearAxis();
    }

    /**
     * Returns {@code true} when two gears at {@code a} and {@code b} are
     * "inline" — adjacent along their shared spin axis.  Inline gears
     * co-rotate (same direction) and propagate spin like a shaft segment.
     */
    private static boolean isInlineGears(Vector3i a, Vector3i b) {
        Object nodeA = NODES.get(VectorCompat.posKey(a));
        Object nodeB = NODES.get(VectorCompat.posKey(b));
        if (!(nodeA instanceof GearConnectable gcA)) return false;
        if (!(nodeB instanceof GearConnectable gcB)) return false;
        if (gcA.getGearAxis() != gcB.getGearAxis()) return false;
        int dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        return switch (gcA.getGearAxis()) {
            case X -> dy == 0 && dz == 0;
            case Y -> dx == 0 && dz == 0;
            case Z -> dx == 0 && dy == 0;
        };
    }

    /**
     * Returns {@code true} when {@code a} and {@code b} would counter-rotate
     * and their teeth would mesh.  Parity is computed in the meshing plane of
     * the gear at {@code a}; if both nodes are {@link GearConnectable} with
     * mismatched axes they cannot mesh.
     */
    public static boolean canMesh(Vector3i a, Vector3i b) {
        Object nodeA = NODES.get(VectorCompat.posKey(a));
        Object nodeB = NODES.get(VectorCompat.posKey(b));

        // If either node is a shaft (not a gear), it drives the adjacent gear
        // directly along its axis — no tooth-parity check needed.
        boolean aIsGear = nodeA instanceof GearConnectable;
        boolean bIsGear = nodeB instanceof GearConnectable;
        if (!aIsGear || !bIsGear) return true;

        ShaftAxis axisA = ((GearConnectable) nodeA).getGearAxis();
        ShaftAxis axisB = ((GearConnectable) nodeB).getGearAxis();
        // Gears on different axes cannot mesh.
        if (axisA != axisB) return false;
        // Inline gears (adjacent along spin axis) co-rotate — not a meshing relationship.
        if (isInlineGears(a, b)) return true;
        return switch (axisA) {
            case Y -> ((a.x + a.z) & 1) != ((b.x + b.z) & 1);
            case X -> ((a.y + a.z) & 1) != ((b.y + b.z) & 1);
            case Z -> ((a.x + a.y) & 1) != ((b.x + b.y) & 1);
        };
    }

    // ── Power propagation ─────────────────────────────────────────────────────

    /**
     * Called each tick by a gear/crank that is currently spinning.
     * Floods the network (BFS, depth-limited) from {@code from}, marking
     * every reachable {@link SpinningGear} as spinning with the given speed,
     * and pushing Joules into any adjacent {@link JouleReceiver}.
     *
     * <p>Propagation rules:
     * <ol>
     *   <li>Only horizontal neighbours (±X, ±Z) are considered — vertical gears
     *       do not mesh.</li>
     *   <li>A neighbour gear is only driven if its position parity is <em>opposite</em>
     *       to the current node's parity — same-parity gears counter-rotate and
     *       mesh; same-parity gears would clash and must not receive power.</li>
     *   <li>{@link JouleReceiver} machines (e.g. Mechanical Grinder) always
     *       receive power from any adjacent spinning node regardless of parity,
     *       because they consume power rather than transmitting rotation.</li>
     * </ol>
     *
     * @param from   world position of the provider node
     * @param speed  speed multiplier from the active source
     */
    public static void propagateFrom(Vector3i from, double speed) {
        // Advance global animation tick once per server tick regardless of how
        // many providers call propagateFrom in the same tick.
        long frame = System.nanoTime() / TICK_NS;
        if (frame != LAST_TICK_FRAME) {
            LAST_TICK_FRAME = frame;
            GLOBAL_TICK.incrementAndGet();
            TICK_DIR_CLAIMS.clear(); // new tick — reset all direction claims
        }

        String fromKey = VectorCompat.posKey(from);
        Object fromNode = NODES.get(fromKey);
        int startDir = (fromNode instanceof SpinningGear sg) ? sg.getSpinDirection() : 1;
        TICK_DIR_CLAIMS.put(fromKey, startDir);

        long generation = NETWORK_GENERATION.get();
        PropagationCache cache = PROP_CACHE.get(fromKey);

        if (cache != null && cache.generation == generation && cache.speed == speed) {
            // ── Fast path: topology unchanged — re-deliver using cached list ──────
            String[] keys = cache.keys;
            int[]    dirs = cache.directions;
            for (int i = 0; i < keys.length; i++) {
                String  key  = keys[i];
                int     dir  = dirs[i];
                Object  node = NODES.get(key);
                if (!(node instanceof SpinningGear g)) continue;
                if (node instanceof GearBlocker b && b.isGearBlocked()) continue;
                // Conflict detection still runs every tick.
                Integer prevClaim = TICK_DIR_CLAIMS.get(key);
                if (prevClaim != null) {
                    if (prevClaim == 0)       { g.stopSpin(); continue; }
                    if (prevClaim != dir)     { TICK_DIR_CLAIMS.put(key, 0); g.stopSpin(); continue; }
                } else {
                    TICK_DIR_CLAIMS.put(key, dir);
                }
                g.receiveSpinSignal(speed, dir);
            }
            return;
        }

        // ── Slow path: full BFS — build (or rebuild) cache ────────────────────
        java.util.ArrayDeque<Vector3i>    queue   = new java.util.ArrayDeque<>();
        java.util.Map<String, Double>     visited = new java.util.HashMap<>();
        java.util.Map<String, Integer>    dirMap  = new java.util.HashMap<>();
        // Claim the source node's direction so other sources see a conflict if they
        // try to reach this same source node via the gear chain with a different direction.
        queue.add(from);
        visited.put(fromKey, speed);
        dirMap.put(fromKey, startDir);
        int limit = 128; // raised: re-queuing on stronger signal can exceed 64

        // Collect deliveries to build cache.
        java.util.ArrayList<String> cacheKeys = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> cacheDirs = new java.util.ArrayList<>();

        while (!queue.isEmpty() && limit-- > 0) {
            Vector3i cur      = queue.poll();
            String   curKey   = VectorCompat.posKey(cur);
            double   curSpeed = visited.getOrDefault(curKey, speed);
            int      curDir   = dirMap.getOrDefault(curKey, 1);

            for (Vector3i adj : neighborsOf(cur)) {
                String key  = VectorCompat.posKey(adj);
                Object node = NODES.get(key);
                if (node == null) continue;

                // Shaft axis compatibility checks:
                // 1. Step to a shaft must align with the shaft's axis.
                if (node instanceof ShaftConnectable sc
                        && !isStepAlignedWithShaft(cur, adj, sc.getShaftAxis())) continue;
                // 2. Shaft↔gear transition: the gear's spin axis must match the shaft's
                //    axis. A shaft running X cannot drive a Z-gear — the shaft lies
                //    parallel to those teeth and cannot impart rotation.
                if (!isShaftGearAxisCompatible(cur, adj)) continue;

                // Allow re-visit only if we carry a strictly stronger signal.
                Double prev = visited.get(key);
                if (prev != null && prev >= curSpeed) continue;
                visited.put(key, curSpeed);

                if (node instanceof SpinningGear g) {
                    // A locked GearBlocker (e.g. Clutch) stops all propagation.
                    if (node instanceof GearBlocker b && b.isGearBlocked()) continue;

                    // Compute the direction this adjacent node should receive.
                    // Rules:
                    //   • Shaft / inline-gear / consumer: same direction (co-rotate)
                    //   • Meshing gears: opposite direction (counter-rotate)
                    //   • Shaft→gear: same (shaft is ShaftConnectable so curNode check)
                    int adjDir;
                    boolean addToQueue;
                    if (node instanceof JouleReceiver) {
                        adjDir = curDir; addToQueue = false;
                    } else if (node instanceof ShaftConnectable) {
                        adjDir = curDir; addToQueue = true;
                    } else if (isInlineGears(cur, adj)) {
                        adjDir = curDir; addToQueue = true;
                    } else {
                        // Standard meshing gears.
                        if (!canMesh(cur, adj)) continue;
                        // A shaft driving a gear co-rotates; gear↔gear meshing counter-rotates.
                        Object curNode = NODES.get(VectorCompat.posKey(cur));
                        adjDir = (curNode instanceof ShaftConnectable) ? curDir : -curDir;
                        addToQueue = true;
                    }

                    // Conflict detection: if another power source already claimed a
                    // DIFFERENT direction for this node this tick, both sources conflict
                    // → stop the node and do not propagate further through it.
                    Integer prevClaim = TICK_DIR_CLAIMS.get(key);
                    if (prevClaim != null) {
                        if (prevClaim == 0) {
                            // Already flagged as conflicted this tick.
                            g.stopSpin();
                            continue;
                        }
                        if (prevClaim != adjDir) {
                            // Newly detected conflict.
                            TICK_DIR_CLAIMS.put(key, 0);
                            g.stopSpin();
                            continue;
                        }
                        // Same direction — no conflict, fall through to deliver.
                    } else {
                        TICK_DIR_CLAIMS.put(key, adjDir);
                    }

                    dirMap.put(key, adjDir);
                    g.receiveSpinSignal(curSpeed, adjDir);
                    cacheKeys.add(key);
                    cacheDirs.add(adjDir);
                    if (addToQueue) queue.add(adj);
                }
                // J delivery is handled exclusively by pushFromProvider.
            }
        }

        // Store the BFS result for future ticks.
        PropagationCache newCache = new PropagationCache();
        newCache.generation = generation;
        newCache.speed      = speed;
        newCache.keys       = cacheKeys.toArray(new String[0]);
        newCache.directions = new int[cacheDirs.size()];
        for (int i = 0; i < cacheDirs.size(); i++) newCache.directions[i] = cacheDirs.get(i);
        PROP_CACHE.put(fromKey, newCache);
    }

    /**
     * Push Joules from a {@link JouleProvider} into all {@link JouleReceiver}
     * nodes reachable through the connected gear network (BFS, depth-limited).
     * Gears act as conduits — any receiver anywhere in the chain gets power,
     * not just those directly adjacent to the provider.
     *
     * @param maxAmount maximum Joules to push in this call (use {@link Double#MAX_VALUE} to push all)
     * @return total Joules transferred
     */
    public static double pushFromProvider(Vector3i from, JouleProvider provider, double maxAmount) {
        if (provider.getJoulesStored() <= 0 || maxAmount <= 0) return 0;
        double remaining = Math.min(maxAmount, provider.getJoulesStored());
        double total = 0;
        java.util.ArrayDeque<Vector3i> queue   = new java.util.ArrayDeque<>();
        java.util.Set<String>          visited = new java.util.HashSet<>();
        queue.add(from);
        visited.add(VectorCompat.posKey(from));
        int limit = 128;

        while (!queue.isEmpty() && limit-- > 0) {
            Vector3i cur = queue.poll();
            for (Vector3i adj : neighborsOf(cur)) {
                if (provider.getJoulesStored() <= 0) break;
                String key = VectorCompat.posKey(adj);
                if (visited.contains(key)) continue;
                Object node = getAt(adj);
                if (node == null) continue;

                // If the destination is a shaft, the step must align with its axis.
                if (node instanceof ShaftConnectable sc
                        && !isStepAlignedWithShaft(cur, adj, sc.getShaftAxis())) continue;

                visited.add(key);

                if (node instanceof JouleReceiver receiver) {
                    double room     = receiver.getJoulesCapacity() - receiver.getJoulesStored();
                    if (room <= 0) continue;
                    double toSend   = Math.min(room, remaining);
                    double accepted = receiver.receiveJoules(toSend, provider.getSpeed(), false);
                    if (accepted > 0) {
                        provider.extractJoules(accepted, false);
                        remaining -= accepted;
                        total += accepted;
                    }
                    if (remaining <= 0) break;
                    // receivers are endpoints — don't BFS through them
                } else if (node instanceof SpinningGear) {
                    // A locked GearBlocker stops J propagation too.
                    if (node instanceof GearBlocker b && b.isGearBlocked()) continue;
                    // traverse gears (including shafts) as conduits
                    queue.add(adj);
                }
            }
        }
        return total;
    }

    // ── Housekeeping ──────────────────────────────────────────────────────────

    private static void purgeDeadEntries() {
        // All entries are strong references (no WeakReference here) so nothing
        // to collect — this call is a no-op placeholder for future cleanup.
        OPS_SINCE_PURGE.set(0);
    }
}
