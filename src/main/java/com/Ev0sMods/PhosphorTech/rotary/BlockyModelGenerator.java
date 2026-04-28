package com.Ev0sMods.PhosphorTech.rotary;

import com.Ev0sMods.PhosphorTech.rotary.BlockGroupCapture.CapturedBlock;

import java.util.List;

/**
 * Generates a .blockymodel JSON string from a captured block group.
 *
 * <p>Each block becomes a child node of a single invisible "root" node.
 * The root sits at the pivot block's position in model space. All child
 * positions are in absolute model-space coordinates (not relative to parent)
 * following the convention observed in existing .blockymodel files.
 *
 * <p>Coordinate scale: 1 Hytale block = 16 model units. The reference
 * y-origin for a block's visual centre is y=16 (matching CokeOven.blockymodel).
 *
 * <p><b>Texture limitation:</b> All nodes share the same texture file, which
 * is set at the block-definition level via CustomModelTexture. Per-block-type
 * textures require a compile-time atlas and a UV lookup table; that is a
 * future enhancement tracked separately.
 */
public final class BlockyModelGenerator {

    private BlockyModelGenerator() {}

    /** Model-space units per world block. */
    private static final int SCALE = 16;

    /**
     * @param blocks  the captured block list (pivot excluded)
     * @return        JSON string for a .blockymodel file
     */
    public static String generate(List<CapturedBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"nodes\": [\n");
        sb.append("    {\n");
        sb.append("      \"id\": \"root\",\n");
        sb.append("      \"name\": \"root\",\n");
        sb.append("      \"position\": {\"x\": 0, \"y\": 16, \"z\": 0},\n");
        sb.append("      \"orientation\": {\"x\": 0, \"y\": 0, \"z\": 0, \"w\": 1},\n");
        sb.append("      \"shape\": ").append(invisibleShape()).append(",\n");
        if (blocks.isEmpty()) {
            sb.append("      \"children\": []\n");
        } else {
            sb.append("      \"children\": [\n");
            for (int i = 0; i < blocks.size(); i++) {
                CapturedBlock b = blocks.get(i);
                sb.append(blockNode(b, i + 1));
                if (i < blocks.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("      ]\n");
        }
        sb.append("    }\n");
        sb.append("  ],\n");
        sb.append("  \"format\": \"prop\",\n");
        sb.append("  \"lod\": \"auto\"\n");
        sb.append("}");
        return sb.toString();
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private static String blockNode(CapturedBlock b, int id) {
        // Absolute position in model space: pivot is at (0, 16, 0);
        // a block offset (dx, dy, dz) is dx*16 / dy*16 / dz*16 from there.
        int px = b.dx() * SCALE;
        int py = 16 + b.dy() * SCALE;
        int pz = b.dz() * SCALE;
        return String.format(
            "        {\n" +
            "          \"id\": \"%d\",\n" +
            "          \"name\": \"block_%d_%d_%d\",\n" +
            "          \"position\": {\"x\": %d, \"y\": %d, \"z\": %d},\n" +
            "          \"orientation\": {\"x\": 0, \"y\": 0, \"z\": 0, \"w\": 1},\n" +
            "          \"shape\": %s\n" +
            "        }",
            id,
            b.dx(), b.dy(), b.dz(),
            px, py, pz,
            visibleCubeShape()
        );
    }

    /** An invisible 1px cube used as the rotation-pivot node. */
    private static String invisibleShape() {
        return "{\n" +
               "        \"type\": \"box\",\n" +
               "        \"offset\": {\"x\": 0, \"y\": 0, \"z\": 0},\n" +
               "        \"stretch\": {\"x\": 1, \"y\": 1, \"z\": 1},\n" +
               "        \"settings\": {\"isPiece\": false, \"size\": {\"x\": 1, \"y\": 1, \"z\": 1}, \"isStaticBox\": false},\n" +
               "        \"textureLayout\": " + defaultUV() + ",\n" +
               "        \"unwrapMode\": \"custom\",\n" +
               "        \"visible\": false,\n" +
               "        \"doubleSided\": false,\n" +
               "        \"shadingMode\": \"flat\"\n" +
               "      }";
    }

    /**
     * A 32×32×32 visible cube matching the CokeOven block scale.
     * UV offsets replicate the CokeOven layout (all faces from pixel 0,0).
     */
    private static String visibleCubeShape() {
        return "{\n" +
               "          \"type\": \"box\",\n" +
               "          \"offset\": {\"x\": 0, \"y\": 0, \"z\": 0},\n" +
               "          \"stretch\": {\"x\": 1, \"y\": 1, \"z\": 1},\n" +
               "          \"settings\": {\"isPiece\": false, \"size\": {\"x\": 32, \"y\": 32, \"z\": 32}, \"isStaticBox\": true},\n" +
               "          \"textureLayout\": " + defaultUV() + ",\n" +
               "          \"unwrapMode\": \"custom\",\n" +
               "          \"visible\": true,\n" +
               "          \"doubleSided\": false,\n" +
               "          \"shadingMode\": \"flat\"\n" +
               "        }";
    }

    /** Standard 6-face UV layout matching CokeOven (back/right/front/left in row 0, top/bottom in row 1). */
    private static String defaultUV() {
        return "{" +
               "\"back\":{\"offset\":{\"x\":0,\"y\":0},\"mirror\":{\"x\":false,\"y\":false},\"angle\":0}," +
               "\"right\":{\"offset\":{\"x\":32,\"y\":0},\"mirror\":{\"x\":false,\"y\":false},\"angle\":0}," +
               "\"front\":{\"offset\":{\"x\":64,\"y\":0},\"mirror\":{\"x\":false,\"y\":false},\"angle\":0}," +
               "\"left\":{\"offset\":{\"x\":96,\"y\":0},\"mirror\":{\"x\":false,\"y\":false},\"angle\":0}," +
               "\"top\":{\"offset\":{\"x\":0,\"y\":32},\"mirror\":{\"x\":false,\"y\":false},\"angle\":0}," +
               "\"bottom\":{\"offset\":{\"x\":32,\"y\":32},\"mirror\":{\"x\":false,\"y\":false},\"angle\":0}" +
               "}";
    }
}
