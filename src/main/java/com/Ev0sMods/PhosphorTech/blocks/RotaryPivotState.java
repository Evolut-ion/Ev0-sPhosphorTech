package com.Ev0sMods.PhosphorTech.blocks;

import com.Ev0sMods.PhosphorTech.compat.VectorCompat;
import com.Ev0sMods.PhosphorTech.mechanical.GearNetwork;
import com.Ev0sMods.PhosphorTech.mechanical.JouleReceiver;
import com.Ev0sMods.PhosphorTech.rotary.BlockGroupCapture;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings({"unchecked", "removal", "rawtypes"})
public class RotaryPivotState implements Component<ChunkStore>, TickableBlockState, JouleReceiver {

    // ── Constants ────────────────────────────────────────────────────────────

    public static final int    ROTATION_TICKS      = 40;
    public static final double JOULES_PER_ROTATION = 16.0;
    public static final double JOULE_CAPACITY      = 32.0;

    private static final int PHASE_IDLE      = 0;
    private static final int PHASE_LOCKED    = 1;
    private static final int PHASE_ROTATING  = 2;
    private static final int PHASE_RESTORING = 3;

    private static final int[][] FACE_OFFSETS = {
        { 1,0,0},{-1,0,0},{ 0,1,0},{ 0,-1,0},{ 0,0,1},{ 0,0,-1}
    };

    // ── Serialised fields ────────────────────────────────────────────────────

    public int    lockedFace   = -1;
    public int    phase        = PHASE_IDLE;
    public int    rotationTick = 0;
    public double joulesStored = 0.0;

    // ── Codec ────────────────────────────────────────────────────────────────

    public static final BuilderCodec<RotaryPivotState> CODEC =
            BuilderCodec.builder(RotaryPivotState.class, RotaryPivotState::new)
                .append(new KeyedCodec<>("LockedFace",   Codec.LONG,   true),
                        (s, v) -> s.lockedFace   = v == null ? -1  : (int)(long) v,
                        s -> (long) s.lockedFace).add()
                .append(new KeyedCodec<>("Phase",         Codec.LONG,   true),
                        (s, v) -> s.phase        = v == null ?  0  : (int)(long) v,
                        s -> (long) s.phase).add()
                .append(new KeyedCodec<>("RotationTick",  Codec.LONG,   true),
                        (s, v) -> s.rotationTick = v == null ?  0  : (int)(long) v,
                        s -> (long) s.rotationTick).add()
                .append(new KeyedCodec<>("JoulesStored",  Codec.DOUBLE, true),
                        (s, v) -> s.joulesStored = v == null ? 0.0 : v,
                        s -> s.joulesStored).add()
                .build();

    // ── Registration ─────────────────────────────────────────────────────────

    public static ComponentType<ChunkStore, RotaryPivotState> COMPONENT_TYPE;
    public static final ConcurrentHashMap<String, RotaryPivotState> REGISTRY =
            new ConcurrentHashMap<>();

    // ── Runtime-only fields ──────────────────────────────────────────────────

    volatile boolean activated    = false;
    volatile boolean removed      = false;
    volatile double  lastSpeed    = 1.0;

    public void activate() { this.activated = true; }

    private Vector3i          cachedPosition   = new Vector3i(0, 0, 0);
    private boolean           positionResolved = false;
    private BlockGroupCapture pendingCapture   = null;

    private final List<Ref<EntityStore>> animEntities = new ArrayList<>();

    // ── Constructor / clone ──────────────────────────────────────────────────

    public RotaryPivotState() {}

    private RotaryPivotState(RotaryPivotState src) {
        this.lockedFace   = src.lockedFace;
        this.phase        = src.phase;
        this.rotationTick = src.rotationTick;
        this.joulesStored = src.joulesStored;
    }

    // ── Component ────────────────────────────────────────────────────────────

    @Override public RotaryPivotState clone()      { return new RotaryPivotState(this); }
    @Override public WorldChunk       getChunk()   { return null; }
    @Override public Vector3i         getPosition(){ return cachedPosition; }

    @Override
    public void invalidate() {
        if (!positionResolved) return;
        REGISTRY.remove(VectorCompat.posKey(cachedPosition), this);
        GearNetwork.unregisterExact(cachedPosition, this);
    }

