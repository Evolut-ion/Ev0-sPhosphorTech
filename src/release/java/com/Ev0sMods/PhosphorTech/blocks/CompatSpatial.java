package com.Ev0sMods.PhosphorTech.blocks;

import com.hypixel.hytale.component.spatial.SpatialStructure;
import java.util.List;

final class CompatSpatial {
    private CompatSpatial() {}

    @SuppressWarnings("unchecked")
    static <T> void collectCylinder(SpatialStructure<T> structure,
                                    org.joml.Vector3d center, double radius, double halfHeight,
                                    List<T> out) {
        structure.collectCylinder(
                new com.hypixel.hytale.math.vector.Vector3d(center.x, center.y, center.z),
                radius, halfHeight, out);
    }
}