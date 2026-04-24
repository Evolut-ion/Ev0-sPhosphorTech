package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Set;

import javax.annotation.Nonnull;

import com.Ev0sMods.PhosphorTech.heat.HeatNetwork;
import com.Ev0sMods.PhosphorTech.mechanical.GearNetwork;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/** ECS ticking system for {@link MechanicalHeaterState}. */
public class MechanicalHeaterSystem extends EntityTickingSystem<ChunkStore> {

    @Nonnull private final ComponentType<ChunkStore, MechanicalHeaterState> componentType;

    @Nonnull
    private static final Set<Dependency<ChunkStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.AFTER,
                    com.hypixel.hytale.builtin.fluid.FluidSystems.Ticking.class),
            new SystemDependency<>(Order.BEFORE,
                    com.hypixel.hytale.builtin.blocktick.system.ChunkBlockTickSystem.Ticking.class)
    );

    public MechanicalHeaterSystem(ComponentType<ChunkStore, MechanicalHeaterState> componentType) {
        this.componentType = componentType;
    }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
                     @Nonnull Store<ChunkStore> store,
                     @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        MechanicalHeaterState state = archetypeChunk.getComponent(index, componentType);
        if (state != null) state.tick(dt, index, archetypeChunk, store, commandBuffer);
    }

    @Nonnull @Override public Query<ChunkStore> getQuery() { return componentType; }
    @Nonnull @Override public Set<Dependency<ChunkStore>> getDependencies() { return DEPENDENCIES; }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    public static class Cleanup extends HolderSystem<ChunkStore> {
        private final ComponentType<ChunkStore, MechanicalHeaterState> componentType;
        public Cleanup(ComponentType<ChunkStore, MechanicalHeaterState> componentType) {
            this.componentType = componentType;
        }
        @Override public void onEntityAdd(@Nonnull Holder<ChunkStore> h, @Nonnull AddReason r, @Nonnull Store<ChunkStore> s) {}
        @Override public void onEntityRemoved(@Nonnull Holder<ChunkStore> h, @Nonnull RemoveReason r, @Nonnull Store<ChunkStore> s) {
            MechanicalHeaterState state = h.getComponent(componentType);
            if (state == null) return;
            state.removed = true;
            state.invalidate();
            GearNetwork.unregisterExact(state.getPosition(), state);
            HeatNetwork.unregisterExact(state.getPosition(), state);
        }
        @Override public Query<ChunkStore> getQuery() { return componentType; }
    }
}
