package com.Ev0sMods.PhosphorTech.recipe;

import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;

/**
 * A single alloy-smelter recipe — two inputs, one or two outputs.
 *
 * <p>Implements {@link JsonAssetWithMap} so the Hytale Asset Editor can
 * create and modify recipes in-game via {@link #ASSET_STORE}.
 *
 * <h3>JSON format</h3>
 * Files live at {@code Server/Recipes/AlloySmelter/<name>.json}:
 * <pre>{@code
 * {
 *   "input1":    "Ingredient_Bar_Iron",
 *   "input2":    "Ingredient_Bar_Copper",
 *   "output1":   "Ingredient_Bar_Potin",
 *   "output1Qty": 2,
 *   "output2":   "Ingredient_Slag",
 *   "output2Qty": 1,
 *   "cfCost":    1500,
 *   "ticks":     120
 * }
 * }</pre>
 *
 * <p>{@code output2} and {@code output2Qty} are optional.
 */
public final class AlloySmelterRecipe
        implements JsonAssetWithMap<String, DefaultAssetMap<String, AlloySmelterRecipe>> {

    // ── Asset store & codec ───────────────────────────────────────────────────

    /** Asset store — assigned in {@code PhosphorTechPlugin.setup()}. */
    public static HytaleAssetStore<String, AlloySmelterRecipe,
            DefaultAssetMap<String, AlloySmelterRecipe>> ASSET_STORE;

    public static HytaleAssetStore<String, AlloySmelterRecipe,
            DefaultAssetMap<String, AlloySmelterRecipe>> getAssetStore() {
        return ASSET_STORE;
    }

    public static DefaultAssetMap<String, AlloySmelterRecipe> getAssetMap() {
        return ASSET_STORE.getAssetMap();
    }

    public static final AssetBuilderCodec<String, AlloySmelterRecipe> CODEC =
            AssetBuilderCodec.builder(
                    AlloySmelterRecipe.class,
                    AlloySmelterRecipe::new,
                    Codec.STRING,
                    (r, id) -> r.id = id,
                    r -> r.id,
                    (r, d)  -> r.extraInfo = d,
                    r -> r.extraInfo
            )
            .append(new KeyedCodec<>("Input1",    Codec.STRING),
                    (r, v) -> r.input1ItemId  = v, r -> r.input1ItemId).add()
            .append(new KeyedCodec<>("Input2",    Codec.STRING),
                    (r, v) -> r.input2ItemId  = v, r -> r.input2ItemId).add()
            .append(new KeyedCodec<>("Output1",   Codec.STRING),
                    (r, v) -> r.output1ItemId = v, r -> r.output1ItemId).add()
            .append(new KeyedCodec<>("Output1Qty", Codec.INTEGER, true),
                    (r, v) -> r.output1Qty    = (v != null) ? v : 1,
                    r -> r.output1Qty).add()
            .append(new KeyedCodec<>("Output2",   Codec.STRING, true),
                    (r, v) -> r.output2ItemId = v, r -> r.output2ItemId).add()
            .append(new KeyedCodec<>("Output2Qty", Codec.INTEGER, true),
                    (r, v) -> r.output2Qty    = (v != null) ? v : 1,
                    r -> r.output2Qty).add()
            .append(new KeyedCodec<>("CfCost",    Codec.INTEGER),
                    (r, v) -> r.cfCost        = v, r -> r.cfCost).add()
            .append(new KeyedCodec<>("Ticks",     Codec.INTEGER),
                    (r, v) -> r.tickDuration  = v, r -> r.tickDuration).add()
            .build();

    // ── Fields ────────────────────────────────────────────────────────────────

    protected String                id;
    protected AssetExtraInfo.Data   extraInfo;
    protected String                input1ItemId;
    protected String                input2ItemId;
    protected String                output1ItemId;
    protected int                   output1Qty      = 1;
    @Nullable protected String      output2ItemId;
    protected int                   output2Qty      = 1;
    protected int                   cfCost;
    protected int                   tickDuration;

    // ── Constructors ──────────────────────────────────────────────────────────

    /** No-arg constructor used by the codec deserialiser. */
    public AlloySmelterRecipe() {}

    /** Full constructor — mirrors the former record's canonical form. */
    public AlloySmelterRecipe(String input1ItemId, String input2ItemId,
                              String output1ItemId, int output1Qty,
                              @Nullable String output2ItemId, int output2Qty,
                              int cfCost, int tickDuration) {
        this.input1ItemId  = input1ItemId;
        this.input2ItemId  = input2ItemId;
        this.output1ItemId = output1ItemId;
        this.output1Qty    = output1Qty;
        this.output2ItemId = output2ItemId;
        this.output2Qty    = output2Qty;
        this.cfCost        = cfCost;
        this.tickDuration  = tickDuration;
    }

    // ── JsonAsset ─────────────────────────────────────────────────────────────

    @Override
    public String getId() { return id; }

    public void setId(String id) { this.id = id; }

    // ── Record-compatible accessors ───────────────────────────────────────────

    public String  input1ItemId()   { return input1ItemId; }
    public String  input2ItemId()   { return input2ItemId; }
    public String  output1ItemId()  { return output1ItemId; }
    public int     output1Qty()     { return output1Qty; }
    @Nullable public String output2ItemId() { return output2ItemId; }
    public int     output2Qty()     { return output2Qty; }
    public int     cfCost()         { return cfCost; }
    public int     tickDuration()   { return tickDuration; }

    /** Returns {@code true} if this recipe produces a second output item. */
    public boolean hasSecondOutput() {
        return output2ItemId != null && !output2ItemId.isBlank();
    }
}
