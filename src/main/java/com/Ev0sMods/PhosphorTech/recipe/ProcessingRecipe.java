package com.Ev0sMods.PhosphorTech.recipe;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;

/**
 * A single processing recipe (Crusher / Extractor / Centrifuge / Sieve).
 *
 * <p>Implements {@link JsonAssetWithMap} so that the Hytale Asset Editor can
 * create and modify recipes in-game. Each machine registers its own
 * {@code HytaleAssetStore} (held on {@link ProcessingRecipeRegistry}) using
 * the shared {@link #CODEC}.
 *
 * <p>The asset ID is derived from the file name (the engine sets it via
 * {@link #setId}). For backward compatibility the class exposes record-style
 * accessor methods so existing call sites need no changes.
 */
public class ProcessingRecipe
        implements JsonAssetWithMap<String, DefaultAssetMap<String, ProcessingRecipe>> {

    // ── Asset codec factory ─────────────────────────────────────────────────

    /**
     * Builds the shared codec for any ProcessingRecipe subclass.
     * Each machine creates its own typed codec via this factory so that
     * the AssetRegistry (which keys stores by class) sees distinct types.
     */
    public static <T extends ProcessingRecipe> AssetBuilderCodec<String, T> buildCodec(
            Class<T> clazz, Supplier<T> factory) {
        return AssetBuilderCodec.builder(
                clazz, factory,
                Codec.STRING,
                (r, id) -> r.id = id,
                r -> r.id,
                (r, d)  -> r.extraInfo = d,
                r -> r.extraInfo
        )
        .append(new KeyedCodec<>("Input",       Codec.STRING),
                (r, v) -> r.inputItemId     = v,  r -> r.inputItemId).add()
        .append(new KeyedCodec<>("InputQty",    Codec.INTEGER, true),
                (r, v) -> r.inputQty        = (v != null && v > 0) ? v : 1,
                r -> r.inputQty).add()
        .append(new KeyedCodec<>("Output",      Codec.STRING),
                (r, v) -> r.outputItemId    = v,  r -> r.outputItemId).add()
        .append(new KeyedCodec<>("OutputQty",   Codec.INTEGER, true),
                (r, v) -> r.outputQty       = (v != null) ? v : 1,
                r -> r.outputQty).add()
        .append(new KeyedCodec<>("CfCost",      Codec.INTEGER),
                (r, v) -> r.cfCost          = v,  r -> r.cfCost).add()
        .append(new KeyedCodec<>("Ticks",       Codec.INTEGER),
                (r, v) -> r.tickDuration    = v,  r -> r.tickDuration).add()
        .append(new KeyedCodec<>("BonusOutput", Codec.STRING, true),
                (r, v) -> r.bonusOutputItemId = v, r -> r.bonusOutputItemId).add()
        .append(new KeyedCodec<>("BonusQty",    Codec.INTEGER, true),
                (r, v) -> r.bonusOutputQty  = (v != null) ? v : 1,
                r -> r.bonusOutputQty).add()
        .append(new KeyedCodec<>("BonusChance", Codec.FLOAT, true),
                (r, v) -> r.bonusChance     = (v != null) ? v : 0f,
                r -> r.bonusChance).add()
        .build();
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    protected String                  id;
    protected AssetExtraInfo.Data     extraInfo;
    protected String                  inputItemId;
    protected int                     inputQty        = 1;
    protected String                  outputItemId;
    protected int                     cfCost;
    protected int                     tickDuration;
    protected int                     outputQty       = 1;
    @Nullable protected String        bonusOutputItemId;
    protected int                     bonusOutputQty  = 1;
    protected float                   bonusChance;
    @Nullable protected String        bonusOutputItemId2;
    protected int                     bonusOutputQty2 = 1;
    protected float                   bonusChance2;
    @Nullable protected String        bonusOutputItemId3;
    protected int                     bonusOutputQty3 = 1;
    protected float                   bonusChance3;
    @Nullable protected String        bonusOutputItemId4;
    protected int                     bonusOutputQty4 = 1;
    protected float                   bonusChance4;

    // ── Constructors ──────────────────────────────────────────────────────────

    /** No-arg constructor used by the codec deserialiser. */
    public ProcessingRecipe() {}

    /** Full constructor — mirrors the former record's canonical form. */
    public ProcessingRecipe(String inputItemId, String outputItemId,
                            int cfCost, int tickDuration, int outputQty,
                            @Nullable String bonusOutputItemId,
                            int bonusOutputQty, float bonusChance) {
        this.inputItemId      = inputItemId;
        this.outputItemId     = outputItemId;
        this.cfCost           = cfCost;
        this.tickDuration     = tickDuration;
        this.outputQty        = outputQty;
        this.bonusOutputItemId = bonusOutputItemId;
        this.bonusOutputQty   = bonusOutputQty;
        this.bonusChance      = bonusChance;
    }

    /** Convenience constructor — no bonus output. */
    public ProcessingRecipe(String inputItemId, String outputItemId,
                            int cfCost, int tickDuration, int outputQty) {
        this(inputItemId, outputItemId, cfCost, tickDuration, outputQty, null, 1, 0f);
    }

    // ── JsonAsset ─────────────────────────────────────────────────────────────

    @Override
    public String getId() { return id; }

    public void setId(String id) { this.id = id; }

    // ── Record-compatible accessors (keeps all existing call sites intact) ────

    public String  inputItemId()        { return inputItemId; }
    public int     inputQty()           { return inputQty; }
    public String  outputItemId()       { return outputItemId; }
    public int     cfCost()             { return cfCost; }
    public int     tickDuration()       { return tickDuration; }
    public int     outputQty()          { return outputQty; }
    @Nullable public String bonusOutputItemId() { return bonusOutputItemId; }
    public int     bonusOutputQty()     { return bonusOutputQty; }
    public float   bonusChance()        { return bonusChance; }

    /** Returns {@code true} when this recipe has a chance-based bonus output. */
    public boolean hasBonus() {
        return bonusOutputItemId != null && bonusChance > 0f;
    }

    /** Returns all bonus entries (slots 1–4) as an immutable list. */
    public java.util.List<BonusEntry> allBonuses() {
        var list = new java.util.ArrayList<BonusEntry>(4);
        if (bonusOutputItemId  != null && bonusChance  > 0f) list.add(new BonusEntry(bonusOutputItemId,  bonusOutputQty,  bonusChance));
        if (bonusOutputItemId2 != null && bonusChance2 > 0f) list.add(new BonusEntry(bonusOutputItemId2, bonusOutputQty2, bonusChance2));
        if (bonusOutputItemId3 != null && bonusChance3 > 0f) list.add(new BonusEntry(bonusOutputItemId3, bonusOutputQty3, bonusChance3));
        if (bonusOutputItemId4 != null && bonusChance4 > 0f) list.add(new BonusEntry(bonusOutputItemId4, bonusOutputQty4, bonusChance4));
        return java.util.Collections.unmodifiableList(list);
    }

    /** A single chance-based bonus output entry. */
    public record BonusEntry(String itemId, int qty, float chance) {}
}
