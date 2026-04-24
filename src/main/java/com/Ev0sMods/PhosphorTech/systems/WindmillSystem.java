package com.Ev0sMods.PhosphorTech.systems;

import com.Ev0sMods.PhosphorTech.blocks.WindmillState;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;

/**
 * ECS system for ticking Windmill blocks.
 * Generates mechanical energy (Joules) based on wind exposure.
 */
public class WindmillSystem {
    public void tick(float dt, int index, ArchetypeChunk<ChunkStore> archetypeChunk, Store<ChunkStore> store, CommandBuffer<ChunkStore> commandBuffer) {
        WindmillState state = archetypeChunk.getComponent(index, WindmillState.COMPONENT_TYPE);
        if (state == null) return;
        // Simple wind check: y > 100 = exposed
        if (state.getPosition().y > 100) {
            state.generateJoules();
        }
        // UI refresh if needed
        if (com.Ev0sMods.PhosphorTech.ui.WindmillUIPage.hasWatcher(state.getPosition())) {
            com.Ev0sMods.PhosphorTech.ui.WindmillUIPage.tickRefresh(state, store, state.getPosition());
        }
    }

    public void invalidate() {}
    public org.joml.Vector3i getPosition() { return new org.joml.Vector3i(0,0,0); }
}
