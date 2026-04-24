package com.Ev0sMods.PhosphorTech.systems;

import com.Ev0sMods.PhosphorTech.blocks.FluidGeneratorState;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;

/**
 * ECS system for ticking Fluid Generator blocks.
 * Consumes fluid to generate Crystalline Flux (CF).
 */
public class FluidGeneratorSystem {
    public void tick(float dt, int index, ArchetypeChunk<ChunkStore> archetypeChunk, Store<ChunkStore> store, CommandBuffer<ChunkStore> commandBuffer) {
        FluidGeneratorState state = archetypeChunk.getComponent(index, FluidGeneratorState.COMPONENT_TYPE);
        if (state == null) return;
        state.tick(dt, index, archetypeChunk, store, commandBuffer);
        // UI refresh if needed
        if (com.Ev0sMods.PhosphorTech.ui.FluidGeneratorUIPage.hasWatcher(state.getPosition())) {
            com.Ev0sMods.PhosphorTech.ui.FluidGeneratorUIPage.tickRefresh(state, store, state.getPosition());
        }
    }
    public void invalidate() {}
    public org.joml.Vector3i getPosition() { return new org.joml.Vector3i(0,0,0); }
}
