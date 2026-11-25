package io.github.pkeppeler.deepcharter.handbook.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.pkeppeler.deepcharter.handbook.ObtainableItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Leaves out of the server's recipes every recipe that needs or makes an item the campaign world does not yield, vanilla's
 * included, so none of them crafts, unlocks or reaches a recipe book (ADR 0093). Wraps the {@code RecipeMap.create} call in the
 * {@code RecipeManager} constructor ({@code <init>}), whose {@code HolderLookup.Provider} carries the reloaded item tags.
 */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {
    @WrapOperation(
            method = "<init>",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/item/crafting/RecipeMap;create(Lnet/minecraft/core/HolderLookup;)Lnet/minecraft/world/item/crafting/RecipeMap;"))
    private RecipeMap deepcharter$onlyObtainable(
            HolderLookup<Recipe<?>> recipes,
            Operation<RecipeMap> original,
            @Local(argsOnly = true) HolderLookup.Provider registries) {
        return original.call(ObtainableItems.loadable(recipes, registries));
    }
}
