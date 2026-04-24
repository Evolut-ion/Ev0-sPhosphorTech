package com.Ev0sMods.PhosphorTech.recipe;

import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;

/** {@link ProcessingRecipe} subclass for the Lathe/RodPuller — gives it a distinct class for the AssetRegistry. */
public final class LatheRecipe extends ProcessingRecipe {

    public static final AssetBuilderCodec<String, LatheRecipe> CODEC =
            ProcessingRecipe.buildCodec(LatheRecipe.class, LatheRecipe::new);

    public LatheRecipe() {}
}
