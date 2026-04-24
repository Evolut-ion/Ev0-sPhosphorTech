package com.Ev0sMods.PhosphorTech.systems;

import com.Ev0sMods.PhosphorTech.blocks.SolarPanelState;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;

/**
 * ECS system for ticking Solar Panel blocks.
 * Generates Crystalline Flux (CF) based on sunlight exposure.
 */
public class SolarPanelSystem {
    public void tick(float dt, int index, ArchetypeChunk<ChunkStore> archetypeChunk, Store<ChunkStore> store, CommandBuffer<ChunkStore> commandBuffer) {
        SolarPanelState state = archetypeChunk.getComponent(index, SolarPanelState.COMPONENT_TYPE);
        if (state == null) return;
        // Simple sunlight check: y > 120 = exposed
        if (state.getPosition().y > 120) {
            state.generateCF();
        }
        // UI refresh if needed
        if (com.Ev0sMods.PhosphorTech.ui.SolarPanelUIPage.hasWatcher(state.getPosition())) {
            com.Ev0sMods.PhosphorTech.ui.SolarPanelUIPage.tickRefresh(state, store, state.getPosition());
        }
    }

    public void invalidate() {}
    public org.joml.Vector3i getPosition() { return new org.joml.Vector3i(0,0,0); }
}
