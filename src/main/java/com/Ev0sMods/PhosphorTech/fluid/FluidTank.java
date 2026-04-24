package com.Ev0sMods.PhosphorTech.fluid;

/**
 * Mutable fluid storage: a type + amount combination.
 *
 * <p>Not thread-safe; callers are responsible for locking when needed.
 */
public final class FluidTank {

    private FluidType type;
    private int       stored;
    private final int capacity;

    public FluidTank(int capacity) {
        this.capacity = capacity;
        this.stored   = 0;
        this.type     = null;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public FluidType getType()     { return type;     }
    public int       getStored()   { return stored;   }
    public int       getCapacity() { return capacity; }
    public boolean   isEmpty()     { return stored == 0; }
    public int       getSpace()    { return capacity - stored; }

    /** True when the tank contains the specified type and has fluid. */
    public boolean containsFluid(FluidType ft) { return type == ft && stored > 0; }

    /** True when the tank can accept mB of the given type. */
    public boolean canAccept(FluidType ft) {
        return stored < capacity && (type == null || type == ft || stored == 0);
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    /**
     * Accept up to {@code amount} mB of the given type.
     *
     * @param simulate if true, does not modify state
     * @return mB actually accepted
     */
    public int accept(FluidType ft, int amount, boolean simulate) {
        if (amount <= 0) return 0;
        if (!canAccept(ft)) return 0;
        int actual = Math.min(amount, getSpace());
        if (!simulate) {
            if (stored == 0) type = ft;
            stored += actual;
        }
        return actual;
    }

    /**
     * Extract up to {@code amount} mB of the given type.
     *
     * @param simulate if true, does not modify state
     * @return mB actually extracted
     */
    public int extract(FluidType ft, int amount, boolean simulate) {
        if (amount <= 0) return 0;
        if (type != ft || stored == 0) return 0;
        int actual = Math.min(amount, stored);
        if (!simulate) {
            stored -= actual;
            if (stored == 0) type = null;
        }
        return actual;
    }

    /** Human-readable label, e.g. {@code "500 / 10000 mB (Steam)"}. */
    public String label() {
        String typeName = type != null ? type.getDisplayName() : "Empty";
        return stored + " / " + capacity + " mB (" + typeName + ")";
    }

    /** Percent full, 0–100. */
    public int pct() { return capacity == 0 ? 0 : (int)(100L * stored / capacity); }

    /** Fraction filled, 0f–1f. */
    public float fraction() { return capacity == 0 ? 0f : (float) stored / capacity; }
}
