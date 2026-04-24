package com.Ev0sMods.PhosphorTech.recipe;

import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;

/** {@link ProcessingRecipe} subclass for the Centrifuge — gives it a distinct class for the AssetRegistry. */
public final class CentrifugeRecipe extends ProcessingRecipe {

    public static final AssetBuilderCodec<String, CentrifugeRecipe> CODEC =
            ProcessingRecipe.buildCodec(CentrifugeRecipe.class, CentrifugeRecipe::new);

    public CentrifugeRecipe() {}
}
