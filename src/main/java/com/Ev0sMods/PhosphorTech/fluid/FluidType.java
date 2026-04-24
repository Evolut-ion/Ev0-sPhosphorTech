package com.Ev0sMods.PhosphorTech.fluid;

/**
 * All fluid types that PhosphorTech pipes and tanks can handle.
 *
 * <p>Pipe types control which fluids they are allowed to transfer —
 * see {@link PipeType#getAllowedFluids()}.
 *
 * <p>Oil variants are reserved at the end of the enum; concrete sub-types
 * (e.g. CRUDE_OIL, REFINED_OIL) will replace OIL_PLACEHOLDER when defined.
 */
public enum FluidType {

    // ── Standard fluids ───────────────────────────────────────────────────────
    WATER       ("Water",      "#2196f3"),
    STEAM       ("Steam",      "#b0bec5"),
    LAVA        ("Lava",       "#ff6f00"),
    TAR         ("Tar",        "#212121"),
    SAP         ("Sap",        "#a5d6a7"),
    CREOSOTE    ("Creosote",   "#558b2f"),

    // ── Oil (to be expanded) ──────────────────────────────────────────────────
    OIL_PLACEHOLDER("Oil",    "#4a148c");

    // ── Metadata ──────────────────────────────────────────────────────────────
    private final String displayName;
    private final String hexColor;

    FluidType(String displayName, String hexColor) {
        this.displayName = displayName;
        this.hexColor    = hexColor;
    }

    public String getDisplayName() { return displayName; }
    /** Hex colour string (e.g. {@code "#2196f3"}) for UI rendering. */
    public String getHexColor()    { return hexColor;    }

    /** True if this type is a variant of oil (for future extensibility). */
    public boolean isOil() { return this == OIL_PLACEHOLDER; }
}
