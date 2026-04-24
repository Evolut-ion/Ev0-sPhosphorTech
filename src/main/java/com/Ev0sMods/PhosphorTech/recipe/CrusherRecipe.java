package com.Ev0sMods.PhosphorTech.recipe;

import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;

/** {@link ProcessingRecipe} subclass for the Crusher — gives it a distinct class for the AssetRegistry. */
public final class CrusherRecipe extends ProcessingRecipe {

    public static final AssetBuilderCodec<String, CrusherRecipe> CODEC =
            ProcessingRecipe.buildCodec(CrusherRecipe.class, CrusherRecipe::new);

    public CrusherRecipe() {}
}
