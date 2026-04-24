package com.Ev0sMods.PhosphorTech.blocks;

import com.hypixel.hytale.component.spatial.SpatialStructure;
import java.util.List;

final class CompatSpatial {
    private CompatSpatial() {}

    static <T> void collectCylinder(SpatialStructure<T> structure,
                                    org.joml.Vector3d center, double radius, double halfHeight,
                                    List<T> out) {
        structure.collectCylinder(center, radius, halfHeight, out);
    }
}