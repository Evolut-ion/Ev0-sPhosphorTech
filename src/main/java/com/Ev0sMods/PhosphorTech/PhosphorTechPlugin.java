package com.Ev0sMods.PhosphorTech;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import com.Ev0sMods.PhosphorTech.blocks.AlloySmelterState;
import com.Ev0sMods.PhosphorTech.blocks.AlloySmelterSystem;
import com.Ev0sMods.PhosphorTech.blocks.BellowsState;
import com.Ev0sMods.PhosphorTech.blocks.BellowsSystem;
import com.Ev0sMods.PhosphorTech.blocks.BrickFormState;
import com.Ev0sMods.PhosphorTech.blocks.BrickFormSystem;
import com.Ev0sMods.PhosphorTech.blocks.CentrifugeState;
import com.Ev0sMods.PhosphorTech.blocks.CentrifugeSystem;
import com.Ev0sMods.PhosphorTech.blocks.ClutchState;
import com.Ev0sMods.PhosphorTech.blocks.ClutchSystem;
import com.Ev0sMods.PhosphorTech.blocks.CokeOvenState;
import com.Ev0sMods.PhosphorTech.blocks.CokeOvenSystem;
import com.Ev0sMods.PhosphorTech.blocks.CompressorState;
import com.Ev0sMods.PhosphorTech.blocks.CompressorSystem;
import com.Ev0sMods.PhosphorTech.blocks.CrusherState;
import com.Ev0sMods.PhosphorTech.blocks.CrusherSystem;
import com.Ev0sMods.PhosphorTech.blocks.CrystalGeneratorState;
import com.Ev0sMods.PhosphorTech.blocks.CrystalGeneratorSystem;
import com.Ev0sMods.PhosphorTech.blocks.CrystallineCapacitorState;
import com.Ev0sMods.PhosphorTech.blocks.CrystallineCapacitorSystem;
import com.Ev0sMods.PhosphorTech.blocks.DynamoState;
import com.Ev0sMods.PhosphorTech.blocks.DynamoSystem;
import com.Ev0sMods.PhosphorTech.blocks.ExtractorState;
import com.Ev0sMods.PhosphorTech.blocks.ExtractorSystem;
import com.Ev0sMods.PhosphorTech.blocks.FluidGeneratorState;
import com.Ev0sMods.PhosphorTech.blocks.FluidGeneratorSystem;
import com.Ev0sMods.PhosphorTech.blocks.FluidPipeState;
import com.Ev0sMods.PhosphorTech.blocks.FluidPipeSystem;
import com.Ev0sMods.PhosphorTech.blocks.GenericFluidTankState;
import com.Ev0sMods.PhosphorTech.blocks.GenericFluidTankSystem;
import com.Ev0sMods.PhosphorTech.blocks.HandCrankState;
import com.Ev0sMods.PhosphorTech.blocks.HandCrankSystem;
import com.Ev0sMods.PhosphorTech.blocks.LargeGearState;
import com.Ev0sMods.PhosphorTech.blocks.LargeGearSystem;
import com.Ev0sMods.PhosphorTech.blocks.LatheState;
import com.Ev0sMods.PhosphorTech.blocks.LatheSystem;
import com.Ev0sMods.PhosphorTech.blocks.LeafSpringFlywheelCapacitorState;
import com.Ev0sMods.PhosphorTech.blocks.LeafSpringFlywheelCapacitorSystem;
import com.Ev0sMods.PhosphorTech.blocks.MechanicalGrinderState;
import com.Ev0sMods.PhosphorTech.blocks.MechanicalGrinderSystem;
import com.Ev0sMods.PhosphorTech.blocks.MechanicalHeaterState;
import com.Ev0sMods.PhosphorTech.blocks.MechanicalHeaterSystem;
import com.Ev0sMods.PhosphorTech.blocks.PoweredHeaterState;
import com.Ev0sMods.PhosphorTech.blocks.PoweredHeaterSystem;
import com.Ev0sMods.PhosphorTech.blocks.PressState;
import com.Ev0sMods.PhosphorTech.blocks.PressSystem;
import com.Ev0sMods.PhosphorTech.blocks.PumpState;
import com.Ev0sMods.PhosphorTech.blocks.PumpSystem;
import com.Ev0sMods.PhosphorTech.blocks.RodPullerState;
import com.Ev0sMods.PhosphorTech.blocks.RodPullerSystem;
import com.Ev0sMods.PhosphorTech.blocks.ShaftState;
import com.Ev0sMods.PhosphorTech.blocks.ShaftSystem;
import com.Ev0sMods.PhosphorTech.blocks.SieveState;
import com.Ev0sMods.PhosphorTech.blocks.SieveSystem;
import com.Ev0sMods.PhosphorTech.blocks.SmallGearState;
import com.Ev0sMods.PhosphorTech.blocks.SmallGearSystem;
import com.Ev0sMods.PhosphorTech.blocks.SolarBoilerState;
import com.Ev0sMods.PhosphorTech.blocks.SolarBoilerSystem;
import com.Ev0sMods.PhosphorTech.blocks.SolarPanelState;
import com.Ev0sMods.PhosphorTech.blocks.SolarPanelSystem;
import com.Ev0sMods.PhosphorTech.blocks.SteamGeneratorState;
import com.Ev0sMods.PhosphorTech.blocks.SteamGeneratorSystem;
import com.Ev0sMods.PhosphorTech.blocks.SteamReservoirState;
import com.Ev0sMods.PhosphorTech.blocks.SteamReservoirSystem;
import com.Ev0sMods.PhosphorTech.blocks.WaterTankState;
import com.Ev0sMods.PhosphorTech.blocks.WaterTankSystem;
import com.Ev0sMods.PhosphorTech.blocks.WaterwheelState;
import com.Ev0sMods.PhosphorTech.blocks.WaterwheelSystem;
import com.Ev0sMods.PhosphorTech.blocks.WindmillState;
import com.Ev0sMods.PhosphorTech.blocks.WindmillSystem;
import com.Ev0sMods.PhosphorTech.blocks.WireState;
import com.Ev0sMods.PhosphorTech.blocks.WireSystem;
import com.Ev0sMods.PhosphorTech.compat.EyeSpyCompat;
import com.Ev0sMods.PhosphorTech.interactions.CrankHandleInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenAlloySmelterInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenBrickFormInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenCentrifugeInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenCokeOvenInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenCompressorInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenCrusherInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenCrystalGeneratorInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenCrystallineCapacitorInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenDynamoInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenExtractorInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenFluidGeneratorInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenGenericFluidTankInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenLatheInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenLeafSpringFlywheelCapacitorInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenMechanicalGrinderInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenMechanicalHeaterInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenPoweredHeaterInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenPressInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenPumpInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenRodPullerInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenSieveInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenSolarBoilerInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenSolarPanelInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenSteamGeneratorInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenSteamReservoirInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenWaterTankInteraction;
import com.Ev0sMods.PhosphorTech.interactions.OpenWindmillInteraction;
import com.Ev0sMods.PhosphorTech.interactions.PumpBellowsInteraction;
import com.Ev0sMods.PhosphorTech.interactions.ToggleClutchInteraction;
import com.Ev0sMods.PhosphorTech.recipe.AlloySmelterRecipe;
import com.Ev0sMods.PhosphorTech.recipe.AlloySmelterRecipeRegistry;
import com.Ev0sMods.PhosphorTech.recipe.CentrifugeRecipe;
import com.Ev0sMods.PhosphorTech.recipe.CrusherRecipe;
import com.Ev0sMods.PhosphorTech.recipe.ExtractorRecipe;
import com.Ev0sMods.PhosphorTech.recipe.LatheRecipe;
import com.Ev0sMods.PhosphorTech.recipe.PressRecipe;
import com.Ev0sMods.PhosphorTech.recipe.ProcessingRecipeRegistry;
import com.Ev0sMods.PhosphorTech.recipe.SieveRecipe;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

