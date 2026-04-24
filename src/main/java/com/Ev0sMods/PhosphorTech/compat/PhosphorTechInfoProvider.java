package com.Ev0sMods.PhosphorTech.compat;

import java.text.DecimalFormat;

import com.Ev0sMods.PhosphorTech.blocks.AlloySmelterState;
import com.Ev0sMods.PhosphorTech.blocks.CompressorState;
import com.Ev0sMods.PhosphorTech.blocks.LatheState;
import com.Ev0sMods.PhosphorTech.blocks.PressState;
import com.Ev0sMods.PhosphorTech.blocks.RodPullerState;
import com.Ev0sMods.PhosphorTech.blocks.BrickFormState;
import com.Ev0sMods.PhosphorTech.blocks.CentrifugeState;
import com.Ev0sMods.PhosphorTech.blocks.ClutchState;
import com.Ev0sMods.PhosphorTech.blocks.CokeOvenState;
import com.Ev0sMods.PhosphorTech.blocks.CrusherState;
import com.Ev0sMods.PhosphorTech.blocks.CrystalGeneratorState;
import com.Ev0sMods.PhosphorTech.blocks.CrystallineCapacitorState;
import com.Ev0sMods.PhosphorTech.blocks.ExtractorState;
import com.Ev0sMods.PhosphorTech.blocks.FluidGeneratorState;
import com.Ev0sMods.PhosphorTech.blocks.FluidPipeState;
import com.Ev0sMods.PhosphorTech.blocks.GenericFluidTankState;
import com.Ev0sMods.PhosphorTech.blocks.HandCrankState;
import com.Ev0sMods.PhosphorTech.blocks.LargeGearState;
import com.Ev0sMods.PhosphorTech.blocks.LeafSpringFlywheelCapacitorState;
import com.Ev0sMods.PhosphorTech.blocks.MechanicalGrinderState;
import com.Ev0sMods.PhosphorTech.blocks.ShaftState;
import com.Ev0sMods.PhosphorTech.blocks.SieveState;
import com.Ev0sMods.PhosphorTech.blocks.SmallGearState;
import com.Ev0sMods.PhosphorTech.blocks.SolarBoilerState;
import com.Ev0sMods.PhosphorTech.blocks.SolarPanelState;
import com.Ev0sMods.PhosphorTech.blocks.SteamGeneratorState;
import com.Ev0sMods.PhosphorTech.blocks.SteamReservoirState;
import com.Ev0sMods.PhosphorTech.blocks.WaterwheelState;
import com.Ev0sMods.PhosphorTech.blocks.WindmillState;
import com.Ev0sMods.PhosphorTech.blocks.WireState;
import com.Ev0sMods.PhosphorTech.fluid.PipeType;
import com.Ev0sMods.PhosphorTech.mechanical.GearNetwork;
import com.Ev0sMods.PhosphorTech.mechanical.ShaftAxis;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.jarhax.eyespy.api.context.BlockContext;
import com.jarhax.eyespy.api.info.InfoBuilder;
import com.jarhax.eyespy.api.info.InfoProvider;
import com.jarhax.eyespy.api.info.values.LabelValue;

/**
 * EyeSpy {@link InfoProvider} for all PhosphorTech blocks.
 *
 * <p>This class is <b>only</b> loaded when EyeSpy is present at runtime.
 * The {@link EyeSpyCompat} bridge guards class-loading via reflection.
 */
public class PhosphorTechInfoProvider implements InfoProvider<BlockContext> {

    private static final DecimalFormat ROUNDED = new DecimalFormat("#,###");

