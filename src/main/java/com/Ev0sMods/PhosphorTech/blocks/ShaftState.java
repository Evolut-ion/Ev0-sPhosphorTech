package com.Ev0sMods.PhosphorTech.blocks;

import java.util.Map;

import org.joml.Vector3i;

import com.Ev0sMods.PhosphorTech.mechanical.GearConnectable;
import com.Ev0sMods.PhosphorTech.mechanical.GearNetwork;
import com.Ev0sMods.PhosphorTech.mechanical.MechanicalCapable;
import com.Ev0sMods.PhosphorTech.mechanical.ShaftAxis;
import com.Ev0sMods.PhosphorTech.mechanical.ShaftConnectable;
import com.Ev0sMods.PhosphorTech.mechanical.SpinningGear;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/**
 * ECS component for a Shaft block.
 *
 * <p>Shafts transmit mechanical rotation along a single fixed axis (X, Y, or Z).
 * The axis is auto-detected on the first tick: whichever pair of axis-aligned
 * neighbours is occupied in the gear network determines the shaft's axis.  If
 * no neighbours are present yet, the axis defaults to {@link ShaftAxis#X} and
 * is re-evaluated every tick until a neighbour is found.
 *
 * <p>Shafts implement {@link ShaftConnectable} so that {@code GearNetwork}
 * can route spin along the shaft's axis instead of the usual horizontal plane.
 *
 * <p>Block animation states: {@code "X"}, {@code "Y"}, {@code "Z"} (spinning on
 * the respective axis) and {@code "Off"} (idle).
 */
