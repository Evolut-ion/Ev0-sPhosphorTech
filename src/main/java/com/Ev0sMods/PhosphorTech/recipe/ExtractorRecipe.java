package com.Ev0sMods.PhosphorTech.recipe;

import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;

/** {@link ProcessingRecipe} subclass for the Extractor — gives it a distinct class for the AssetRegistry. */
public final class ExtractorRecipe extends ProcessingRecipe {

    public static final AssetBuilderCodec<String, ExtractorRecipe> CODEC =
            ProcessingRecipe.buildCodec(ExtractorRecipe.class, ExtractorRecipe::new);

    public ExtractorRecipe() {}
}
