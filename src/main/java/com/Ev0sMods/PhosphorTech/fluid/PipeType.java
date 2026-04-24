package com.Ev0sMods.PhosphorTech.fluid;

import java.util.Set;

/**
 * Defines the properties of each pipe material.
 *
 * <p>Transfer rates and fluid restrictions:
 * <table border="1" cellpadding="4">
 *   <tr><th>Type</th><th>Rate (mB)</th><th>Interval (ticks)</th><th>Lava?</th></tr>
 *   <tr><td>WOODEN</td><td>500</td><td>5</td><td>No</td></tr>
 *   <tr><td>CLAY</td><td>1000</td><td>5</td><td>No</td></tr>
 *   <tr><td>COPPER</td><td>1000</td><td>2</td><td>No</td></tr>
 *   <tr><td>POTIN</td><td>1000</td><td>5</td><td>Yes</td></tr>
 * </table>
 */
public enum PipeType {

    WOODEN("Wooden Pipe",
            500, 5,
            Set.of(FluidType.WATER, FluidType.STEAM, FluidType.TAR,
                   FluidType.SAP, FluidType.CREOSOTE, FluidType.OIL_PLACEHOLDER),
            300.0),

    CLAY("Clay Pipe",
            1000, 5,
            Set.of(FluidType.WATER, FluidType.STEAM, FluidType.TAR,
                   FluidType.SAP, FluidType.CREOSOTE, FluidType.OIL_PLACEHOLDER),
            600.0),

    COPPER("Copper Pipe",
            1000, 2,
            Set.of(FluidType.WATER, FluidType.STEAM, FluidType.TAR,
                   FluidType.SAP, FluidType.CREOSOTE, FluidType.OIL_PLACEHOLDER),
            900.0),

    POTIN("Potin Pipe",
            1000, 5,
            Set.of(FluidType.values()), // all fluids, including lava
            1200.0);

    // ── Fields ────────────────────────────────────────────────────────────────

    private final String      displayName;
    /** mB transferred per interval. */
    private final int         transferAmount;
    /** Server ticks between transfer events. */
    private final int         transferInterval;
    private final Set<FluidType> allowedFluids;
    /** Maximum safe temperature this pipe material can hold, in °C. */
    private final double      maxHeatCelsius;

    PipeType(String displayName, int transferAmount, int transferInterval,
             Set<FluidType> allowedFluids, double maxHeatCelsius) {
        this.displayName      = displayName;
        this.transferAmount   = transferAmount;
        this.transferInterval = transferInterval;
        this.allowedFluids    = allowedFluids;
        this.maxHeatCelsius   = maxHeatCelsius;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String           getDisplayName()      { return displayName;      }
    public int              getTransferAmount()   { return transferAmount;   }
    public int              getTransferInterval() { return transferInterval; }
    public Set<FluidType>   getAllowedFluids()     { return allowedFluids;    }
    public double           getMaxHeatCelsius()   { return maxHeatCelsius;   }

    public boolean canTransfer(FluidType type) { return allowedFluids.contains(type); }
}