/**
 * Entry point for the PhosphorTech plugin.
 *
 * <p>Registers all ECS components, systems, and interaction codecs used by
 * the Crystal Generator and wire blocks.
 */
public class PhosphorTechPlugin extends JavaPlugin {

    public PhosphorTechPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        System.out.println("[PhosphorTech] Plugin loaded!");
    }

    @Override
    protected void setup() {
        super.setup();
        System.out.println("[PhosphorTech] Plugin enabled!");

        var csr = this.getChunkStoreRegistry();

        // â”€â”€ Register ECS components â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        CrystalGeneratorState.COMPONENT_TYPE = csr.registerComponent(
                CrystalGeneratorState.class, "CrystalGenerator", CrystalGeneratorState.CODEC);

        WireState.COMPONENT_TYPE = csr.registerComponent(
                WireState.class, "WireState", WireState.CODEC);

        CrystallineCapacitorState.COMPONENT_TYPE = csr.registerComponent(
                CrystallineCapacitorState.class, "CrystallineCapacitor", CrystallineCapacitorState.CODEC);

        SteamReservoirState.COMPONENT_TYPE = csr.registerComponent(
                SteamReservoirState.class, "SteamReservoir", SteamReservoirState.CODEC);

        FluidPipeState.COMPONENT_TYPE = csr.registerComponent(
                FluidPipeState.class, "FluidPipe", FluidPipeState.CODEC);

        SteamGeneratorState.COMPONENT_TYPE = csr.registerComponent(
                SteamGeneratorState.class, "SteamGenerator", SteamGeneratorState.CODEC);

        CrusherState.COMPONENT_TYPE = csr.registerComponent(
                CrusherState.class, "Crusher", CrusherState.CODEC);

        ExtractorState.COMPONENT_TYPE = csr.registerComponent(
                ExtractorState.class, "Extractor", ExtractorState.CODEC);

        CentrifugeState.COMPONENT_TYPE = csr.registerComponent(
                CentrifugeState.class, "Centrifuge", CentrifugeState.CODEC);

        WaterTankState.COMPONENT_TYPE = csr.registerComponent(
                WaterTankState.class, "WaterTank", WaterTankState.CODEC);

        GenericFluidTankState.COMPONENT_TYPE = csr.registerComponent(
                GenericFluidTankState.class, "GenericFluidTank", GenericFluidTankState.CODEC);

        PumpState.COMPONENT_TYPE = csr.registerComponent(
                PumpState.class, "Pump", PumpState.CODEC);

        SmallGearState.COMPONENT_TYPE = csr.registerComponent(
                SmallGearState.class, "SmallGear", SmallGearState.CODEC);
        LargeGearState.COMPONENT_TYPE = csr.registerComponent(
                LargeGearState.class, "LargeGear", LargeGearState.CODEC);
        HandCrankState.COMPONENT_TYPE = csr.registerComponent(
                HandCrankState.class, "HandCrank", HandCrankState.CODEC);
        MechanicalGrinderState.COMPONENT_TYPE = csr.registerComponent(
                MechanicalGrinderState.class, "MechanicalGrinder", MechanicalGrinderState.CODEC);
        SieveState.COMPONENT_TYPE = csr.registerComponent(
                SieveState.class, "Sieve", SieveState.CODEC);
        LeafSpringFlywheelCapacitorState.COMPONENT_TYPE = csr.registerComponent(
                LeafSpringFlywheelCapacitorState.class, "LeafSpringFlywheelCapacitor",
                LeafSpringFlywheelCapacitorState.CODEC);
        ClutchState.COMPONENT_TYPE = csr.registerComponent(
                ClutchState.class, "Clutch", ClutchState.CODEC);

        AlloySmelterState.COMPONENT_TYPE = csr.registerComponent(
                AlloySmelterState.class, "AlloySmelter", AlloySmelterState.CODEC);

        WaterwheelState.COMPONENT_TYPE = csr.registerComponent(
                WaterwheelState.class, "Waterwheel", WaterwheelState.CODEC);

        ShaftState.COMPONENT_TYPE = csr.registerComponent(
                ShaftState.class, "Shaft", ShaftState.CODEC);

        PoweredHeaterState.COMPONENT_TYPE = csr.registerComponent(
                PoweredHeaterState.class, "PoweredHeater", PoweredHeaterState.CODEC);

        MechanicalHeaterState.COMPONENT_TYPE = csr.registerComponent(
                MechanicalHeaterState.class, "MechanicalHeater", MechanicalHeaterState.CODEC);

        BellowsState.COMPONENT_TYPE = csr.registerComponent(
                BellowsState.class, "Bellows", BellowsState.CODEC);

        DynamoState.COMPONENT_TYPE = csr.registerComponent(
                DynamoState.class, "Dynamo", DynamoState.CODEC);

        BrickFormState.COMPONENT_TYPE = csr.registerComponent(
                BrickFormState.class, "BrickForm", BrickFormState.CODEC);
        CokeOvenState.COMPONENT_TYPE = csr.registerComponent(
                CokeOvenState.class, "CokeOven", CokeOvenState.CODEC);
        SolarBoilerState.COMPONENT_TYPE = csr.registerComponent(
                SolarBoilerState.class, "SolarBoiler", SolarBoilerState.CODEC);
        SolarPanelState.COMPONENT_TYPE = csr.registerComponent(
                SolarPanelState.class, "SolarPanel", SolarPanelState.CODEC);
        WindmillState.COMPONENT_TYPE = csr.registerComponent(
                WindmillState.class, "Windmill", WindmillState.CODEC);
        FluidGeneratorState.COMPONENT_TYPE = csr.registerComponent(
                FluidGeneratorState.class, "FluidGenerator", FluidGeneratorState.CODEC);
        PressState.COMPONENT_TYPE = csr.registerComponent(
                PressState.class, "Press", PressState.CODEC);
        CompressorState.COMPONENT_TYPE = csr.registerComponent(
                CompressorState.class, "Compressor", CompressorState.CODEC);
        LatheState.COMPONENT_TYPE = csr.registerComponent(
                LatheState.class, "Lathe", LatheState.CODEC);
        RodPullerState.COMPONENT_TYPE = csr.registerComponent(
                RodPullerState.class, "RodPuller", RodPullerState.CODEC);

        // â”€â”€ Register CF-connectable block type IDs for cross-chunk connection visuals â”€â”€
        for (String id : new String[]{
                "CopperWire", "IronWire", "ThoriumWire", "CobaltWire", "AdamantiteWire",
                "CrystalGenerator", "CrystallineCapacitor", "Extractor", "Crusher", "Centrifuge", "Pump", "AlloySmelter", "PoweredHeater", "Dynamo", "SolarPanel", "FluidGenerator", "Compressor", "Lathe"}) {
            com.Ev0sMods.PhosphorTech.energy.CrystallineFluxNetwork.registerConnectableType(id);
        }

        // â”€â”€ Register gear-network connectable types â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        for (String id : new String[]{"SmallGear", "LargeGear", "HandCrank", "HandCrank_Spin", "MechanicalGrinder", "MechanicalHeater", "LeafSpringFlywheelCapacitor", "Clutch", "Clutch_Spin", "Clutch_Locked", "Waterwheel", "Waterwheel_Spin", "Shaft", "Dynamo", "Windmill", "RodPuller"}) {
            com.Ev0sMods.PhosphorTech.mechanical.GearNetwork.registerConnectableType(id);
        }

                // Register sieve as gear-connectable visual type too (same as grinder)
                com.Ev0sMods.PhosphorTech.mechanical.GearNetwork.registerConnectableType("Sieve");

        // â”€â”€ Register ECS systems â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        csr.registerSystem(new CrystalGeneratorSystem(CrystalGeneratorState.COMPONENT_TYPE));
        csr.registerSystem(new WireSystem(WireState.COMPONENT_TYPE));
        csr.registerSystem(new CrystallineCapacitorSystem(CrystallineCapacitorState.COMPONENT_TYPE));
        csr.registerSystem(new SteamReservoirSystem(SteamReservoirState.COMPONENT_TYPE));
        csr.registerSystem(new FluidPipeSystem(FluidPipeState.COMPONENT_TYPE));
        csr.registerSystem(new SteamGeneratorSystem(SteamGeneratorState.COMPONENT_TYPE));
        csr.registerSystem(new CrusherSystem(CrusherState.COMPONENT_TYPE));
        csr.registerSystem(new ExtractorSystem(ExtractorState.COMPONENT_TYPE));
        csr.registerSystem(new CentrifugeSystem(CentrifugeState.COMPONENT_TYPE));
        csr.registerSystem(new WaterTankSystem(WaterTankState.COMPONENT_TYPE));
        csr.registerSystem(new GenericFluidTankSystem(GenericFluidTankState.COMPONENT_TYPE));
        csr.registerSystem(new PumpSystem(PumpState.COMPONENT_TYPE));
        csr.registerSystem(new SmallGearSystem(SmallGearState.COMPONENT_TYPE));
        csr.registerSystem(new LargeGearSystem(LargeGearState.COMPONENT_TYPE));
        csr.registerSystem(new HandCrankSystem(HandCrankState.COMPONENT_TYPE));
        csr.registerSystem(new MechanicalGrinderSystem(MechanicalGrinderState.COMPONENT_TYPE));
        csr.registerSystem(new SieveSystem(SieveState.COMPONENT_TYPE));
        csr.registerSystem(new LeafSpringFlywheelCapacitorSystem(LeafSpringFlywheelCapacitorState.COMPONENT_TYPE));
        csr.registerSystem(new ClutchSystem(ClutchState.COMPONENT_TYPE));
        csr.registerSystem(new AlloySmelterSystem(AlloySmelterState.COMPONENT_TYPE));
        csr.registerSystem(new WaterwheelSystem(WaterwheelState.COMPONENT_TYPE));
        csr.registerSystem(new ShaftSystem(ShaftState.COMPONENT_TYPE));
        csr.registerSystem(new PoweredHeaterSystem(PoweredHeaterState.COMPONENT_TYPE));
        csr.registerSystem(new MechanicalHeaterSystem(MechanicalHeaterState.COMPONENT_TYPE));
        csr.registerSystem(new BellowsSystem(BellowsState.COMPONENT_TYPE));
        csr.registerSystem(new DynamoSystem(DynamoState.COMPONENT_TYPE));
        csr.registerSystem(new BrickFormSystem(BrickFormState.COMPONENT_TYPE));
        csr.registerSystem(new CokeOvenSystem(CokeOvenState.COMPONENT_TYPE));
        csr.registerSystem(new SolarBoilerSystem(SolarBoilerState.COMPONENT_TYPE));
        csr.registerSystem(new SolarPanelSystem(SolarPanelState.COMPONENT_TYPE));
        csr.registerSystem(new WindmillSystem(WindmillState.COMPONENT_TYPE));
        csr.registerSystem(new FluidGeneratorSystem(FluidGeneratorState.COMPONENT_TYPE));
        csr.registerSystem(new PressSystem(PressState.COMPONENT_TYPE));
        csr.registerSystem(new CompressorSystem(CompressorState.COMPONENT_TYPE));
        csr.registerSystem(new LatheSystem(LatheState.COMPONENT_TYPE));
        csr.registerSystem(new RodPullerSystem(RodPullerState.COMPONENT_TYPE));

        // â”€â”€ Network cleanup systems (unique HolderSystem subclass per type) â”€â”€
        csr.registerSystem(new WireSystem.Cleanup(WireState.COMPONENT_TYPE));
        csr.registerSystem(new FluidPipeSystem.Cleanup(FluidPipeState.COMPONENT_TYPE));
        csr.registerSystem(new CrystalGeneratorSystem.Cleanup(CrystalGeneratorState.COMPONENT_TYPE));
        csr.registerSystem(new CrystallineCapacitorSystem.Cleanup(CrystallineCapacitorState.COMPONENT_TYPE));
        csr.registerSystem(new SteamReservoirSystem.Cleanup(SteamReservoirState.COMPONENT_TYPE));
        csr.registerSystem(new SteamGeneratorSystem.Cleanup(SteamGeneratorState.COMPONENT_TYPE));
        csr.registerSystem(new CrusherSystem.Cleanup(CrusherState.COMPONENT_TYPE));
        csr.registerSystem(new ExtractorSystem.Cleanup(ExtractorState.COMPONENT_TYPE));
        csr.registerSystem(new CentrifugeSystem.Cleanup(CentrifugeState.COMPONENT_TYPE));
        csr.registerSystem(new WaterTankSystem.Cleanup(WaterTankState.COMPONENT_TYPE));
        csr.registerSystem(new GenericFluidTankSystem.Cleanup(GenericFluidTankState.COMPONENT_TYPE));
        csr.registerSystem(new PumpSystem.Cleanup(PumpState.COMPONENT_TYPE));
        csr.registerSystem(new SmallGearSystem.Cleanup(SmallGearState.COMPONENT_TYPE));
        csr.registerSystem(new LargeGearSystem.Cleanup(LargeGearState.COMPONENT_TYPE));
        csr.registerSystem(new HandCrankSystem.Cleanup(HandCrankState.COMPONENT_TYPE));
        csr.registerSystem(new MechanicalGrinderSystem.Cleanup(MechanicalGrinderState.COMPONENT_TYPE));
        csr.registerSystem(new SieveSystem.Cleanup(SieveState.COMPONENT_TYPE));
        csr.registerSystem(new LeafSpringFlywheelCapacitorSystem.Cleanup(LeafSpringFlywheelCapacitorState.COMPONENT_TYPE));
        csr.registerSystem(new ClutchSystem.Cleanup(ClutchState.COMPONENT_TYPE));
        csr.registerSystem(new AlloySmelterSystem.Cleanup(AlloySmelterState.COMPONENT_TYPE));
        csr.registerSystem(new WaterwheelSystem.Cleanup(WaterwheelState.COMPONENT_TYPE));
        csr.registerSystem(new ShaftSystem.Cleanup(ShaftState.COMPONENT_TYPE));
        csr.registerSystem(new PoweredHeaterSystem.Cleanup(PoweredHeaterState.COMPONENT_TYPE));
        csr.registerSystem(new MechanicalHeaterSystem.Cleanup(MechanicalHeaterState.COMPONENT_TYPE));
        csr.registerSystem(new BellowsSystem.Cleanup(BellowsState.COMPONENT_TYPE));
        csr.registerSystem(new DynamoSystem.Cleanup(DynamoState.COMPONENT_TYPE));
        csr.registerSystem(new BrickFormSystem.Cleanup(BrickFormState.COMPONENT_TYPE));
        csr.registerSystem(new CokeOvenSystem.Cleanup(CokeOvenState.COMPONENT_TYPE));
        csr.registerSystem(new SolarBoilerSystem.Cleanup(SolarBoilerState.COMPONENT_TYPE));
        csr.registerSystem(new SolarPanelSystem.Cleanup(SolarPanelState.COMPONENT_TYPE));
        csr.registerSystem(new WindmillSystem.Cleanup(WindmillState.COMPONENT_TYPE));
        csr.registerSystem(new FluidGeneratorSystem.Cleanup(FluidGeneratorState.COMPONENT_TYPE));
        csr.registerSystem(new PressSystem.Cleanup(PressState.COMPONENT_TYPE));
        csr.registerSystem(new CompressorSystem.Cleanup(CompressorState.COMPONENT_TYPE));
        csr.registerSystem(new LatheSystem.Cleanup(LatheState.COMPONENT_TYPE));
        csr.registerSystem(new RodPullerSystem.Cleanup(RodPullerState.COMPONENT_TYPE));

        // â”€â”€ Register interaction codecs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenCrystalGenerator",
                OpenCrystalGeneratorInteraction.class,
                OpenCrystalGeneratorInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenCrystallineCapacitor",
                OpenCrystallineCapacitorInteraction.class,
                OpenCrystallineCapacitorInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenSteamReservoir",
                OpenSteamReservoirInteraction.class,
                OpenSteamReservoirInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenSteamGenerator",
                OpenSteamGeneratorInteraction.class,
                OpenSteamGeneratorInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenSolarBoiler",
                OpenSolarBoilerInteraction.class,
                OpenSolarBoilerInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenSolarPanel",
                OpenSolarPanelInteraction.class,
                OpenSolarPanelInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenWindmill",
                OpenWindmillInteraction.class,
                OpenWindmillInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenFluidGenerator",
                OpenFluidGeneratorInteraction.class,
                OpenFluidGeneratorInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenCrusher",
                OpenCrusherInteraction.class,
                OpenCrusherInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenExtractor",
                OpenExtractorInteraction.class,
                OpenExtractorInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenCentrifuge",
                OpenCentrifugeInteraction.class,
                OpenCentrifugeInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenWaterTank",
                OpenWaterTankInteraction.class,
                OpenWaterTankInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenGenericFluidTank",
                OpenGenericFluidTankInteraction.class,
                OpenGenericFluidTankInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenPump",
                OpenPumpInteraction.class,
                OpenPumpInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "CrankHandle",
                CrankHandleInteraction.class,
                CrankHandleInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenMechanicalGrinder",
                OpenMechanicalGrinderInteraction.class,
                OpenMechanicalGrinderInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenSieve",
                OpenSieveInteraction.class,
                OpenSieveInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenLeafSpringFlywheelCapacitor",
                OpenLeafSpringFlywheelCapacitorInteraction.class,
                OpenLeafSpringFlywheelCapacitorInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "ToggleClutch",
                ToggleClutchInteraction.class,
                ToggleClutchInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "PumpBellows",
                PumpBellowsInteraction.class,
                PumpBellowsInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenAlloySmelter",
                OpenAlloySmelterInteraction.class,
                OpenAlloySmelterInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenDynamo",
                OpenDynamoInteraction.class,
                OpenDynamoInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenBrickForm",
                OpenBrickFormInteraction.class,
                OpenBrickFormInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenPress",
                OpenPressInteraction.class,
                OpenPressInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenCompressor",
                OpenCompressorInteraction.class,
                OpenCompressorInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenLathe",
                OpenLatheInteraction.class,
                OpenLatheInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenRodPuller",
                OpenRodPullerInteraction.class,
                OpenRodPullerInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenCokeOven",
                OpenCokeOvenInteraction.class,
                OpenCokeOvenInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenPoweredHeater",
                OpenPoweredHeaterInteraction.class,
                OpenPoweredHeaterInteraction.CODEC);

        this.getCodecRegistry(Interaction.CODEC).register(
                "OpenMechanicalHeater",
                OpenMechanicalHeaterInteraction.class,
                OpenMechanicalHeaterInteraction.CODEC);

        System.out.println("[PhosphorTech] Registered: CrystalGenerator, SteamGenerator, WireState (5 tiers), CrystallineCapacitor, SteamReservoir, WaterTank, FluidPipe (4 types), Crusher, Extractor, Centrifuge, SmallGear, LargeGear, HandCrank, MechanicalGrinder, AlloySmelter, Waterwheel, Shaft");

        // ── Load recipes from classpath ──────────────────────────────────────────
        ProcessingRecipeRegistry.CRUSHER.loadFromClasspath(PhosphorTechPlugin.class,
                "Ingredient_Phosphorus_Dust.json", "Ingredient_Sulfur_Dust.json", "Dust_Gold.json",
                "Pattern_MetallurgyOni.json", "Pattern_MetallurgyInfusions.json", "Pattern_MetallurgyOre.json",
                "Pattern_RockToRubble.json", "Pattern_RubbleToDust.json",
                "NoCube_Ingredient_Flour.json", "NoCube_Ingredient_Corn_Meal.json", "NoCube_Ingredient_Rice_Powder.json");
        ProcessingRecipeRegistry.EXTRACTOR.loadFromClasspath(PhosphorTechPlugin.class,
                "Ingredient_Rubber.json", "Ingredient_Sulfur_Dust.json");
        ProcessingRecipeRegistry.CENTRIFUGE.loadFromClasspath(PhosphorTechPlugin.class,
                "Ingredient_Centrifuged_Sap.json");
        ProcessingRecipeRegistry.SIEVE.loadFromClasspath(PhosphorTechPlugin.class,
                "Dust_Stone.json", "Dust_Aqua.json",
                "Dust_Basalt.json", "Dust_Dawnstone.json", "Dust_Lime.json", "Dust_Quartzite.json",
                "Dust_Sandstone_Red.json", "Dust_Sandstone_White.json", "Dust_Shale.json", "Dust_Slate.json",
                "Dust_Calcite.json", "Dust_Chalk.json", "Dust_Marble.json",
                "Dust_Sandstone.json",
                "NoCube_Ingredient_Sifted_Flour.json");
        AlloySmelterRecipeRegistry.INSTANCE.loadFromClasspath(PhosphorTechPlugin.class,
                "SteelAge_Ingredient_Bar_Steel.json");
        ProcessingRecipeRegistry.PRESS.loadFromClasspath(PhosphorTechPlugin.class,
                "Plate_Iron.json", "Plate_Steel.json", "Plate_Lead.json", "Plate_Potin.json",
                "Plate_FiredClay.json", "Plate_FiredCoke.json",
                "Pattern_OniIngots.json", "Pattern_BarIngots.json",
                "Pattern_Juice.json",
                "NoCube_Drink_Fruit_Juice_Apple.json", "NoCube_Drink_Fruit_Juice_Azure.json",
                "NoCube_Drink_Fruit_Juice_Mango.json", "NoCube_Drink_Fruit_Juice_Pinkberry.json",
                "NoCube_Drink_Juice_Fruit_Berries_Red.json");
        ProcessingRecipeRegistry.LATHE.loadFromClasspath(PhosphorTechPlugin.class,
                "Rod_Iron.json", "Rod_Steel.json", "Rod_Steel2.json",
                "Pattern_OniRods.json", "Pattern_BarRods.json");

        // ── Scan classpath for recipes from other mods ───────────────────────
        ProcessingRecipeRegistry.CRUSHER.scanAndLoad();
        ProcessingRecipeRegistry.EXTRACTOR.scanAndLoad();
        ProcessingRecipeRegistry.CENTRIFUGE.scanAndLoad();
        ProcessingRecipeRegistry.SIEVE.scanAndLoad();
        ProcessingRecipeRegistry.PRESS.scanAndLoad();
        ProcessingRecipeRegistry.LATHE.scanAndLoad();

        // ── Register recipe asset stores (exposes recipes to the Asset Editor) ─
        @SuppressWarnings({"unchecked", "rawtypes"})
        Runnable registerProcessingStores = () -> {
            getAssetRegistry().register(ProcessingRecipeRegistry.CRUSHER.buildAssetStore());
            getAssetRegistry().register(ProcessingRecipeRegistry.EXTRACTOR.buildAssetStore());
            getAssetRegistry().register(ProcessingRecipeRegistry.CENTRIFUGE.buildAssetStore());
            getAssetRegistry().register(ProcessingRecipeRegistry.SIEVE.buildAssetStore());
            getAssetRegistry().register(ProcessingRecipeRegistry.PRESS.buildAssetStore());
            getAssetRegistry().register(ProcessingRecipeRegistry.LATHE.buildAssetStore());
        };
        registerProcessingStores.run();

        AlloySmelterRecipe.ASSET_STORE = new HytaleAssetStore.Builder<>(
                String.class, AlloySmelterRecipe.class, new DefaultAssetMap<String, AlloySmelterRecipe>()
        )
        .setPath("Server/Recipes/AlloySmelter")
        .setExtension(".json")
        .setCodec(AlloySmelterRecipe.CODEC)
        .setKeyFunction(AlloySmelterRecipe::getId)
        .build();
        getAssetRegistry().register(AlloySmelterRecipe.ASSET_STORE);

        // ── Subscribe to LoadedAssetsEvents to populate lookup maps at runtime ─
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<LoadedAssetsEvent<String, CrusherRecipe, DefaultAssetMap<String, CrusherRecipe>>>
                crusherEventClass = (Class) LoadedAssetsEvent.class;
        getEventRegistry().register(crusherEventClass, CrusherRecipe.class, event ->
                event.getLoadedAssets().values().forEach(ProcessingRecipeRegistry.CRUSHER::register));

        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<LoadedAssetsEvent<String, ExtractorRecipe, DefaultAssetMap<String, ExtractorRecipe>>>
                extractorEventClass = (Class) LoadedAssetsEvent.class;
        getEventRegistry().register(extractorEventClass, ExtractorRecipe.class, event ->
                event.getLoadedAssets().values().forEach(ProcessingRecipeRegistry.EXTRACTOR::register));

        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<LoadedAssetsEvent<String, CentrifugeRecipe, DefaultAssetMap<String, CentrifugeRecipe>>>
                centrifugeEventClass = (Class) LoadedAssetsEvent.class;
        getEventRegistry().register(centrifugeEventClass, CentrifugeRecipe.class, event ->
                event.getLoadedAssets().values().forEach(ProcessingRecipeRegistry.CENTRIFUGE::register));

        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<LoadedAssetsEvent<String, SieveRecipe, DefaultAssetMap<String, SieveRecipe>>>
                sieveEventClass = (Class) LoadedAssetsEvent.class;
        getEventRegistry().register(sieveEventClass, SieveRecipe.class, event ->
                event.getLoadedAssets().values().forEach(ProcessingRecipeRegistry.SIEVE::register));

        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<LoadedAssetsEvent<String, PressRecipe, DefaultAssetMap<String, PressRecipe>>>
                pressEventClass = (Class) LoadedAssetsEvent.class;
        getEventRegistry().register(pressEventClass, PressRecipe.class, event ->
                event.getLoadedAssets().values().forEach(ProcessingRecipeRegistry.PRESS::register));

        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<LoadedAssetsEvent<String, LatheRecipe, DefaultAssetMap<String, LatheRecipe>>>
                latheEventClass = (Class) LoadedAssetsEvent.class;
        getEventRegistry().register(latheEventClass, LatheRecipe.class, event ->
                event.getLoadedAssets().values().forEach(ProcessingRecipeRegistry.LATHE::register));
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<LoadedAssetsEvent<String, AlloySmelterRecipe, DefaultAssetMap<String, AlloySmelterRecipe>>>
                alloySmelterEventClass = (Class) LoadedAssetsEvent.class;
        getEventRegistry().register(alloySmelterEventClass, AlloySmelterRecipe.class, event -> {
            event.getLoadedAssets().values().forEach(AlloySmelterRecipeRegistry.INSTANCE::register);
        });

        // Register item-container component types with Ev0Lib's hopper component-type cache
        // so hoppers can push items into these machines without requiring an Ev0Lib rebuild.
        injectIntoHopperCache(CrystalGeneratorState.COMPONENT_TYPE);
        injectIntoHopperCache(SteamGeneratorState.COMPONENT_TYPE);
        injectIntoHopperCache(BrickFormState.COMPONENT_TYPE);
        injectIntoHopperCache(CokeOvenState.COMPONENT_TYPE);
        injectIntoHopperCache(CrusherState.COMPONENT_TYPE);
        injectIntoHopperCache(ExtractorState.COMPONENT_TYPE);
        injectIntoHopperCache(CentrifugeState.COMPONENT_TYPE);
        injectIntoHopperCache(MechanicalGrinderState.COMPONENT_TYPE);
        injectIntoHopperCache(SieveState.COMPONENT_TYPE);
        injectIntoHopperCache(AlloySmelterState.COMPONENT_TYPE);
        injectIntoHopperCache(PressState.COMPONENT_TYPE);
        injectIntoHopperCache(CompressorState.COMPONENT_TYPE);
        injectIntoHopperCache(LatheState.COMPONENT_TYPE);
        injectIntoHopperCache(RodPullerState.COMPONENT_TYPE);

        // â”€â”€ Optional EyeSpy HUD integration â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        EyeSpyCompat.register();

                // â”€â”€ Runtime resource-type injector: mark Ingredient_Crystal items as ResourceType "Crystals"
                try {
                        var itemAssetClass = Class.forName("com.hypixel.hytale.server.core.asset.type.item.config.Item");
                        // Asset maps are available via the asset type's static getAssetMap() method
                        try {
                                var getMap = itemAssetClass.getMethod("getAssetMap");
                                Object assetMap = getMap.invoke(null);
                                // assetMap exposes getKeys() and getAsset(key) in engine; use reflection to iterate
                                var mapClass = assetMap.getClass();
                                // Try several known method names for key enumeration
                                java.lang.reflect.Method keysMethod = null;
                                for (String km : new String[]{"getKeys", "keySet", "keys", "getAllKeys", "getIds"}) {
                                    try { keysMethod = mapClass.getMethod(km); break; } catch (NoSuchMethodException ignored2) {}
                                }
                                if (keysMethod == null) throw new NoSuchMethodException("No key-enumeration method found on " + mapClass.getName());
                                java.lang.reflect.Method getAssetMethod = null;
                                for (String gm : new String[]{"getAsset", "get", "getById", "getItem"}) {
                                    try { getAssetMethod = mapClass.getMethod(gm, String.class); break; } catch (NoSuchMethodException ignored2) {}
                                }
                                if (getAssetMethod == null) throw new NoSuchMethodException("No asset-getter method found on " + mapClass.getName());
                                Object keys = keysMethod.invoke(assetMap);
                                if (keys instanceof java.util.Collection) {
                                        java.util.List<String> matched = new java.util.ArrayList<>();
                                        for (Object k : (java.util.Collection<?>) keys) {
                                                String key = String.valueOf(k);
                                                if (key.contains("Ingredient_Crystal")) {
                                                        matched.add(key);
                                                        try {
                                                                Object item = getAssetMethod.invoke(assetMap, key);
                                                                        if (item != null) {
                                                                                boolean applied = false;
                                                                                // Prefer setter if present
                                                                                try {
                                                                                        var m = item.getClass().getMethod("setResourceType", String.class);
                                                                                        m.invoke(item, "Crystals");
                                                                                        applied = true;
                                                                                        System.out.println("[PhosphorTech] setResourceType via setter for " + key);
                                                                                } catch (NoSuchMethodException ns) {
                                                                                        // fall through to field/synthetic attempts
                                                                                } catch (Throwable t) {
                                                                                        System.out.println("[PhosphorTech] Failed setter for " + key + ": " + t.getMessage());
                                                                                }

                                                                                if (!applied) {
                                                                                        // Use resilient reflection helper (stores synthetic if real field missing)
                                                                                        try {
                                                                                                com.Ev0sMods.PhosphorTech.compat.ReflectionCache.setField(item.getClass(), item, "resourceType", "Crystals");
                                                                                                applied = true;
                                                                                                System.out.println("[PhosphorTech] setResourceType via ReflectionCache for " + key);
                                                                                        } catch (Throwable t) {
                                                                                                System.out.println("[PhosphorTech] ReflectionCache failed for " + key + ": " + t.getMessage());
                                                                                        }
                                                                                }

                                                                                if (!applied) {
                                                                                        // Attempt alternate method names as last resort
                                                                                        String[] alt = new String[]{"setResourceTypeId", "setResourceTypeName", "setType"};
                                                                                        for (String name : alt) {
                                                                                                try {
                                                                                                        var m2 = item.getClass().getMethod(name, String.class);
                                                                                                        m2.invoke(item, "Crystals");
                                                                                                        applied = true;
                                                                                                        System.out.println("[PhosphorTech] setResourceType via " + name + " for " + key);
                                                                                                        break;
                                                                                                } catch (Throwable ignored) {}
                                                                                        }
                                                                                }

                                                                                if (!applied) {
                                                                                        System.out.println("[PhosphorTech] Could not set ResourceType for " + key + " (no known setter/field)");
                                                                                }
                                                                        } else {
                                                                                System.out.println("[PhosphorTech] Asset lookup returned null for " + key);
                                                                        }
                                                        } catch (Throwable t) {
                                                                System.out.println("[PhosphorTech] Error while handling " + key + ": " + t.getMessage());
                                                        }
                                                }
                                        }
                                        System.out.println("[PhosphorTech] ResourceType injector matched " + matched.size() + " keys: " + matched);
                                }
                                System.out.println("[PhosphorTech] ResourceType injector completed");
                        } catch (Throwable t) {
                                System.out.println("[PhosphorTech] ResourceType injector failed: " + t.getMessage());
                        }
                } catch (ClassNotFoundException ignored) {
                        // Item asset class not present â€” skip injector
                }
    }

    /**
     * Injects {@code componentType} into Ev0Lib's hopper ECS container-type caches
     * ({@code HopperProcessor} and {@code HopperComponent}) via reflection.
     *
     * <p>If the static list has not been initialised yet (null), this method
     * mirrors the standard candidate-class discovery so that the other known
     * containers (FertilizerState, ProcessingBenchState, â€¦) are still found.
     */
    @SuppressWarnings("unchecked")
    private static void injectIntoHopperCache(Object componentType) {
        if (componentType == null) return;

        String[] hopperClasses = {
            "org.Ev0Mods.plugin.api.block.state.HopperProcessor",
            "org.Ev0Mods.plugin.api.component.HopperComponent"
        };
        // Mirror Ev0Lib's own candidateClasses so we don't lose them when we init the list ourselves.
        String[] candidateClasses = {
            "com.Ev0sMods.Ev0sWoodCutter.blockstates.FertilizerState",
            "com.hypixel.hytale.builtin.crafting.state.ProcessingBenchState",
            "com.hypixel.hytale.builtin.crafting.blockstates.ProcessingBenchState",
            "com.hypixel.hytale.builtin.crafting.state.FurnaceState"
        };
        String[] fieldNames = {"COMPONENT_TYPE", "COMPONENT", "COMPONENTTYPE"};

        for (String hopperClass : hopperClasses) {
            try {
                Class<?> cls = Class.forName(hopperClass);
                Field field = cls.getDeclaredField("KNOWN_CONTAINER_COMP_TYPES");
                field.setAccessible(true);

                List<Object> list = (List<Object>) field.get(null);
                if (list == null) {
                    // The hopper hasn't ticked yet â€” build the list ourselves,
                    // mirroring what the hopper would have done lazily.
                    list = new ArrayList<>();
                    for (String candidate : candidateClasses) {
                        try {
                            Class<?> candidateCls = Class.forName(candidate);
                            for (String fn : fieldNames) {
                                try {
                                    java.lang.reflect.Field f = candidateCls.getField(fn);
                                    Object ct = f.get(null);
                                    if (ct != null) { list.add(ct); break; }
                                } catch (Throwable ignored) {}
                            }
                        } catch (Throwable ignored) {}
                    }
                    field.set(null, list);
                }

                if (!list.contains(componentType)) {
                    list.add(componentType);
                    System.out.println("[PhosphorTech] Injected CrystalGeneratorState into " + cls.getSimpleName() + " hopper cache");
                }
            } catch (Throwable t) {
                System.out.println("[PhosphorTech] Could not inject into hopper cache (" + hopperClass + "): " + t.getMessage());
            }
        }
    }

    public void onEnable() {}

    public void onDisable() {
        System.out.println("[PhosphorTech] Plugin disabled!");
    }
}



