package com.hypixel.hytale.server.core.universe.world.chunk.state;

import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/**
 * Local stub – the engine removed this interface; we define it so
 * Component implementations can share the same tick signature.
 */
public interface TickableBlockState {
    void tick(float dt, int index, ArchetypeChunk<ChunkStore> archeChunk,
              Store<ChunkStore> store, CommandBuffer<ChunkStore> commandBuffer);

    @Nullable
    WorldChunk getChunk();

    Vector3i getPosition();

    void invalidate();
}
