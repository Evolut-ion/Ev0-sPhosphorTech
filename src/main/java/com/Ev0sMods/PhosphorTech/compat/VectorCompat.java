package com.Ev0sMods.PhosphorTech.compat;

import org.joml.Vector3d;
import org.joml.Vector3i;

/**
 * Migration shim for vector types.
 *
 * <p>The current Hytale release uses {@link Vector3i} / {@link Vector3d}.
 * The next update will migrate these to JOML types (org.joml.Vector3i /
 * org.joml.Vector3d). <b>All code in PhosphorTech should create/operate on
 * vector values through this class</b> so that the migration is a single-file
 * change.
 *
 * <p>TODO(JOML-migration): Replace method bodies to return org.joml types and
 * update the import. Field-access style (v.x, v.y, v.z) is identical between
 * the Hytale shim and JOML, so call-sites outside this class require no changes.
 */
public final class VectorCompat {

    private VectorCompat() {}

    // ── Construction helpers ──────────────────────────────────────────────────

    /** TODO(JOML-migration): swap body to {@code new org.joml.Vector3i(x,y,z)} */
    public static Vector3i vec3i(int x, int y, int z) {
        return new Vector3i(x, y, z);
    }

    /** TODO(JOML-migration): swap body to {@code new org.joml.Vector3d(x,y,z)} */
    public static Vector3d vec3d(double x, double y, double z) {
        return new Vector3d(x, y, z);
    }

    // ── Adjacency ─────────────────────────────────────────────────────────────

    /**
     * Returns the six face-adjacent positions (±X, ±Y, ±Z) of the given block
     * position.
     *
     * TODO(JOML-migration): return type becomes org.joml.Vector3i[] — no other
     * call-site changes needed.
     */
    public static Vector3i[] adjacentPositions(Vector3i pos) {
        return new Vector3i[]{
            new Vector3i(pos.x + 1, pos.y,     pos.z    ),
            new Vector3i(pos.x - 1, pos.y,     pos.z    ),
            new Vector3i(pos.x,     pos.y + 1, pos.z    ),
            new Vector3i(pos.x,     pos.y - 1, pos.z    ),
            new Vector3i(pos.x,     pos.y,     pos.z + 1),
            new Vector3i(pos.x,     pos.y,     pos.z - 1),
        };
    }

    // ── Equality / hashing ────────────────────────────────────────────────────

    /**
     * Returns a canonical string key for a world position, safe for use as a
     * {@link java.util.HashMap} key regardless of whether the vector type
     * overrides {@code equals}/{@code hashCode} correctly.
     *
     * TODO(JOML-migration): this helper remains identical; no changes needed.
     */
    public static String posKey(Vector3i pos) {
        return pos.x + "," + pos.y + "," + pos.z;
    }

    /** True when {@code a} and {@code b} represent the same world block. */
    public static boolean eq(Vector3i a, Vector3i b) {
        return a != null && b != null && a.x == b.x && a.y == b.y && a.z == b.z;
    }

    /**
     * Parses a canonical string key (as produced by posKey) back into a Vector3i.
     */
    public static Vector3i parsePosKey(String key) {
        String[] parts = key.split(",");
        if (parts.length != 3) throw new IllegalArgumentException("Invalid key: " + key);
        return new Vector3i(
            Integer.parseInt(parts[0].trim()),
            Integer.parseInt(parts[1].trim()),
            Integer.parseInt(parts[2].trim())
        );
    }
}
