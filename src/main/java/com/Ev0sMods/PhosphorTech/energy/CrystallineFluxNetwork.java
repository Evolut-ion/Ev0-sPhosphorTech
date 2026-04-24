package com.Ev0sMods.PhosphorTech.energy;

import com.Ev0sMods.PhosphorTech.compat.VectorCompat;
import org.joml.Vector3i;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Static registry and routing hub for the Crystalline Flux (CF) energy network.
 *
 * <p>Every CF-capable block registers itself here once its world position is
 * resolved (first tick). Wires and machines query this registry for adjacent
 * nodes when pushing / pulling energy, avoiding the need to traverse the ECS
 * chunk store from within a tick.
 *
 * <p><b>Lifecycle correctness:</b>
 * <ul>
 *   <li>Nodes call {@link #register} on their first tick after position is known.</li>
 *   <li>Nodes call {@link #unregister} from their {@code invalidate()} hook when
 *       destroyed.</li>
 *   <li>Entries are stored as {@link WeakReference}s so that if a chunk is
 *       unloaded without calling {@code invalidate()} the GC will collect the
 *       component and {@link #getAt} will auto-clean the stale entry.</li>
 *   <li>On every {@link #register}/{@link #unregister} call, adjacent nodes that
 *       implement {@link CrystallineFluxConnectable} are notified immediately so
 *       wire visuals update without waiting for their own next tick.</li>
 * </ul>
 *
 * <p>Thread safety: all mutations use {@link ConcurrentHashMap}.
 */
public final class CrystallineFluxNetwork {

    private CrystallineFluxNetwork() {}

    /** Weak-valued map: key = posKey, value = weak ref to component. */
    private static final ConcurrentHashMap<String, WeakReference<Object>> NODES =
            new ConcurrentHashMap<>();

    /** Approximate number of register() calls since last dead-ref sweep. */
    private static final AtomicInteger OPS_SINCE_PURGE = new AtomicInteger(0);
    private static final int PURGE_INTERVAL = 64;

    /**
     * Block-type IDs of every block that participates in the CF network.
     * Used to detect connections to blocks in unloaded/unticked chunks that
     * have not yet called {@link #register} (and therefore are absent from
     * {@link #NODES}).
     */
    private static final java.util.Set<String> CONNECTABLE_TYPE_IDS =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** Register a block-type ID as CF-connectable (called from plugin setup). */
    public static void registerConnectableType(String blockTypeId) {
        CONNECTABLE_TYPE_IDS.add(blockTypeId);
    }

    /** Returns {@code true} if {@code blockTypeId} is a known CF-connectable block type. */
    public static boolean isConnectableType(String blockTypeId) {
        return CONNECTABLE_TYPE_IDS.contains(blockTypeId);
    }

    // ── Registration ─────────────────────────────────────────────────────────

    /**
     * Register a CF-capable node at the given world position.
     * {@code node} must implement {@link CrystallineFluxProvider} and/or
     * {@link CrystallineFluxReceiver}.
     * <p>After registration, adjacent {@link CrystallineFluxConnectable} nodes
     * are notified so they can immediately update their visual state.
     */
    public static void register(Vector3i pos, Object node) {
        NODES.put(VectorCompat.posKey(pos), new WeakReference<>(node));
        if (OPS_SINCE_PURGE.incrementAndGet() >= PURGE_INTERVAL) purgeDeadEntries();
        notifyNeighbors(pos);
    }

    /**
     * Remove any node registration for the given world position.
     * <p>Adjacent {@link CrystallineFluxConnectable} nodes are notified
     * immediately after removal.
     */
    public static void unregister(Vector3i pos) {
        NODES.remove(VectorCompat.posKey(pos));
        notifyNeighbors(pos);
    }

    /**
     * Remove the node registration at {@code pos} only if the currently
     * registered node is exactly {@code expectedNode} (identity comparison).
     * <p>This prevents a cloned component from accidentally unregistering a
     * newer registration at the same position.
     */
    public static void unregisterExact(Vector3i pos, Object expectedNode) {
        String key = VectorCompat.posKey(pos);
        WeakReference<Object> ref = NODES.get(key);
        if (ref != null && ref.get() == expectedNode) {
            if (NODES.remove(key, ref)) {
                notifyNeighbors(pos);
            }
        }
    }

    /**
     * Returns the live, registered node at {@code pos}, or {@code null}.
     * <p>If the weak reference has been collected (chunk unloaded without
     * {@code invalidate()} being called), the stale entry is removed and
     * {@code null} is returned.
     */
    public static Object getAt(Vector3i pos) {
        String key = VectorCompat.posKey(pos);
        WeakReference<Object> ref = NODES.get(key);
        if (ref == null) return null;
        Object node = ref.get();
        if (node == null) { NODES.remove(key, ref); } // dead entry — clean up
        return node;
    }

    // ── Neighbor notification ─────────────────────────────────────────────────

    /**
     * Notifies all adjacent registered {@link CrystallineFluxConnectable} nodes
     * that the topology at {@code pos} changed.
     * <p>Called automatically by {@link #register} and {@link #unregister}.
     */
    public static void notifyNeighbors(Vector3i pos) {
        for (Vector3i adj : VectorCompat.adjacentPositions(pos)) {
            Object node = getAt(adj);
            if (node instanceof CrystallineFluxConnectable c) {
                try { c.onNeighborCFChanged(); } catch (Throwable ignored) {}
            }
        }
    }

    // ── Energy routing ────────────────────────────────────────────────────────

    /**
     * Push CF from {@code provider} at {@code from} into all adjacent
     * registered {@link CrystallineFluxReceiver} nodes.
     *
     * <p>The push is limited by the provider's remaining CF and each receiver's
     * remaining capacity. Called every tick from
     * {@code CrystalGeneratorState.tick()}.
     *
     * @return total CF actually transferred this call
     */
    public static long pushFromProvider(Vector3i from, CrystallineFluxProvider provider) {
        if (provider.getCFStored() <= 0) return 0L;
        long totalTransferred = 0L;
        for (Vector3i adj : VectorCompat.adjacentPositions(from)) {
            if (provider.getCFStored() <= 0) break;
            Object node = getAt(adj);
            if (!(node instanceof CrystallineFluxReceiver receiver)) continue;
            long room = receiver.getCFCapacity() - receiver.getCFStored();
            if (room <= 0) continue;
            long toSend = Math.min(room, provider.getCFStored());
            long accepted = receiver.receiveCF(toSend, false);
            if (accepted > 0) {
                provider.extractCF(accepted, false);
                totalTransferred += accepted;
            }
        }
        return totalTransferred;
    }

    /**
     * Wire-mediated transfer: a wire at {@code wirePos} with a rate cap of
     * {@code maxTransfer} CF/tick pulls from an adjacent
     * {@link CrystallineFluxProvider} and pushes to an adjacent
     * {@link CrystallineFluxReceiver}.
     *
     * @return CF transferred this call
     */
    public static long wireTransfer(Vector3i wirePos, long maxTransfer) {
        if (maxTransfer <= 0) return 0L;

        // Find best adjacent provider (highest CF available)
        CrystallineFluxProvider bestProvider = null;
        for (Vector3i adj : VectorCompat.adjacentPositions(wirePos)) {
            Object node = getAt(adj);
            if (node instanceof CrystallineFluxProvider p && p.getCFStored() > 0) {
                if (bestProvider == null || p.getCFStored() > bestProvider.getCFStored()) {
                    bestProvider = p;
                }
            }
        }
        if (bestProvider == null) return 0L;

        // Find best adjacent receiver (most room available), skipping the provider
        CrystallineFluxReceiver bestReceiver = null;
        for (Vector3i adj : VectorCompat.adjacentPositions(wirePos)) {
            Object node = getAt(adj);
            if (node instanceof CrystallineFluxReceiver r && node != bestProvider) {
                long room = r.getCFCapacity() - r.getCFStored();
                if (room > 0) {
                    if (bestReceiver == null || room > (bestReceiver.getCFCapacity() - bestReceiver.getCFStored())) {
                        bestReceiver = r;
                    }
                }
            }
        }
        if (bestReceiver == null) return 0L;

        // Transfer up to wire's rate-cap
        long canTransfer = Math.min(maxTransfer, bestProvider.getCFStored());
        long room = bestReceiver.getCFCapacity() - bestReceiver.getCFStored();
        long toTransfer = Math.min(canTransfer, room);
        if (toTransfer <= 0) return 0L;

        long extracted = bestProvider.extractCF(toTransfer, false);
        if (extracted <= 0) return 0L;
        bestReceiver.receiveCF(extracted, false);
        return extracted;
    }

    // ── Maintenance ───────────────────────────────────────────────────────────

    /** Sweeps the map and removes any entries whose weak reference has been collected. */
    public static void purgeDeadEntries() {
        OPS_SINCE_PURGE.set(0);
        NODES.entrySet().removeIf(e -> e.getValue().get() == null);
    }

    /** Returns the total number of registered nodes (for diagnostics). */
    public static int nodeCount() {
        purgeDeadEntries();
        return NODES.size();
    }
}

