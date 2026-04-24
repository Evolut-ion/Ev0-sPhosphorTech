package com.Ev0sMods.PhosphorTech.blocks;

import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxNetwork;
import com.Ev0sMods.PhosphorTech.heat.HeatNetwork;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import java.util.Set;

/** ECS ticking system for {@link PoweredHeaterState}. */
public class PoweredHeaterSystem extends EntityTickingSystem<ChunkStore> {

    @Nonnull private final ComponentType<ChunkStore, PoweredHeaterState> componentType;

    @Nonnull
    private static final Set<Dependency<ChunkStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.AFTER,
                    com.hypixel.hytale.builtin.fluid.FluidSystems.Ticking.class),
            new SystemDependency<>(Order.BEFORE,
                    com.hypixel.hytale.builtin.blocktick.system.ChunkBlockTickSystem.Ticking.class)
    );

    public PoweredHeaterSystem(ComponentType<ChunkStore, PoweredHeaterState> componentType) {
        this.componentType = componentType;
    }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
                     @Nonnull Store<ChunkStore> store,
                     @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        PoweredHeaterState state = archetypeChunk.getComponent(index, componentType);
        if (state != null) state.tick(dt, index, archetypeChunk, store, commandBuffer);
    }

    @Override @Nonnull public Set<Dependency<ChunkStore>> getDependencies() { return DEPENDENCIES; }
    @Nonnull @Override public Query<ChunkStore> getQuery() { return componentType; }

    public static class Cleanup extends HolderSystem<ChunkStore> {
        private final ComponentType<ChunkStore, PoweredHeaterState> componentType;
        public Cleanup(ComponentType<ChunkStore, PoweredHeaterState> c) { this.componentType = c; }

        @Override
        public void onEntityAdd(@Nonnull Holder<ChunkStore> h, @Nonnull AddReason r,
                                @Nonnull Store<ChunkStore> s) {}

        @Override
        public void onEntityRemoved(@Nonnull Holder<ChunkStore> h, @Nonnull RemoveReason r,
                                    @Nonnull Store<ChunkStore> s) {
            PoweredHeaterState state = h.getComponent(componentType);
            if (state == null) return;
            state.removed = true;
            state.animator.reset();
            CrystallineFluxNetwork.unregisterExact(state.getPosition(), state);
            HeatNetwork.unregisterExact(state.getPosition(), state);
        }

        @Nonnull @Override public Query<ChunkStore> getQuery() { return componentType; }
    }
}
