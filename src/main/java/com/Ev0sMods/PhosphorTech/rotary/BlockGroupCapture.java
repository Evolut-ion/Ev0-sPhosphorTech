package com.Ev0sMods.PhosphorTech.rotary;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkFlag;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of the 5×5×1 slab around a pivot point.
 * Positions are stored as integer offsets (dx, dy, dz) relative to the pivot.
 * The pivot block itself (0,0,0) is never captured — it stays in the world.
 * Block entity data is preserved via cloned Holder snapshots.
 */
@SuppressWarnings("removal")
public final class BlockGroupCapture {

    public record CapturedBlock(int dx, int dy, int dz, String blockTypeId, int rotation,
                                Holder<ChunkStore> entitySnapshot) {}

    private final List<CapturedBlock> blocks = new ArrayList<>();
    private final Vector3i pivot;

    private BlockGroupCapture(Vector3i pivot) {
        this.pivot = new Vector3i(pivot);
    }

    /**
     * Read a 5×5×1 slab at depth=1 in the locked face direction.
     * The slab is centred on the pivot and extends ±2 in the two perpendicular axes.
     */
    public static BlockGroupCapture capture(World world, Vector3i pivot, int lockedFace) {
        BlockGroupCapture cap = new BlockGroupCapture(pivot);
        // Face offsets: 0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z
        int[] faceOffset = { 1,0,0, -1,0,0, 0,1,0, 0,-1,0, 0,0,1, 0,0,-1 };
        int fx = faceOffset[lockedFace * 3];
        int fy = faceOffset[lockedFace * 3 + 1];
        int fz = faceOffset[lockedFace * 3 + 2];
        // Iterate the 5×5 perpendicular grid at depth=1 in face direction
        for (int u = -2; u <= 2; u++) {
            for (int v = -2; v <= 2; v++) {
                int dx, dy, dz;
                if (fx != 0) { dx = fx; dy = u; dz = v; }
                else if (fy != 0) { dx = u; dy = fy; dz = v; }
                else { dx = u; dy = v; dz = fz; }
                int wx = pivot.x + dx;
                int wy = pivot.y + dy;
                int wz = pivot.z + dz;
                try {
                    WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(wx, wz));
                    if (chunk == null) continue;
                    BlockType bt = chunk.getBlockType(wx, wy, wz);
                    if (bt == null) continue;
                    String id = bt.getId();
                    if (id == null || id.isEmpty()) continue;
                    int rot = chunk.getRotationIndex(wx, wy, wz);
                    Holder<ChunkStore> snapshot = cloneBlockEntity(world, chunk, wx, wy, wz);
                    cap.blocks.add(new CapturedBlock(dx, dy, dz, id, rot, snapshot));
                } catch (Throwable ignored) {}
            }
        }
        return cap;
    }

    /**
     * Remove all captured blocks from the world (replace with air).
     * Call this right after capture before starting the rotation animation.
     */
    @SuppressWarnings("null")
    public void removeFromWorld(World world) {
        for (CapturedBlock b : blocks) {
            int wx = pivot.x + b.dx();
            int wy = pivot.y + b.dy();
            int wz = pivot.z + b.dz();
            try {
                WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(wx, wz));
                if (chunk == null) continue;
                chunk.setBlock(wx, wy, wz, 0, null, 0,
                        com.hypixel.hytale.server.core.universe.world.SetBlockSettings.NO_UPDATE_STATE,
                        (byte) 0);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Place all captured blocks back, with positions rotated 90° around the given axis.
     * Block entity data is restored at the new rotated position.
     *
     * @param axis 0=X, 1=Y, 2=Z
     */
    public void restoreRotated(World world, int axis) {
        for (CapturedBlock b : blocks) {
            int[] rotated = rotate90(b.dx(), b.dy(), b.dz(), axis);
            int wx = pivot.x + rotated[0];
            int wy = pivot.y + rotated[1];
            int wz = pivot.z + rotated[2];
            int newRot = rotateIndex(b.rotation(), axis);
            try {
                WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(wx, wz));
                if (chunk == null) continue;
                int idx = BlockType.getAssetMap().getIndex(b.blockTypeId());
                if (idx == Integer.MIN_VALUE) continue;
                BlockType bt = (BlockType) BlockType.getAssetMap().getAsset(idx);
                if (bt == null) continue;
                chunk.setBlock(wx, wy, wz, idx, bt, newRot,
                        com.hypixel.hytale.server.core.universe.world.SetBlockSettings.NONE,
                        (byte) 0);
                if (b.entitySnapshot() != null) {
                    restoreBlockEntity(world, chunk, wx, wy, wz, b.entitySnapshot());
                }
            } catch (Throwable ignored) {}
        }
    }

    /** Return a new capture with every block position and rotation advanced by one 90° step around axis. */
    public BlockGroupCapture rotated(int axis) {
        BlockGroupCapture next = new BlockGroupCapture(pivot);
        for (CapturedBlock b : blocks) {
            int[] r = rotate90(b.dx(), b.dy(), b.dz(), axis);
            next.blocks.add(new CapturedBlock(r[0], r[1], r[2], b.blockTypeId(),
                    rotateIndex(b.rotation(), axis), b.entitySnapshot()));
        }
        return next;
    }

    public List<CapturedBlock> getBlocks() { return blocks; }
    public Vector3i getPivot()             { return pivot; }

    // ── Block entity snapshot / restore ──────────────────────────────────────

    /** Clone the block entity at (wx, wy, wz), returning null if none exists. */
    private static Holder<ChunkStore> cloneBlockEntity(World world, WorldChunk chunk, int wx, int wy, int wz) {
        try {
            BlockComponentChunk bcc = getOrEnsureBcc(world, chunk, wx, wz, false);
            if (bcc == null) return null;
            int blockIndex = ChunkUtil.indexBlockInColumn(wx, wy, wz);

            // Try live Ref first (ticking chunk)
            var ref = bcc.getEntityReference(blockIndex);
            if (ref != null && ref.isValid()) {
                try {
                    Holder<ChunkStore> copy = ref.getStore().copyEntity(ref);
                    return copy != null ? copy.clone() : null;
                } catch (Throwable ignored) {}
            }
            // Fall back to in-memory Holder
            Holder<ChunkStore> held = bcc.getEntityHolder(blockIndex);
            return held != null ? held.clone() : null;
        } catch (Throwable ignored) { return null; }
    }

    /** Restore a previously cloned block entity Holder to (wx, wy, wz), replacing any existing one. */
    private static void restoreBlockEntity(World world, WorldChunk chunk, int wx, int wy, int wz,
                                           Holder<ChunkStore> snapshot) {
        try {
            BlockComponentChunk bcc = getOrEnsureBcc(world, chunk, wx, wz, true);
            if (bcc == null) return;
            int blockIndex = ChunkUtil.indexBlockInColumn(wx, wy, wz);

            // Remove any existing entity at this block slot
            var oldRef = bcc.getEntityReference(blockIndex);
            if (oldRef != null) {
                if (oldRef.isValid()) {
                    try { oldRef.getStore().removeEntity(oldRef, RemoveReason.REMOVE); } catch (Throwable ignored) {}
                }
                bcc.unloadEntityReference(blockIndex, oldRef);
            } else {
                bcc.removeEntityHolder(blockIndex);
            }

            // Patch BlockStateInfo onto the cloned holder so the engine knows its position
            Holder<ChunkStore> toPlace = snapshot.clone();
            try {
                var bsiType = com.hypixel.hytale.server.core.modules.block.BlockModule.BlockStateInfo.getComponentType();
                var bsi = new com.hypixel.hytale.server.core.modules.block.BlockModule.BlockStateInfo(
                        blockIndex, chunk.getReference());
                toPlace.putComponent(bsiType, bsi);
            } catch (Throwable ignored) {}

            ChunkStore cs = world.getChunkStore();
            if (cs != null && chunk.is(ChunkFlag.TICKING)) {
                var store = cs.getStore();
                if (store != null) {
                    try { store.addEntity(toPlace, AddReason.SPAWN); } catch (Throwable ignored) {}
                    return;
                }
            }
            bcc.addEntityHolder(blockIndex, toPlace);
            chunk.markNeedsSaving();
        } catch (Throwable ignored) {}
    }

    /** Get or ensure a BlockComponentChunk for the column containing (wx, wz). */
    private static BlockComponentChunk getOrEnsureBcc(World world, WorldChunk chunk, int wx, int wz, boolean ensure) {
        try {
            BlockComponentChunk bcc = chunk.getBlockComponentChunk();
            if (bcc != null) return bcc;
            ChunkStore cs = world.getChunkStore();
            if (cs == null) return null;
            var store = cs.getStore();
            if (store == null) return null;
            var colRef = cs.getChunkReference(ChunkUtil.indexChunkFromBlock(wx, wz));
            if (colRef == null || !colRef.isValid()) return null;
            var componentType = BlockComponentChunk.getComponentType();
            bcc = (BlockComponentChunk) store.getComponent(colRef, componentType);
            if (bcc == null && ensure) {
                bcc = (BlockComponentChunk) store.ensureAndGetComponent(colRef, componentType);
            }
            return bcc;
        } catch (Throwable ignored) { return null; }
    }

    // ── Rotation math ────────────────────────────────────────────────────────

    /** CW 90° rotation of an integer position around the given axis (0=X, 1=Y, 2=Z). */
    public static int[] rotate90(int dx, int dy, int dz, int axis) {
        return switch (axis) {
            case 0  -> new int[]{ dx,  dz, -dy };  // around X
            case 1  -> new int[]{ dz,  dy, -dx };  // around Y
            default -> new int[]{-dy,  dx,  dz };  // around Z
        };
    }

    /**
     * Rotate a block's orientation index 90° around the given axis.
     * Hytale rotation indices: 0=South, 1=West, 2=North, 3=East, 4=Up, 5=Down.
     * Cycles derived from rotate90() by applying the same transformation to each unit face vector.
     */
    public static int rotateIndex(int rot, int axis) {
        if (rot < 0 || rot > 5) return rot;
        int[] cycle = switch (axis) {
            case 0  -> new int[]{4, 1, 5, 3, 2, 0}; // X: S→U, N→D, U→N, D→S; E/W fixed
            case 1  -> new int[]{3, 0, 1, 2, 4, 5}; // Y: S→E, W→S, N→W, E→N; U/D fixed
            default -> new int[]{0, 5, 2, 4, 1, 3}; // Z: W→D, E→U, U→W, D→E; S/N fixed
        };
        return cycle[rot];
    }

}
