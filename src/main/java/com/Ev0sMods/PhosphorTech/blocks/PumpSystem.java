package com.Ev0sMods.PhosphorTech.blocks;

import com.Ev0sMods.PhosphorTech.energy.CrystallineFluxNetwork;
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

/** Drives {@link PumpState#tick} once per server tick. */
public class PumpSystem extends EntityTickingSystem<ChunkStore> {

    @Nonnull
    private final ComponentType<ChunkStore, PumpState> componentType;

    @Nonnull
    private static final Set<Dependency<ChunkStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.AFTER,
                    com.hypixel.hytale.builtin.fluid.FluidSystems.Ticking.class),
            new SystemDependency<>(Order.BEFORE,
                    com.hypixel.hytale.builtin.blocktick.system.ChunkBlockTickSystem.Ticking.class)
    );

    public PumpSystem(ComponentType<ChunkStore, PumpState> componentType) {
        this.componentType = componentType;
    }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
                     @Nonnull Store<ChunkStore> store,
                     @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        PumpState pump = archetypeChunk.getComponent(index, componentType);
        if (pump != null) pump.tick(dt, index, archetypeChunk, store, commandBuffer);
    }

    @Nonnull @Override public Query<ChunkStore>           getQuery()        { return componentType; }
    @Nonnull @Override public Set<Dependency<ChunkStore>> getDependencies() { return DEPENDENCIES;  }

    public static class Cleanup extends HolderSystem<ChunkStore> {
        private final ComponentType<ChunkStore, PumpState> componentType;

        public Cleanup(ComponentType<ChunkStore, PumpState> componentType) {
            this.componentType = componentType;
        }

        @Override public void onEntityAdd(@Nonnull Holder<ChunkStore> h, @Nonnull AddReason r, @Nonnull Store<ChunkStore> s) {}

        @Override
        public void onEntityRemoved(@Nonnull Holder<ChunkStore> h, @Nonnull RemoveReason r, @Nonnull Store<ChunkStore> s) {
            PumpState pump = h.getComponent(componentType);
            if (pump != null) {
                try {
                    World world = s.getExternalData().getWorld();
                    if (world != null) BlockAnimator.applyBlockState(world, pump.getPosition(), "Off");
                } catch (Throwable ignored) {}
                pump.removed = true;
                CrystallineFluxNetwork.unregister(pump.getPosition());
                FluidNetwork.unregisterExact(pump.getPosition(), pump);
            }
        }

        @Override public Query<ChunkStore> getQuery() { return componentType; }
    }
}