// SuppressWarnings removed
public class ShaftState
        implements Component<ChunkStore>, TickableBlockState,
                   SpinningGear, MechanicalCapable, ShaftConnectable {

    // ── Component registration ────────────────────────────────────────────────

    public static ComponentType<ChunkStore, ShaftState> COMPONENT_TYPE;

    // ── Serialised fields ─────────────────────────────────────────────────────

    /** Ticks remaining in the current spin burst.  0 = idle. */
    public int spinTimer = 0;

    /** Set by the cleanup system the moment the block is removed — tick() checks this first. */
    volatile boolean removed = false;

    /** Persisted axis so the shaft keeps its orientation across restarts. */
    public String axisName = "X";

    // ── Runtime-only state ────────────────────────────────────────────────────

    private ShaftAxis axis          = ShaftAxis.X;
    private double    currentSpeed  = 1.0;
    private boolean   axisResolved  = false;
    private int       spinDirection = 1;

    /** Last rendered spin state to avoid redundant setBlock calls. */
    private boolean lastRenderedSpin = false;

    private Vector3i cachedPosition      = new Vector3i(0, 0, 0);
    private boolean  positionResolved    = false;
    private boolean  registeredInNetwork = false;

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final BuilderCodec<ShaftState> CODEC =
            BuilderCodec.builder(ShaftState.class, ShaftState::new)
                .append(new KeyedCodec<>("SpinTimer", Codec.INTEGER, true),
                        (s, v) -> s.spinTimer = v, s -> s.spinTimer).add()
                .append(new KeyedCodec<>("Axis", Codec.STRING, true),
                        (s, v) -> { s.axisName = v; s.axis = parseAxis(v); s.axisResolved = true; },
                        s -> s.axisName).add()
                .build();

    // ── Constructor ───────────────────────────────────────────────────────────

    public ShaftState() {
        // Ensure axis is not resolved on placement so detectAxis runs on first tick
        this.axisResolved = false;
    }

    // Copy constructor removed (unused)

    // ── Component ─────────────────────────────────────────────────────────────

    @Override
    public WorldChunk getChunk() { return null; }
    @Override
    public Vector3i   getPosition() { return cachedPosition; }
    @Override
    public void       invalidate()  { registeredInNetwork = false; }

    // ── ShaftConnectable ──────────────────────────────────────────────────────

    @Override
    public ShaftAxis getShaftAxis() { return axis; }

    // ── MechanicalCapable ─────────────────────────────────────────────────────

    @Override
    public void onNeighborGearChanged() {
        // Always re-detect axis when a neighbor changes, even if spinning.
        // If a gear is present adjacent, force axis to match the gear's axis.
        int x = cachedPosition.x, y = cachedPosition.y, z = cachedPosition.z;
        boolean matchedGear = false;
        Object[] neighbors = {
            GearNetwork.getAt(new Vector3i(x, y + 1, z)),
            GearNetwork.getAt(new Vector3i(x, y - 1, z)),
            GearNetwork.getAt(new Vector3i(x + 1, y, z)),
            GearNetwork.getAt(new Vector3i(x - 1, y, z)),
            GearNetwork.getAt(new Vector3i(x, y, z + 1)),
            GearNetwork.getAt(new Vector3i(x, y, z - 1))
        };
        ShaftAxis[] axes = {
            ShaftAxis.Y, ShaftAxis.Y,
            ShaftAxis.X, ShaftAxis.X,
            ShaftAxis.Z, ShaftAxis.Z
        };
        for (int i = 0; i < neighbors.length; i++) {
            Object node = neighbors[i];
            if (node instanceof GearConnectable gc && gc.getGearAxis() == axes[i]) {
                setAxis(axes[i]);
                matchedGear = true;
                break;
            }
        }
        if (!matchedGear) {
            axisResolved = false;
        }
        // Re-sync all connected shaft animations so a newly placed shaft starts
        // in phase with the rest of the line.
        resetConnectedShaftAnimations();
    }

    // ── SpinningGear ──────────────────────────────────────────────────────────

    @Override
    public void receiveSpinSignal(double speed) {
        currentSpeed = speed;
        spinTimer    = 3;
    }

    @Override
    public void receiveSpinSignal(double speed, int direction) {
        if (direction != spinDirection) {
            spinDirection = direction;
            lastRenderedSpin = false; // force block re-place with correct SpinBack/Spin state
        }
        receiveSpinSignal(speed);
    }

    @Override
    public void resetAnimation() {
        // Only force re-place if currently spinning; idle shafts should not be
        // pulled back into the spin state by a network-wide sync call.
        if (spinTimer > 0) lastRenderedSpin = false;
    }

    @Override
    public int getSpinDirection() { return spinDirection; }

    @Override
    public void stopSpin() {
        spinTimer = 0;
        // Force lastRenderedSpin=true so the !spinning && lastRenderedSpin branch
        // always fires on the next tick and applies "Idle". Without this, shafts
        // further down the chain may have lastRenderedSpin=false (e.g. cleared by
        // a direction-change event) and get stuck in the spinning visual state.
        lastRenderedSpin = true;
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @Override
    public void tick(float dt, int index, ArchetypeChunk<ChunkStore> archetypeChunk, Store<ChunkStore> store, CommandBuffer<ChunkStore> commandBuffer) {

        if (removed) return;

        World world = store.getExternalData().getWorld();
        // if (world == null) return; // removed dead code

        if (!positionResolved) {
            probePosition();
            if (!positionResolved && archetypeChunk != null) {
                Ref<ChunkStore> myRef = archetypeChunk.getReferenceTo(index);
                resolvePositionFromStore(store, myRef);
            }
            if (!positionResolved) {
                System.out.println("[ShaftState] Position not resolved for shaft, skipping registration. Index: " + index);
                return;
            } else {
                System.out.println("[ShaftState] Position resolved: " + cachedPosition + " (Index: " + index + ")");
            }
        }

        if (!registeredInNetwork) {
            System.out.println("[ShaftState] Registering shaft at position: " + cachedPosition);
            GearNetwork.register(cachedPosition, this);
            registeredInNetwork = true;
        }

        // Heartbeat tick for periodic sync
        GearNetwork.heartbeatTick();

        if (!axisResolved) {
            System.out.println("[ShaftState] Axis not resolved for shaft at " + cachedPosition + ", attempting to detect axis.");
            // First try to infer axis from the block's placement rotation so the
            // shaft respects the direction the player was looking (Pipe rotation).
            // Neighbor detection below can still override this.
            WorldChunk placementChunk = world.getChunkIfInMemory(
                    ChunkUtil.indexChunkFromBlock(cachedPosition.x, cachedPosition.z));
            if (placementChunk != null) {
                int placementRot = placementChunk.getRotationIndex(cachedPosition.x, cachedPosition.y, cachedPosition.z);
                ShaftAxis inferredAxis = switch (placementRot) {
                    case 1  -> ShaftAxis.Z;
                    case 4  -> ShaftAxis.Y;
                    default -> ShaftAxis.X; // rot 0 or anything else → X
                };
                setAxis(inferredAxis);
            }
            // Neighbor-based detection still runs and will override the above
            // if an adjacent gear or shaft provides a stronger signal.
            detectAxis();
            if (axisResolved) {
                System.out.println("[ShaftState] Axis resolved for shaft at " + cachedPosition + ": " + axis);
                // Normalize the block's visual rotation immediately so a shaft
                // placed from either end of its axis looks identical.
                BlockAnimator.applyBlockState(world, cachedPosition, "Idle", rotationForAxis(axis));
            } else {
                System.out.println("[ShaftState] Axis still not resolved for shaft at " + cachedPosition);
            }
        }

        if (spinTimer > 0) {
            spinTimer--;
        }

        // Animation — guard against block-state packets for a destroyed block.
        WorldChunk guardChunk = world.getChunkIfInMemory(
                ChunkUtil.indexChunkFromBlock(cachedPosition.x, cachedPosition.z));
        if (guardChunk == null || guardChunk.getBlockType(cachedPosition.x, cachedPosition.y, cachedPosition.z) == null) {
            // Do NOT clear lastRenderedSpin here. If the shaft just stopped spinning
            // (spinTimer=0, lastRenderedSpin=true), clearing the flag here causes the
            // !spinning && lastRenderedSpin branch to never fire, leaving the shaft
            // visually stuck in the Spin animation state forever.
            return;
        }
        boolean spinning = spinTimer > 0;
        if (spinning && !lastRenderedSpin) {
            int rot = rotationForAxis(axis);
            if (spinDirection > 0) {
                BlockAnimator.applyBlockState(world, cachedPosition, "SpinBack", rot);
            } else {
                BlockAnimator.applyBlockState(world, cachedPosition, "Spin", rot);
            }
            lastRenderedSpin = true;
        } else if (!spinning && lastRenderedSpin) {
            BlockAnimator.applyBlockState(world, cachedPosition, "Idle", rotationForAxis(axis));
            lastRenderedSpin = false;
        }
    }

    // ── Axis detection ────────────────────────────────────────────────────────

    /**
     * Determines axis by checking which pair of in-axis neighbours is populated
     * in the gear network.  Priority: adjacent gear whose axis matches the step
     * direction (so the shaft auto-aligns to receive power), then any neighbour
     * along Y, X, or Z.
     */
    private void detectAxis() {
        int x = cachedPosition.x, y = cachedPosition.y, z = cachedPosition.z;

        // Check all axes for adjacent gears or shafts whose axis matches
        // Priority 1: If any adjacent gear or shaft's axis matches, set to that axis
        Object nodeYp = GearNetwork.getAt(new Vector3i(x, y + 1, z));
        Object nodeYm = GearNetwork.getAt(new Vector3i(x, y - 1, z));
        if ((nodeYp instanceof GearConnectable gcYp && gcYp.getGearAxis() == ShaftAxis.Y) ||
            (nodeYm instanceof GearConnectable gcYm && gcYm.getGearAxis() == ShaftAxis.Y) ||
            (nodeYp instanceof ShaftConnectable scYp && scYp.getShaftAxis() == ShaftAxis.Y) ||
            (nodeYm instanceof ShaftConnectable scYm && scYm.getShaftAxis() == ShaftAxis.Y)) {
            setAxis(ShaftAxis.Y); return;
        }
        Object nodeXp = GearNetwork.getAt(new Vector3i(x + 1, y, z));
        Object nodeXm = GearNetwork.getAt(new Vector3i(x - 1, y, z));
        if ((nodeXp instanceof GearConnectable gcXp && gcXp.getGearAxis() == ShaftAxis.X) ||
            (nodeXm instanceof GearConnectable gcXm && gcXm.getGearAxis() == ShaftAxis.X) ||
            (nodeXp instanceof ShaftConnectable scXp && scXp.getShaftAxis() == ShaftAxis.X) ||
            (nodeXm instanceof ShaftConnectable scXm && scXm.getShaftAxis() == ShaftAxis.X)) {
            setAxis(ShaftAxis.X); return;
        }
        Object nodeZp = GearNetwork.getAt(new Vector3i(x, y, z + 1));
        Object nodeZm = GearNetwork.getAt(new Vector3i(x, y, z - 1));
        if ((nodeZp instanceof GearConnectable gcZp && gcZp.getGearAxis() == ShaftAxis.Z) ||
            (nodeZm instanceof GearConnectable gcZm && gcZm.getGearAxis() == ShaftAxis.Z) ||
            (nodeZp instanceof ShaftConnectable scZp && scZp.getShaftAxis() == ShaftAxis.Z) ||
            (nodeZm instanceof ShaftConnectable scZm && scZm.getShaftAxis() == ShaftAxis.Z)) {
            setAxis(ShaftAxis.Z); return;
        }

        // Priority 2: Align with any neighbour (shaft or other node) along each axis.
        if (nodeYp != null || nodeYm != null) {
            setAxis(ShaftAxis.Y); return;
        }
        if (nodeXp != null || nodeXm != null) {
            setAxis(ShaftAxis.X); return;
        }
        if (nodeZp != null || nodeZm != null) {
            setAxis(ShaftAxis.Z);
        }
        // No neighbour found — keep existing axis but mark as not yet resolved
        // so we try again next tick.
    }

    private void setAxis(ShaftAxis a) {
        boolean changed = (a != axis);
        axis          = a;
        axisName      = a.name();
        axisResolved  = true;
        if (changed) {
            // Force animation re-apply with the new rotation on next tick.
            lastRenderedSpin = false;
        }
    }

    private static ShaftAxis parseAxis(String s) {
        if (s == null) return ShaftAxis.X;
        return switch (s.toUpperCase()) {
            case "Y" -> ShaftAxis.Y;
            case "Z" -> ShaftAxis.Z;
            default  -> ShaftAxis.X;
        };
    }

    /**
     * Maps axis to chunk rotation index so the visual block matches the shaft direction.
     * The shaft model is built along X (perpendicular to north), so:
     * <ul>
     *   <li>X axis → rot 0 (North) — model stays along X</li>
     *   <li>Z axis → rot 1 (East)  — model rotates 90° to run along Z</li>
     *   <li>Y axis → rot 4 (Up)    — model rotates to run vertically</li>
     * </ul>
     */
    private static int rotationForAxis(ShaftAxis a) {
        return switch (a) {
            case X -> 0;
            case Z -> 1;
            case Y -> 4;
        };
    }

    /**
     * Walks along the shaft axis in both directions, resetting the animation
     * flag on every connected shaft so they all re-place their spinning block
     * on the next tick (forcing animation sync).
     */
    private void resetConnectedShaftAnimations() {
        if (spinTimer > 0) lastRenderedSpin = false;
        if (!positionResolved) return;
        for (int sign = -1; sign <= 1; sign += 2) {
            Vector3i pos = cachedPosition;
            for (int i = 0; i < 64; i++) {
                pos = stepAlongAxis(pos, axis, sign);
                Object node = GearNetwork.getAt(pos);
                if (node instanceof ShaftState ss) {
                    if (ss.spinTimer > 0) ss.lastRenderedSpin = false;
                } else {
                    break;
                }
            }
        }
    }

    private static Vector3i stepAlongAxis(Vector3i pos, ShaftAxis a, int sign) {
        return switch (a) {
            case X -> new Vector3i(pos.x + sign, pos.y, pos.z);
            case Y -> new Vector3i(pos.x, pos.y + sign, pos.z);
            case Z -> new Vector3i(pos.x, pos.y, pos.z + sign);
        };
    }

    // ── Position resolution ───────────────────────────────────────────────────

    private void probePosition() {
        try {
            Class<?> sc = getClass().getSuperclass();
            if (sc == null) return;
            for (String name : new String[]{"getBlockPosition", "getPosition", "getPos", "position"}) {
                try {
                    java.lang.reflect.Method m = sc.getMethod(name);
                    Object r = m.invoke(this);
                    if (r instanceof Vector3i v && !(v.x == 0 && v.y == 0 && v.z == 0)) {
                        cachedPosition = v; positionResolved = true; return;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static volatile java.lang.reflect.Method entityRefsMethod;
    private static volatile boolean entityRefsMethodResolved;

    private void resolvePositionFromStore(Store<ChunkStore> store, Ref<ChunkStore> myRef) {
        try {
            int myIdx = myRef.getIndex();
            ChunkStore cs = store.getExternalData();
            var chunks = cs.getChunkIndexes();
            if (chunks.isEmpty()) return;
            for (long chunkIdx : chunks) {
                Ref<ChunkStore> colRef = cs.getChunkReference(chunkIdx);
                if (colRef == null) continue;
                BlockComponentChunk bcc = store.getComponent(colRef, BlockComponentChunk.getComponentType());
                if (bcc == null) continue;
                Map<?, ?> refs = entityRefsViaReflection(bcc);
                if (refs == null) continue;
                for (Map.Entry<?, ?> e : refs.entrySet()) {
                    if (!(e.getKey() instanceof Integer blockIndex)) continue;
                    if (!(e.getValue() instanceof Ref<?> ref)) continue;
                    if (ref.getIndex() != myIdx) continue;
                    int lx = ChunkUtil.xFromBlockInColumn(blockIndex);
                    int wy = ChunkUtil.yFromBlockInColumn(blockIndex);
                    int lz = ChunkUtil.zFromBlockInColumn(blockIndex);
                    int wx = ChunkUtil.worldCoordFromLocalCoord(ChunkUtil.xOfChunkIndex(chunkIdx), lx);
                    int wz = ChunkUtil.worldCoordFromLocalCoord(ChunkUtil.zOfChunkIndex(chunkIdx), lz);
                    cachedPosition = new Vector3i(wx, wy, wz);
                    positionResolved = true;
                    return;
                }
            }
        } catch (NullPointerException | ClassCastException | IllegalStateException ignored) {}
    }

    // @SuppressWarnings("unchecked") removed
    private static Map<?, ?> entityRefsViaReflection(BlockComponentChunk bcc) {
        try {
            if (!entityRefsMethodResolved) {
                synchronized (ShaftState.class) {
                    if (!entityRefsMethodResolved) {
                        for (java.lang.reflect.Method m : bcc.getClass().getMethods()) {
                            if ("getEntityReferences".equals(m.getName()) && m.getParameterCount() == 0) {
                                m.setAccessible(true);
                                entityRefsMethod = m;
                                break;
                            }
                        }
                        entityRefsMethodResolved = true;
                    }
                }
            }
            if (entityRefsMethod == null) return null;
            Object r = entityRefsMethod.invoke(bcc);
            return r instanceof Map<?, ?> map ? map : null;
        } catch (Exception ignored) { return null; }
    }

    @Override
    public ShaftState clone() {
        try {
            return (ShaftState) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }
}