    // ── JouleReceiver ────────────────────────────────────────────────────────

    @Override public double getJoulesStored()   { return joulesStored; }
    @Override public double getJoulesCapacity() { return JOULE_CAPACITY; }

    @Override
    public double receiveJoules(double amount, double speed, boolean simulate) {
        double accepted = Math.min(amount, JOULE_CAPACITY - joulesStored);
        if (!simulate) {
            joulesStored += accepted;
            if (speed > 0) lastSpeed = speed;
        }
        return accepted;
    }

    // ── TickableBlockState ───────────────────────────────────────────────────

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
                     @Nonnull Store<ChunkStore> store,
                     @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        if (removed) return;

        World world = store.getExternalData().getWorld();
        if (world == null) return;

        if (!positionResolved) {
            resolvePosition(store, archetypeChunk.getReferenceTo(index));
            if (!positionResolved) return;
            REGISTRY.put(VectorCompat.posKey(cachedPosition), this);
            if (phase == PHASE_LOCKED) GearNetwork.register(cachedPosition, this);
        }

        switch (phase) {
            case PHASE_IDLE      -> tickIdle(world);
            case PHASE_LOCKED    -> tickLocked(world);
            case PHASE_ROTATING  -> tickRotating(world);
            case PHASE_RESTORING -> tickRestoring(world);
        }
    }

    // ── Phase handlers ───────────────────────────────────────────────────────

    private void tickIdle(World world) {
        if (!activated) return;
        activated = false;

        for (int f = 0; f < 6; f++) {
            int[] o = FACE_OFFSETS[f];
            int nx = cachedPosition.x + o[0];
            int ny = cachedPosition.y + o[1];
            int nz = cachedPosition.z + o[2];
            try {
                WorldChunk c = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(nx, nz));
                if (c == null) continue;
                var bt = c.getBlockType(nx, ny, nz);
                if (bt == null) continue;
                String id = bt.getId();
                if (id == null || id.isEmpty()) continue;
                lockedFace = f;
                phase      = PHASE_LOCKED;
                GearNetwork.register(cachedPosition, this);
                System.out.println("[RotaryPivot] Locked to face " + f);
                return;
            } catch (Throwable ignored) {}
        }
        System.out.println("[RotaryPivot] No adjacent block found.");
    }

    private void tickLocked(World world) {
        if (activated) {
            activated = false;
            joulesStored = JOULES_PER_ROTATION;
        }

        if (joulesStored < JOULES_PER_ROTATION) return;
        joulesStored -= JOULES_PER_ROTATION;

        pendingCapture = BlockGroupCapture.capture(world, cachedPosition, lockedFace);
        pendingCapture.removeFromWorld(world);
        startAnimation(world);
        System.out.println("[RotaryPivot] captured=" + pendingCapture.getBlocks().size()
                + " animEntities=" + animEntities.stream().filter(r -> r != null).count());

        rotationTick = Math.max(1, (int)(ROTATION_TICKS / lastSpeed));
        phase        = PHASE_ROTATING;
    }

    private void tickRotating(World world) {
        rotationTick--;
        updateAnimation(world);
        if (rotationTick > 0) return;
        phase = PHASE_RESTORING;
    }

    private void tickRestoring(World world) {
        clearAllAnimationState(world);
        int axis = axisForFace(lockedFace);
        if (joulesStored >= JOULES_PER_ROTATION && pendingCapture != null) {
            joulesStored -= JOULES_PER_ROTATION;
            pendingCapture = pendingCapture.rotated(axis);
            startAnimation(world);
            rotationTick = Math.max(1, (int)(ROTATION_TICKS / lastSpeed));
            phase = PHASE_ROTATING;
        } else {
            if (pendingCapture != null) {
                pendingCapture.restoreRotated(world, axis);
                pendingCapture = null;
            }
            phase = PHASE_LOCKED;
        }
    }

    // ── Animation start ───────────────────────────────────────────────────────

    private void startAnimation(World world) {
        if (pendingCapture == null) return;
        animEntities.clear();

        Store<EntityStore> es = getEntityStore(world);
        if (es == null) return;

        for (BlockGroupCapture.CapturedBlock b : pendingCapture.getBlocks()) {
            animEntities.add(spawnBlockVisual(es, b));
        }
    }

    // ── Per-tick update ───────────────────────────────────────────────────────

    private void updateAnimation(World world) {
        if (pendingCapture == null || animEntities.isEmpty()) return;
        int axis = axisForFace(lockedFace);
        double progress = 1.0 - (double) rotationTick / ROTATION_TICKS;
        double angle    = progress * (Math.PI / 2.0);

        Store<EntityStore> es = getEntityStore(world);
        if (es == null) return;

        List<BlockGroupCapture.CapturedBlock> blocks = pendingCapture.getBlocks();
        for (int i = 0; i < blocks.size() && i < animEntities.size(); i++) {
            Ref<EntityStore> ref = animEntities.get(i);
            if (ref == null || !ref.isValid()) continue;
            BlockGroupCapture.CapturedBlock b = blocks.get(i);
            double[] r = arcPos(b.dx(), b.dy(), b.dz(), axis, angle);
            double wx = cachedPosition.x + r[0] + 0.5;
            double wy = cachedPosition.y + r[1];
            double wz = cachedPosition.z + r[2] + 0.5;

            float[] rot = arcOrientationEuler(b.rotation(), axis, angle);
            updateEntityTransform(es, ref, wx, wy, wz, rot[0], rot[1], rot[2]);
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private void clearAllAnimationState(World world) {
        Store<EntityStore> es = getEntityStore(world);
        for (Ref<EntityStore> ref : animEntities) {
            if (ref == null) continue;
            try {
                if (es != null) es.removeEntity(ref, RemoveReason.REMOVE);
            } catch (Throwable ignored) {}
        }
        animEntities.clear();
    }

    // ── Block visual entity spawning ──────────────────────────────────────────

    private Ref<EntityStore> spawnBlockVisual(Store<EntityStore> es,
                                               BlockGroupCapture.CapturedBlock b) {
        try {
            if (!resolveBlockRefl()) return null;
            float[] euler = indexToEulerRad(b.rotation());
            Object pos = vec3dCtor.newInstance(
                    cachedPosition.x + b.dx() + 0.5,
                    cachedPosition.y + b.dy(),
                    cachedPosition.z + b.dz() + 0.5);
            Object rot = vec3fCtor.newInstance(euler[0], euler[1], euler[2]);

            // Fresh EntityStore holder
            Object holder = newEntityHolder.invoke(entityStoreRegistry);

            // BlockEntity component — tells the client which block to render
            Object blockEnt = blockEntityCtor.newInstance(b.blockTypeId());
            holderAddComponent.invoke(holder, blockEntityType.invoke(null), blockEnt);

            // TransformComponent — world position + body orientation
            Object transform = transformCtor.newInstance(pos, rot);
            holderAddComponent.invoke(holder, transformType.invoke(null), transform);

            // HeadRotation — matches body rotation
            Object headRot = headRotCtor.newInstance(rot);
            holderAddComponent.invoke(holder, headRotType.invoke(null), headRot);

            return (Ref<EntityStore>) es.addEntity((Holder<EntityStore>) holder, AddReason.SPAWN);
        } catch (Throwable t) {
            System.out.println("[RotaryPivot] spawnBlockVisual: " + t);
            return null;
        }
    }

    private void updateEntityTransform(Store<EntityStore> es, Ref<EntityStore> ref,
                                        double x, double y, double z,
                                        float pitch, float yaw, float roll) {
        try {
            if (!resolveBlockRefl()) return;
            Object pos = vec3dCtor.newInstance(x, y, z);
            Object rot = vec3fCtor.newInstance(pitch, yaw, roll);

            ComponentType tType = (ComponentType) transformType.invoke(null);
            Component transform = es.getComponent(ref, tType);
            if (transform != null) {
                transformSetPos.invoke(transform, pos);
                transformSetRot.invoke(transform, rot);
                es.putComponent(ref, tType, transform);
            }

            ComponentType hType = (ComponentType) headRotType.invoke(null);
            Component head = es.getComponent(ref, hType);
            if (head != null) {
                headRotSetRot.invoke(head, rot);
                es.putComponent(ref, hType, head);
            }
        } catch (Throwable ignored) {}
    }

    // ── Block world helpers ───────────────────────────────────────────────────

    private static Store<EntityStore> getEntityStore(World world) {
        try { return world.getEntityStore().getStore(); } catch (Throwable t) { return null; }
    }

    // ── Reflection ───────────────────────────────────────────────────────────

    private static volatile Constructor<?> vec3dCtor;
    private static volatile Constructor<?> vec3fCtor;
    private static volatile Object         entityStoreRegistry;  // ComponentRegistry
    private static volatile Method         newEntityHolder;      // ComponentRegistry.newHolder()
    private static volatile Constructor<?> blockEntityCtor;      // BlockEntity(String)
    private static volatile Method         blockEntityType;      // BlockEntity.getComponentType()
    private static volatile Constructor<?> transformCtor;        // TransformComponent(V3d, V3f)
    private static volatile Method         transformType;        // TransformComponent.getComponentType()
    private static volatile Method         transformSetPos;      // .setPosition(V3d)
    private static volatile Method         transformSetRot;      // .setRotation(V3f)
    private static volatile Constructor<?> headRotCtor;          // HeadRotation(V3f)
    private static volatile Method         headRotType;          // HeadRotation.getComponentType()
    private static volatile Method         headRotSetRot;        // .setRotation(V3f)
    private static volatile Method         holderAddComponent;   // Holder.addComponent(ComponentType, Component)
    private static volatile boolean        blockReflResolved;

    private static boolean resolveBlockRefl() {
        if (blockReflResolved) return vec3dCtor != null;
        synchronized (RotaryPivotState.class) {
            if (blockReflResolved) return vec3dCtor != null;
            try {
                Class<?> v3d  = Class.forName("com.hypixel.hytale.math.vector.Vector3d");
                Class<?> v3f  = Class.forName("com.hypixel.hytale.math.vector.Vector3f");
                Class<?> beCls = Class.forName("com.hypixel.hytale.server.core.entity.entities.BlockEntity");
                Class<?> tcCls = Class.forName("com.hypixel.hytale.server.core.modules.entity.component.TransformComponent");
                Class<?> hrCls = Class.forName("com.hypixel.hytale.server.core.modules.entity.component.HeadRotation");
                Class<?> esCls = EntityStore.class;

                vec3dCtor = v3d.getConstructor(double.class, double.class, double.class);
                vec3fCtor = v3f.getConstructor(float.class, float.class, float.class);

                // EntityStore.REGISTRY → ComponentRegistry
                Field regField = esCls.getField("REGISTRY");
                entityStoreRegistry = regField.get(null);
                newEntityHolder = entityStoreRegistry.getClass().getMethod("newHolder");

                blockEntityCtor = beCls.getConstructor(String.class);
                blockEntityType = beCls.getMethod("getComponentType");

                transformCtor   = tcCls.getConstructor(v3d, v3f);
                transformType   = tcCls.getMethod("getComponentType");
                transformSetPos = tcCls.getMethod("setPosition", v3d);
                transformSetRot = tcCls.getMethod("setRotation", v3f);

                headRotCtor    = hrCls.getConstructor(v3f);
                headRotType    = hrCls.getMethod("getComponentType");
                headRotSetRot  = hrCls.getMethod("setRotation", v3f);

                holderAddComponent = Holder.class.getMethod("addComponent", ComponentType.class, Component.class);

                System.out.println("[RotaryPivot] Block visual reflection resolved.");
            } catch (Throwable t) {
                System.out.println("[RotaryPivot] resolveBlockRefl failed: " + t);
            }
            blockReflResolved = true;
        }
        return vec3dCtor != null;
    }

    // ── Math ─────────────────────────────────────────────────────────────────

    /** CW 90° arc position around the given axis at angle a (0 → π/2). */
    private static double[] arcPos(int dx, int dy, int dz, int axis, double a) {
        double cos = Math.cos(a), sin = Math.sin(a);
        return switch (axis) {
            case 0  -> new double[]{ dx,  dy * cos + dz * sin, -dy * sin + dz * cos };
            case 1  -> new double[]{ dx * cos + dz * sin,  dy, -dx * sin + dz * cos };
            default -> new double[]{ dx * cos - dy * sin,  dx * sin + dy * cos,  dz };
        };
    }

    /** Hytale rotation index → Euler radians (pitch, yaw, roll). S=0,W=1,N=2,E=3,Up=4,Down=5 */
    private static float[] indexToEulerRad(int index) {
        return switch (index) {
            case 1  -> new float[]{0f,                       (float)(Math.PI / 2),  0f};
            case 2  -> new float[]{0f,                       (float)(Math.PI),      0f};
            case 3  -> new float[]{0f,                       (float)(3 * Math.PI / 2), 0f};
            case 4  -> new float[]{(float)(-Math.PI / 2),   0f,                    0f};
            case 5  -> new float[]{(float)( Math.PI / 2),   0f,                    0f};
            default -> new float[]{0f,                       0f,                    0f};
        };
    }


    private static float[] arcOrientationEuler(int rotIndex, int axis, double angle) {
        float[] init = indexToEulerRad(rotIndex);
        org.joml.Quaternionf qInit = new org.joml.Quaternionf().rotateXYZ(init[0], init[1], init[2]);
        org.joml.Quaternionf qArc = new org.joml.Quaternionf();
        float a = (float) angle;
        switch (axis) {
            case 0  -> qArc.rotateX(-a);
            case 1  -> qArc.rotateY( a);
            default -> qArc.rotateZ( a);
        }
        org.joml.Quaternionf qs = qArc.mul(qInit);
        if (qs.w < 0) { qs.x = -qs.x; qs.y = -qs.y; qs.z = -qs.z; qs.w = -qs.w; }
        org.joml.Vector3f e = qs.getEulerAnglesXYZ(new org.joml.Vector3f());
        return new float[]{e.x, e.y, e.z};
    }

    private static int axisForFace(int face) {
        if (face == 0 || face == 1) return 0;
        if (face == 2 || face == 3) return 1;
        return 2;
    }

    // ── Position resolution ──────────────────────────────────────────────────

    private void resolvePosition(Store<ChunkStore> store, Ref<ChunkStore> myRef) {
        try {
            Class<?> sc = getClass().getSuperclass();
            if (sc != null) {
                for (String name : new String[]{"getBlockPosition","getPosition","getPos"}) {
                    try {
                        Method m = sc.getMethod(name);
                        Object r = m.invoke(this);
                        if (r instanceof Vector3i v && !(v.x == 0 && v.y == 0 && v.z == 0)) {
                            cachedPosition = v; positionResolved = true; return;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        resolvePositionFromStore(store, myRef);
    }

    private void resolvePositionFromStore(Store<ChunkStore> store, Ref<ChunkStore> myRef) {
        try {
            int myIdx = myRef.getIndex();
            ChunkStore cs = store.getExternalData();
            var chunks = cs.getChunkIndexes();
            if (chunks == null || chunks.isEmpty()) return;
            for (long chunkIdx : chunks) {
                Ref<ChunkStore> colRef = cs.getChunkReference(chunkIdx);
                if (colRef == null) continue;
                BlockComponentChunk bcc = store.getComponent(colRef, BlockComponentChunk.getComponentType());
                if (bcc == null) continue;
                java.util.Map<?, ?> refs = entityRefsViaReflection(bcc);
                if (refs == null) continue;
                for (java.util.Map.Entry<?, ?> e : refs.entrySet()) {
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
        } catch (Throwable ignored) {}
    }

    private static volatile Method  entityRefsMethod;
    private static volatile boolean entityRefsMethodResolved;

    private static java.util.Map<?, ?> entityRefsViaReflection(BlockComponentChunk bcc) {
        try {
            if (!entityRefsMethodResolved) {
                synchronized (RotaryPivotState.class) {
                    if (!entityRefsMethodResolved) {
                        for (Method m : bcc.getClass().getMethods()) {
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
            return r instanceof java.util.Map<?,?> map ? map : null;
        } catch (Throwable ignored) { return null; }
    }
}
