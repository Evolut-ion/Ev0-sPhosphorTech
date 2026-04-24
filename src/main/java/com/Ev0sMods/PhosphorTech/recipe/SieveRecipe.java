package com.Ev0sMods.PhosphorTech.recipe;

import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;

/** {@link ProcessingRecipe} subclass for the Sieve — gives it a distinct class for the AssetRegistry. */
public final class SieveRecipe extends ProcessingRecipe {

    public static final AssetBuilderCodec<String, SieveRecipe> CODEC =
            ProcessingRecipe.buildCodec(SieveRecipe.class, SieveRecipe::new);

    public SieveRecipe() {}
}