    // ─────────────────────────────────────────────────────────────────────────
    // Component lookup helper — mirrors the pattern in VanillaBlockInfoProvider
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static <T extends Component<ChunkStore>> T getComponent(
            BlockContext context, ComponentType<ChunkStore, T> type) {
                if (type == null) return null;
        WorldChunk chunk = context.getChunk();
        if (chunk == null) return null;

        BlockComponentChunk bcc = chunk.getBlockComponentChunk();
        if (bcc == null) return null;

        // EyeSpy was compiled against the pre-JOML Hytale release; call
        // getOffsetPos() reflectively to avoid a compile-time dependency on
        // the now-removed com.hypixel.hytale.math.vector.Vector3i class.
        int px, py, pz;
        try {
            Object rawPos = context.getClass().getMethod("getOffsetPos").invoke(context);
            px = (int) rawPos.getClass().getField("x").get(rawPos);
            py = (int) rawPos.getClass().getField("y").get(rawPos);
            pz = (int) rawPos.getClass().getField("z").get(rawPos);
        } catch (ReflectiveOperationException e) {
            return null;
        }
        int index = ChunkUtil.indexBlockInColumn(px, py, pz);
        Ref<ChunkStore> ref = bcc.getEntityReference(index);
        if (ref == null || !ref.isValid()) return null;

                if (context.getStore() == null) return null;
                Object ext = context.getStore().getExternalData();
                if (!(ext instanceof EntityStore)) return null;
                World world = ((EntityStore) ext).getWorld();
                if (world == null || world.getChunkStore() == null) return null;
                Store<ChunkStore> store = world.getChunkStore().getStore();
                if (store == null) return null;

                return store.getComponent(ref, type);
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void updateDescription(BlockContext context, InfoBuilder info) {

        // ── Crystal Generator ─────────────────────────────────────────────
        CrystalGeneratorState cg = getComponent(context, CrystalGeneratorState.COMPONENT_TYPE);
        if (cg != null) { addCrystalGenerator(cg, info); return; }

        // ── Steam Generator ───────────────────────────────────────────────
        SteamGeneratorState sg = getComponent(context, SteamGeneratorState.COMPONENT_TYPE);
        if (sg != null) { addSteamGenerator(sg, info); return; }

        // ── Solar Boiler ──────────────────────────────────────────────────
        SolarBoilerState sb = getComponent(context, SolarBoilerState.COMPONENT_TYPE);
        if (sb != null) { addSolarBoiler(sb, info); return; }

        // ── Solar Panel ───────────────────────────────────────────────────
        SolarPanelState sp = getComponent(context, SolarPanelState.COMPONENT_TYPE);
        if (sp != null) { addSolarPanel(sp, info); return; }

        // ── Windmill ──────────────────────────────────────────────────────
        WindmillState wm = getComponent(context, WindmillState.COMPONENT_TYPE);
        if (wm != null) { addWindmill(wm, info); return; }

        // ── Fluid Generator ───────────────────────────────────────────────
        FluidGeneratorState fg = getComponent(context, FluidGeneratorState.COMPONENT_TYPE);
        if (fg != null) { addFluidGenerator(fg, info); return; }

        // ── Crusher ───────────────────────────────────────────────────────
        CrusherState cr = getComponent(context, CrusherState.COMPONENT_TYPE);
        if (cr != null) {
            addProcessor("Crusher", cr.cfStored, CrusherState.CF_CAPACITY,
                    cr.processing, cr.processTimer, cr.ticksNeeded, info);
            return;
        }

        // ── Extractor ─────────────────────────────────────────────────────
        ExtractorState ex = getComponent(context, ExtractorState.COMPONENT_TYPE);
        if (ex != null) {
            addProcessor("Extractor", ex.cfStored, ExtractorState.CF_CAPACITY,
                    ex.processing, ex.processTimer, ex.ticksNeeded, info);
            return;
        }

        // ── Centrifuge ────────────────────────────────────────────────────
        CentrifugeState ce = getComponent(context, CentrifugeState.COMPONENT_TYPE);
        if (ce != null) {
            addProcessor("Centrifuge", ce.cfStored, CentrifugeState.CF_CAPACITY,
                    ce.processing, ce.processTimer, ce.ticksNeeded, info);
            return;
        }

        // ── Crystalline Capacitor ─────────────────────────────────────────
        CrystallineCapacitorState cap = getComponent(context, CrystallineCapacitorState.COMPONENT_TYPE);
        if (cap != null) { addCapacitor(cap, info); return; }

        // ── Steam Reservoir ───────────────────────────────────────────────
        SteamReservoirState sr = getComponent(context, SteamReservoirState.COMPONENT_TYPE);
        if (sr != null) { addSteamReservoir(sr, info); return; }

        // ── Fluid Pipe ────────────────────────────────────────────────────
        FluidPipeState fp = getComponent(context, FluidPipeState.COMPONENT_TYPE);
        if (fp != null) { addFluidPipe(fp, info); return; }

        // ── Wire ──────────────────────────────────────────────────────────
        WireState ws = getComponent(context, WireState.COMPONENT_TYPE);
        if (ws != null) { addWire(ws, info); return; }
        // ── Small Gear ────────────────────────────────────────────────────────
        SmallGearState sg2 = getComponent(context, SmallGearState.COMPONENT_TYPE);
        if (sg2 != null) { addSmallGear(sg2, info); return; }

                // ── Sieve ─────────────────────────────────────────────────────────────
                SieveState sieve = getComponent(context, SieveState.COMPONENT_TYPE);
                if (sieve != null) { addSieve(sieve, info); return; }

        // ── Large Gear ────────────────────────────────────────────────────────
        LargeGearState lg = getComponent(context, LargeGearState.COMPONENT_TYPE);
        if (lg != null) { addLargeGear(lg, info); return; }

        // ── Hand Crank ────────────────────────────────────────────────────────
        HandCrankState hc = getComponent(context, HandCrankState.COMPONENT_TYPE);
        if (hc != null) { addHandCrank(hc, info); return; }

        // ── Mechanical Grinder ────────────────────────────────────────────────
        MechanicalGrinderState mg = getComponent(context, MechanicalGrinderState.COMPONENT_TYPE);
        if (mg != null) { addMechanicalGrinder(mg, info); return; }

        // ── Leaf Spring Flywheel Capacitor ────────────────────────────────────
        LeafSpringFlywheelCapacitorState lsfc = getComponent(context, LeafSpringFlywheelCapacitorState.COMPONENT_TYPE);
        if (lsfc != null) { addFlywheelCapacitor(lsfc, info); return; }

        // ── Clutch ────────────────────────────────────────────────────
        ClutchState clutch = getComponent(context, ClutchState.COMPONENT_TYPE);
        if (clutch != null) { addClutch(clutch, info); return; }

        // ── Waterwheel ────────────────────────────────────────────────────────
        WaterwheelState ww = getComponent(context, WaterwheelState.COMPONENT_TYPE);
        if (ww != null) { addWaterwheel(ww, info); return; }

        // ── Alloy Smelter ─────────────────────────────────────────────────────
        AlloySmelterState as = getComponent(context, AlloySmelterState.COMPONENT_TYPE);
        if (as != null) { addAlloySmelter(as, info); return; }

        // ── Shaft ─────────────────────────────────────────────────────────────
        ShaftState sh = getComponent(context, ShaftState.COMPONENT_TYPE);
        if (sh != null) { addShaft(sh, info); return; }
        // ── Powered Heater ─────────────────────────────────────────────────
        com.Ev0sMods.PhosphorTech.blocks.PoweredHeaterState ph =
                getComponent(context, com.Ev0sMods.PhosphorTech.blocks.PoweredHeaterState.COMPONENT_TYPE);
        if (ph != null) { addPoweredHeater(ph, info); return; }

        // ── Mechanical Heater ───────────────────────────────────────────────
        com.Ev0sMods.PhosphorTech.blocks.MechanicalHeaterState mh =
                getComponent(context, com.Ev0sMods.PhosphorTech.blocks.MechanicalHeaterState.COMPONENT_TYPE);
        if (mh != null) { addMechanicalHeater(mh, info); return; }

        // ── Bellows ─────────────────────────────────────────────────────
        com.Ev0sMods.PhosphorTech.blocks.BellowsState bw =
                getComponent(context, com.Ev0sMods.PhosphorTech.blocks.BellowsState.COMPONENT_TYPE);
        if (bw != null) { addBellows(bw, info); return; }

        // ── Coke Oven ────────────────────────────────────────────────────
        CokeOvenState co = getComponent(context, CokeOvenState.COMPONENT_TYPE);
        if (co != null) { addCokeOven(co, info); return; }

        // ── Generic Fluid Tank ───────────────────────────────────────────
        GenericFluidTankState gft = getComponent(context, GenericFluidTankState.COMPONENT_TYPE);
        if (gft != null) { addGenericFluidTank(gft, info); return; }

        // ── Brick Form ────────────────────────────────────────────────────
        BrickFormState bf = getComponent(context, BrickFormState.COMPONENT_TYPE);
        if (bf != null) { addBrickForm(bf, info); return; }

        // ── Press ─────────────────────────────────────────────────────────
        PressState press = getComponent(context, PressState.COMPONENT_TYPE);
        if (press != null) { addPress(press, info); return; }

        // ── Compressor ────────────────────────────────────────────────────
        CompressorState comp = getComponent(context, CompressorState.COMPONENT_TYPE);
        if (comp != null) {
            addProcessor("Compressor", comp.cfStored, CompressorState.CF_CAPACITY,
                    comp.processing, comp.processTimer, comp.ticksNeeded, info);
            return;
        }

        // ── Lathe ─────────────────────────────────────────────────────────
        LatheState lathe = getComponent(context, LatheState.COMPONENT_TYPE);
        if (lathe != null) {
            addProcessor("Lathe", lathe.cfStored, LatheState.CF_CAPACITY,
                    lathe.processing, lathe.processTimer, lathe.ticksNeeded, info);
            return;
        }

        // ── Rod Puller ────────────────────────────────────────────────────
        RodPullerState rp = getComponent(context, RodPullerState.COMPONENT_TYPE);
        if (rp != null) { addRodPuller(rp, info); return; }
    }

    // ── Crystal Generator ─────────────────────────────────────────────────────

    private void addCrystalGenerator(CrystalGeneratorState s, InfoBuilder info) {
        float cfPct    = (float) s.cfStored / CrystalGeneratorState.CF_MAX_STORED;
        float steamPct = (float) s.steamMB  / CrystalGeneratorState.STEAM_MAX_MB;
        float waterPct = (float) s.waterMB  / CrystalGeneratorState.WATER_MAX_MB;

        info.set("PTCF", id -> new LabelValue(id,
                Message.raw("CF: " + ROUNDED.format(s.cfStored) + " / "
                        + ROUNDED.format(CrystalGeneratorState.CF_MAX_STORED)
                        + " (" + pct(cfPct) + ")")));
        info.set("PTSteam", id -> new LabelValue(id,
                Message.raw("Steam: " + s.steamMB + " / "
                        + CrystalGeneratorState.STEAM_MAX_MB + " mB"
                        + " (" + pct(steamPct) + ")")));
        info.set("PTWater", id -> new LabelValue(id,
                Message.raw("Water: " + s.waterMB + " / "
                        + CrystalGeneratorState.WATER_MAX_MB + " mB"
                        + " (" + pct(waterPct) + ")")));
        info.set("PTStatus", id -> new LabelValue(id,
                Message.raw(s.isGenerating ? "Generating" : "Idle")));

        addOwner(info);
    }

    // ── Steam Generator ───────────────────────────────────────────────────────

    private void addSteamGenerator(SteamGeneratorState s, InfoBuilder info) {
        float waterPct = (float) s.waterMB / SteamGeneratorState.WATER_MAX_MB;
        float steamPct = (float) s.steamMB / SteamGeneratorState.STEAM_MAX_MB;

        info.set("PTWater", id -> new LabelValue(id,
                Message.raw("Water: " + s.waterMB + " / "
                        + SteamGeneratorState.WATER_MAX_MB + " mB"
                        + " (" + pct(waterPct) + ")")));
        info.set("PTSteam", id -> new LabelValue(id,
                Message.raw("Steam: " + s.steamMB + " / "
                        + SteamGeneratorState.STEAM_MAX_MB + " mB"
                        + " (" + pct(steamPct) + ")")));
        info.set("PTStatus", id -> new LabelValue(id,
                Message.raw(s.isGenerating ? "Generating" : "Idle")));

        addOwner(info);
    }

    // ── Solar Boiler ─────────────────────────────────────────────────────

    private void addSolarBoiler(SolarBoilerState s, InfoBuilder info) {
        float waterPct = (float) s.getWaterMB() / s.getCapacity();
        float steamPct = (float) s.getSteamMB() / s.getCapacity();

        info.set("PTWater", id -> new LabelValue(id,
                Message.raw("Water: " + s.getWaterMB() + " / "
                        + s.getCapacity() + " mB (" + pct(waterPct) + ")")));
        info.set("PTSteam", id -> new LabelValue(id,
                Message.raw("Steam: " + s.getSteamMB() + " / "
                        + s.getCapacity() + " mB (" + pct(steamPct) + ")")));
        info.set("PTStatus", id -> new LabelValue(id,
                Message.raw((s.getSteamMB() > 0) ? "Has Steam" : "Idle")));
        addOwner(info);
    }

    // ── Solar Panel ──────────────────────────────────────────────────────

    private void addSolarPanel(SolarPanelState s, InfoBuilder info) {
        info.set("PTCF", id -> new LabelValue(id,
                Message.raw("CF: " + ROUNDED.format(s.getCFStored()))));
        info.set("PTStatus", id -> new LabelValue(id,
                Message.raw("Generating: " + (s.getCFStored() > 0 ? "Yes" : "No"))));
        addOwner(info);
    }

    // ── Windmill ─────────────────────────────────────────────────────────

    private void addWindmill(WindmillState s, InfoBuilder info) {
        info.set("PTJ", id -> new LabelValue(id,
                Message.raw("J: " + String.format("%.2f", s.getJoulesStored()) + " J")));
        info.set("PTSpeed", id -> new LabelValue(id,
                Message.raw("Speed: " + String.format("%.2f", s.getSpeed()))));
        addOwner(info);
    }

    // ── Fluid Generator ──────────────────────────────────────────────────

    private void addFluidGenerator(FluidGeneratorState s, InfoBuilder info) {
        info.set("PTCF", id -> new LabelValue(id,
                Message.raw("CF: " + ROUNDED.format(s.getCFStored()))));
        String fluid = s.getFluidTypeName() == null || s.getFluidTypeName().isEmpty() ? "Empty" : s.getFluidTypeName();
        info.set("PTFluid", id -> new LabelValue(id,
                Message.raw("Fluid: " + fluid + " | " + s.getFluidMB() + " / 4000 mB")));
        addOwner(info);
    }

    // ── Processor (Crusher / Extractor / Centrifuge) ──────────────────────────

    private void addProcessor(String name, long cfStored, long cfCapacity,
                              boolean processing, int processTimer,
                              int ticksNeeded, InfoBuilder info) {
        float cfPct = cfCapacity > 0 ? (float) cfStored / cfCapacity : 0f;

        info.set("PTCF", id -> new LabelValue(id,
                Message.raw("CF: " + ROUNDED.format(cfStored) + " / "
                        + ROUNDED.format(cfCapacity)
                        + " (" + pct(cfPct) + ")")));

        if (processing && ticksNeeded > 0) {
            float progressPct = ticksNeeded > 0 ? (float) processTimer / ticksNeeded : 0f;
            info.set("PTProgress", id -> new LabelValue(id,
                    Message.raw("Progress: " + pct(clamp(progressPct)))));
        }

        info.set("PTStatus", id -> new LabelValue(id,
                Message.raw(processing ? "Processing" : "Idle")));

        addOwner(info);
    }

    // ── Crystalline Capacitor ─────────────────────────────────────────────────

    private void addCapacitor(CrystallineCapacitorState s, InfoBuilder info) {
        float cfPct = (float) s.cfStored / CrystallineCapacitorState.CF_CAPACITY;

        info.set("PTCF", id -> new LabelValue(id,
                Message.raw("CF: " + ROUNDED.format(s.cfStored) + " / "
                        + ROUNDED.format(CrystallineCapacitorState.CF_CAPACITY)
                        + " (" + pct(cfPct) + ")")));

        addOwner(info);
    }

    // ── Steam Reservoir ───────────────────────────────────────────────────────

    private void addSteamReservoir(SteamReservoirState s, InfoBuilder info) {
        float steamPct = (float) s.steamMB / SteamReservoirState.STEAM_CAPACITY;

        info.set("PTSteam", id -> new LabelValue(id,
                Message.raw("Steam: " + s.steamMB + " / "
                        + SteamReservoirState.STEAM_CAPACITY + " mB"
                        + " (" + pct(steamPct) + ")")));

        addOwner(info);
    }

    // ── Fluid Pipe ────────────────────────────────────────────────────────────

    private void addFluidPipe(FluidPipeState s, InfoBuilder info) {
        PipeType type = s.pipeType;
        String fluidName = s.bufferFluid != null ? s.bufferFluid.getDisplayName() : "Empty";
        int capacity = type.getTransferAmount();

        info.set("PTPipe", id -> new LabelValue(id,
                Message.raw(type.getDisplayName() + " | " + fluidName
                        + ": " + s.bufferAmount + " / " + capacity + " mB")));
        info.set("PTHeat", id -> new LabelValue(id,
                Message.raw("Heat: " + String.format("%.1f", s.heatCelsius) + " °C")));

        addOwner(info);
    }

    // ── Small Gear ────────────────────────────────────────────────────────────

    private void addSmallGear(SmallGearState s, InfoBuilder info) {
        int connected = GearNetwork.getConnectedCount(s.getPosition());
        ShaftAxis gAxis = s.getGearAxis();
        String shaftSide = shaftSides(gAxis);
        String meshSides = gearMeshSides(gAxis);
        info.set("PTGear", id -> new LabelValue(id,
                Message.raw("Small Gear | Speed: "
                        + (s.spinTimer > 0 ? String.format("%.1f", s.getCurrentSpeed()) : "Idle")
                        + " | Connected: " + connected)));
        info.set("PTGearShaft", id -> new LabelValue(id,
                Message.raw("Shaft input: " + shaftSide)));
        info.set("PTGearMesh", id -> new LabelValue(id,
                Message.raw("Gear mesh: " + meshSides)));
        addOwner(info);
    }

    // ── Large Gear ────────────────────────────────────────────────────────────

    private void addLargeGear(LargeGearState s, InfoBuilder info) {
        int connected = GearNetwork.getConnectedCount(s.getPosition());
        ShaftAxis gAxis = s.getGearAxis();
        String shaftSide = shaftSides(gAxis);
        String meshSides = gearMeshSides(gAxis);
        info.set("PTGear", id -> new LabelValue(id,
                Message.raw("Large Gear | Speed: "
                        + (s.spinTimer > 0 ? String.format("%.1f", s.getCurrentSpeed()) : "Idle")
                        + " | Connected: " + connected)));
        info.set("PTGearShaft", id -> new LabelValue(id,
                Message.raw("Shaft input: " + shaftSide)));
        info.set("PTGearMesh", id -> new LabelValue(id,
                Message.raw("Gear mesh: " + meshSides)));
        addOwner(info);
    }

    // ── Hand Crank ────────────────────────────────────────────────────────────

    private void addHandCrank(HandCrankState s, InfoBuilder info) {
        info.set("PTCrank", id -> new LabelValue(id,
                Message.raw("Hand Crank | " + (s.revolutionTimer > 0
                        ? "Spinning (" + s.revolutionTimer + " ticks) | "
                          + String.format("%.1f", s.joulesStored) + " J stored"
                        : "Idle"))));
        addOwner(info);
    }
    // ── Clutch ─────────────────────────────────────────────────────

    private void addClutch(ClutchState s, InfoBuilder info) {
        info.set("PTClutch", id -> new LabelValue(id,
                Message.raw("Clutch: " + (s.locked ? "❌ Locked" : "✅ Unlocked"))));
        addOwner(info);
    }

    // ── Leaf Spring Flywheel Capacitor ─────────────────────────────────────

    private void addFlywheelCapacitor(LeafSpringFlywheelCapacitorState s, InfoBuilder info) {
        float jPct = (float) (s.joulesStored / LeafSpringFlywheelCapacitorState.J_CAPACITY);
        info.set("PTJ", id -> new LabelValue(id,
                Message.raw("J: " + String.format("%.1f", s.joulesStored) + " / "
                        + String.format("%.0f", LeafSpringFlywheelCapacitorState.J_CAPACITY)
                        + " (" + pct(jPct) + ")")));
        info.set("PTSpeed", id -> new LabelValue(id,
                Message.raw("Speed: " + (s.currentSpeed > 0
                        ? String.format("%.1f", s.currentSpeed)
                        : "Idle"))));
        addOwner(info);
    }
    // ── Mechanical Grinder ────────────────────────────────────────────────────

    private void addMechanicalGrinder(MechanicalGrinderState s, InfoBuilder info) {
        float jPct = (float) (s.joulesStored / MechanicalGrinderState.J_CAPACITY);
        info.set("PTJ", id -> new LabelValue(id,
                Message.raw("J: " + String.format("%.1f", s.joulesStored) + " / "
                        + String.format("%.0f", MechanicalGrinderState.J_CAPACITY)
                        + " (" + pct(jPct) + ")")));
        if (s.processing && s.ticksNeeded > 0) {
            float prog = (float) s.processTimer / s.ticksNeeded;
            info.set("PTProgress", id -> new LabelValue(id,
                    Message.raw("Progress: " + pct(clamp(prog))
                            + " | Speed: " + String.format("%.1f", s.currentSpeed))));
        }
        info.set("PTStatus", id -> new LabelValue(id,
                Message.raw(s.processing ? "Processing" : "Idle")));
        addOwner(info);
    }

        // ── Sieve ───────────────────────────────────────────────────────────────

        private void addSieve(SieveState s, InfoBuilder info) {
                float jPct = (float) (s.joulesStored / SieveState.J_CAPACITY);
                info.set("PTSieveJ", id -> new LabelValue(id,
                                Message.raw("J: " + String.format("%.1f", s.joulesStored) + " / "
                                                + String.format("%.0f", SieveState.J_CAPACITY)
                                                + " (" + pct(jPct) + ")")));

                if (s.processing && s.ticksNeeded > 0) {
                        float prog = (float) s.processTimer / s.ticksNeeded;
                        info.set("PTSieveProgress", id -> new LabelValue(id,
                                        Message.raw("Progress: " + pct(clamp(prog)) + " | Speed: " + String.format("%.1f", s.currentSpeed))));
                }

                info.set("PTSieveStatus", id -> new LabelValue(id,
                                Message.raw(s.processing ? "Processing" : "Idle")));

                addOwner(info);
        }

    // ── Wire ──────────────────────────────────────────────────────────────────

    private void addWire(WireState s, InfoBuilder info) {
        String tier = wireTierName(s.maxTransfer);

        info.set("PTWire", id -> new LabelValue(id,
                Message.raw(tier + " Wire | " + s.cfBuffer + " CF buffered | "
                        + s.maxTransfer + " CF/t")));

        addOwner(info);
    }

    // ── Waterwheel ────────────────────────────────────────────────────────────

    private void addWaterwheel(WaterwheelState s, InfoBuilder info) {
        double joulesPerTick = WaterwheelState.J_PER_SPEED * s.fluidNeighbours;
        String sides = shaftSides(s.getShaftAxis());
        info.set("PTWaterwheel", id -> new LabelValue(id,
                Message.raw("Waterwheel | Fluid sides: " + s.fluidNeighbours + "/4"
                        + " | " + String.format("%.1f", joulesPerTick) + " J/t"
                        + (s.fluidNeighbours > 0 ? " | Spinning" : " | Idle"))));
        info.set("PTWheelSides", id -> new LabelValue(id,
                Message.raw("Power output: " + sides)));
        addOwner(info);
    }

    // ── Alloy Smelter ─────────────────────────────────────────────────────────

    private void addAlloySmelter(AlloySmelterState s, InfoBuilder info) {
        float cfPct = AlloySmelterState.CF_CAPACITY > 0
                ? (float) s.cfStored / AlloySmelterState.CF_CAPACITY : 0f;
        info.set("PTCF", id -> new LabelValue(id,
                Message.raw("CF: " + ROUNDED.format(s.cfStored) + " / "
                        + ROUNDED.format(AlloySmelterState.CF_CAPACITY)
                        + " (" + pct(cfPct) + ")")));
        if (s.processing && s.ticksNeeded > 0) {
            float prog = (float) s.processTimer / s.ticksNeeded;
            info.set("PTProgress", id -> new LabelValue(id,
                    Message.raw("Progress: " + pct(clamp(prog)))));
        }
        info.set("PTStatus", id -> new LabelValue(id,
                Message.raw(s.processing ? "Smelting" : "Idle")));
        addOwner(info);
    }

    // ── Coke Oven ─────────────────────────────────────────────────────────

    private void addCokeOven(CokeOvenState s, InfoBuilder info) {
        float creosPct = CokeOvenState.CREOSOTE_CAPACITY > 0
                ? (float) s.creosoteStored / CokeOvenState.CREOSOTE_CAPACITY : 0f;
        info.set("PTCreosote", id -> new LabelValue(id,
                Message.raw("Creosote: " + ROUNDED.format(s.creosoteStored) + " / "
                        + ROUNDED.format(CokeOvenState.CREOSOTE_CAPACITY) + " mB"
                        + " (" + pct(creosPct) + ")")));
        if (s.processing && CokeOvenState.TICKS_PER_COKE > 0) {
            float prog = (float) s.processTimer / CokeOvenState.TICKS_PER_COKE;
            info.set("PTProgress", id -> new LabelValue(id,
                    Message.raw("Progress: " + pct(clamp(prog)))));
        }
        info.set("PTStatus", id -> new LabelValue(id,
                Message.raw(s.processing ? "Processing" : "Idle")));
        addOwner(info);
    }

    // ── Generic Fluid Tank ────────────────────────────────────────────────────

    private void addGenericFluidTank(GenericFluidTankState s, InfoBuilder info) {
        float pct = (float) s.fluidMB / GenericFluidTankState.CAPACITY;
        String fluidName  = s.fluidDisplayName();
        String fluidLabel = s.fluidLabel();
        info.set("PTFluid", id -> new LabelValue(id,
                Message.raw("Fluid: " + fluidName)));
        info.set("PTFluidLevel", id -> new LabelValue(id,
                Message.raw(fluidLabel + " (" + pct(pct) + ")")));
        addOwner(info);
    }

    // ── Shaft ─────────────────────────────────────────────────────────────────

    private void addShaft(ShaftState s, InfoBuilder info) {
        String sides = shaftSides(s.getShaftAxis());
        info.set("PTShaft", id -> new LabelValue(id,
                Message.raw("Shaft [" + s.axisName + "] | "
                        + (s.spinTimer > 0 ? "Spinning (" + s.spinTimer + " ticks)" : "Idle"))));
        info.set("PTShaftSides", id -> new LabelValue(id,
                Message.raw("Connects: " + sides)));
        addOwner(info);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void addOwner(InfoBuilder info) {
        info.set("PTOwner", id -> new LabelValue(id,
                Message.raw("Ev0's PhosphorTech")));
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static String pct(float value) {
        return (int)(clamp(value) * 100) + "%";
    }

    private static String shaftSides(ShaftAxis axis) {
        return switch (axis) {
            case X -> "East / West";
            case Y -> "Up / Down";
            case Z -> "North / South";
        };
    }

    /** Returns the four gear-meshing faces (perpendicular plane to the gear axis). */
    private static String gearMeshSides(ShaftAxis axis) {
        return switch (axis) {
            case Y -> "North / South / East / West"; // XZ plane
            case X -> "Up / Down / North / South";   // YZ plane
            case Z -> "Up / Down / East / West";     // XY plane
        };
    }

    private static String wireTierName(int maxTransfer) {
        if (maxTransfer >= WireState.ADAMANTITE_TRANSFER) return "Adamantite";
        if (maxTransfer >= WireState.COBALT_TRANSFER)     return "Cobalt";
        if (maxTransfer >= WireState.THORIUM_TRANSFER)    return "Thorium";
        if (maxTransfer >= WireState.IRON_TRANSFER)       return "Iron";
        return "Copper";
    }

    // ── Powered Heater ────────────────────────────────────────────────────────

    private void addPoweredHeater(com.Ev0sMods.PhosphorTech.blocks.PoweredHeaterState s, InfoBuilder info) {
        float cfPct   = (float) s.cfStored / com.Ev0sMods.PhosphorTech.blocks.PoweredHeaterState.CF_CAPACITY;
        float heatPct = (float) (s.heatCelsius / com.Ev0sMods.PhosphorTech.blocks.PoweredHeaterState.MAX_HEAT_CELSIUS);

        info.set("PTCF", id -> new LabelValue(id,
                Message.raw("CF: " + ROUNDED.format(s.cfStored) + " / "
                        + ROUNDED.format(com.Ev0sMods.PhosphorTech.blocks.PoweredHeaterState.CF_CAPACITY)
                        + " (" + pct(cfPct) + ")")));
        info.set("PTHeat", id -> new LabelValue(id,
                Message.raw("Heat: " + String.format("%.1f", s.heatCelsius) + " / "
                        + String.format("%.0f", com.Ev0sMods.PhosphorTech.blocks.PoweredHeaterState.MAX_HEAT_CELSIUS)
                        + " °C (" + pct(heatPct) + ")")));
        info.set("PTStatus", id -> new LabelValue(id,
                Message.raw(s.isHeating ? "Heating" : "Idle")));
        addOwner(info);
    }

    // ── Mechanical Heater ─────────────────────────────────────────────────────

    private void addMechanicalHeater(com.Ev0sMods.PhosphorTech.blocks.MechanicalHeaterState s, InfoBuilder info) {
        float jPct    = (float) (s.joulesStored / com.Ev0sMods.PhosphorTech.blocks.MechanicalHeaterState.J_CAPACITY);
        float heatPct = (float) (s.heatCelsius / com.Ev0sMods.PhosphorTech.blocks.MechanicalHeaterState.MAX_HEAT_CELSIUS);

        info.set("PTJ", id -> new LabelValue(id,
                Message.raw("J: " + String.format("%.1f", s.joulesStored) + " / "
                        + String.format("%.0f", com.Ev0sMods.PhosphorTech.blocks.MechanicalHeaterState.J_CAPACITY)
                        + " (" + pct(jPct) + ")")));
        info.set("PTHeat", id -> new LabelValue(id,
                Message.raw("Heat: " + String.format("%.1f", s.heatCelsius) + " / "
                        + String.format("%.0f", com.Ev0sMods.PhosphorTech.blocks.MechanicalHeaterState.MAX_HEAT_CELSIUS)
                        + " °C (" + pct(heatPct) + ")")));
        info.set("PTStatus", id -> new LabelValue(id,
                Message.raw(s.isHeating ? "Heating" : "Idle")));
        addOwner(info);
    }

    // ── Bellows ───────────────────────────────────────────────────────────────

    private void addBellows(com.Ev0sMods.PhosphorTech.blocks.BellowsState s, InfoBuilder info) {
        info.set("PTBellows", id -> new LabelValue(id,
                Message.raw("Bellows | " + (s.pumpTimer > 0
                        ? "Pumping (" + s.pumpTimer + " ticks)"
                        : "Idle"))));
        addOwner(info);
    }

    // ── Press ─────────────────────────────────────────────────────────────────

    private void addPress(PressState s, InfoBuilder info) {
        if (s.processing && s.ticksNeeded > 0) {
            float prog = (float) s.processTimer / s.ticksNeeded;
            info.set("PTProgress", id -> new LabelValue(id,
                    Message.raw("Progress: " + pct(clamp(prog)))));
        }
        info.set("PTStatus", id -> new LabelValue(id,
                Message.raw(s.processing ? "Pressing" : "Idle")));
        addOwner(info);
    }

    // ── Rod Puller ────────────────────────────────────────────────────────────

    private void addRodPuller(RodPullerState s, InfoBuilder info) {
        float jPct = (float)(s.joulesStored / RodPullerState.J_CAPACITY);
        info.set("PTJ", id -> new LabelValue(id,
                Message.raw("J: " + String.format("%.1f", s.joulesStored) + " / "
                        + String.format("%.0f", RodPullerState.J_CAPACITY)
                        + " (" + pct(jPct) + ")")));
        if (s.processing && s.ticksNeeded > 0) {
            float prog = (float) s.processTimer / s.ticksNeeded;
            info.set("PTProgress", id -> new LabelValue(id,
                    Message.raw("Progress: " + pct(clamp(prog))
                            + " | Speed: " + String.format("%.1f", s.currentSpeed))));
        }
        info.set("PTStatus", id -> new LabelValue(id,
                Message.raw(s.processing ? "Pulling" : "Idle")));
        addOwner(info);
    }

    // ── Brick Form ────────────────────────────────────────────────────────────

    private void addBrickForm(BrickFormState s, InfoBuilder info) {
        com.hypixel.hytale.server.core.inventory.ItemStack slot =
                s.getItemContainer() != null ? s.getItemContainer().getItemStack((short) 0) : null;
        int qty = (slot != null && !slot.isEmpty()) ? slot.getQuantity() : 0;
        String itemId = (slot != null && !slot.isEmpty()) ? slot.getItemId() : null;

        String contents = itemId != null ? itemId + " × " + qty : "Empty";
        info.set("PTBrickFormContents", id -> new LabelValue(id,
                Message.raw("Contents: " + contents)));

        String status;
        if (s.processingTick < 0) {
            status = qty > 0 ? "Filling (" + qty + " / " + BrickFormState.REQUIRED_QUANTITY + ")" : "Empty";
        } else if (s.processingTick < BrickFormState.PROCESS_TICKS) {
            status = "Processing (" + s.processingTick + " / " + BrickFormState.PROCESS_TICKS + ")";
        } else {
            status = "Ready to collect";
        }
        info.set("PTBrickFormStatus", id -> new LabelValue(id,
                Message.raw("Status: " + status)));
        addOwner(info);
    }
}
