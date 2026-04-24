package com.Ev0sMods.PhosphorTech.recipe;

import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;

/** {@link ProcessingRecipe} subclass for the Press — gives it a distinct class for the AssetRegistry. */
public final class PressRecipe extends ProcessingRecipe {

    public static final AssetBuilderCodec<String, PressRecipe> CODEC =
            ProcessingRecipe.buildCodec(PressRecipe.class, PressRecipe::new);

    public PressRecipe() {}
}
