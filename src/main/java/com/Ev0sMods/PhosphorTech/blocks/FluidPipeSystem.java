package com.Ev0sMods.PhosphorTech.blocks;

import com.Ev0sMods.PhosphorTech.fluid.FluidNetwork;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import java.util.Set;

/** Drives {@link FluidPipeState#tick} once per server tick. */
public class FluidPipeSystem extends EntityTickingSystem<ChunkStore> {

    @Nonnull
    private final ComponentType<ChunkStore, FluidPipeState> componentType;

    @Nonnull
    private static final Set<Dependency<ChunkStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.AFTER,
                    com.hypixel.hytale.builtin.fluid.FluidSystems.Ticking.class),
            new SystemDependency<>(Order.BEFORE,
                    com.hypixel.hytale.builtin.blocktick.system.ChunkBlockTickSystem.Ticking.class)
    );

    public FluidPipeSystem(ComponentType<ChunkStore, FluidPipeState> componentType) {
        this.componentType = componentType;
    }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
                     @Nonnull Store<ChunkStore> store,
                     @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        FluidPipeState pipe = archetypeChunk.getComponent(index, componentType);
        if (pipe != null) pipe.tick(dt, index, archetypeChunk, store, commandBuffer);
    }

    @Nonnull @Override public Query<ChunkStore>           getQuery()       { return componentType; }
    @Nonnull @Override public Set<Dependency<ChunkStore>> getDependencies() { return DEPENDENCIES;  }

    public static class Cleanup extends HolderSystem<ChunkStore> {
        private final ComponentType<ChunkStore, FluidPipeState> componentType;
        public Cleanup(ComponentType<ChunkStore, FluidPipeState> componentType) {
            this.componentType = componentType;
        }
        @Override public void onEntityAdd(@Nonnull Holder<ChunkStore> h, @Nonnull AddReason r, @Nonnull Store<ChunkStore> s) {}
        @Override public void onEntityRemoved(@Nonnull Holder<ChunkStore> h, @Nonnull RemoveReason r, @Nonnull Store<ChunkStore> s) {
            FluidPipeState state = h.getComponent(componentType);
            if (state != null) {
                try {
                    World world = s.getExternalData().getWorld();
                    if (world != null) BlockAnimator.applyBlockState(world, state.getPosition(), "Off");
                } catch (Throwable ignored) {}
                state.removed = true;
                FluidNetwork.unregisterExact(state.getPosition(), state);
            }
        }
        @Override public Query<ChunkStore> getQuery() { return componentType; }
    }
}
