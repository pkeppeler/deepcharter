package io.github.pkeppeler.deepcharter.handbook.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.pkeppeler.deepcharter.handbook.ObtainableItems;
import java.util.Collection;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaves out of the server's recipes every recipe that needs or makes an item the campaign world does not yield,
 * vanilla's included, so none of them crafts, unlocks or reaches a recipe book (ADR 0093). The constructor
 * ({@code <init>}) builds the recipe map before the reloaded tags are bound to the items, so the
 * {@code RecipeMap.create} call there only keeps the recipe registry, and {@code finalizeRecipeLoading}, which runs
 * once the tags are bound and before the server crafts, sends or indexes a recipe, builds the map again without those
 * recipes. Fabric's recipe API copies the map at the end of the constructor, so its
 * {@code FabricRecipeManager.getSynchronizedRecipes()} still holds the left-out recipes (ADR 0093).
 */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {
    @Shadow
    @Final
    @Mutable
    private RecipeMap recipes;

    @SuppressWarnings("UnusedVariable") // written for the game to read
    @Shadow
    @Final
    @Mutable
    private Collection<RecipeHolder<?>> learnableRecipes;

    @Unique
    private @Nullable HolderLookup<Recipe<?>> deepcharter$registry;

    @WrapOperation(
            method = "<init>",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/item/crafting/RecipeMap;create(Lnet/minecraft/core/HolderLookup;)Lnet/minecraft/world/item/crafting/RecipeMap;"))
    private RecipeMap deepcharter$keepRegistry(HolderLookup<Recipe<?>> registry, Operation<RecipeMap> original) {
        deepcharter$registry = registry;
        return original.call(registry);
    }

    @Inject(method = "finalizeRecipeLoading", at = @At("HEAD"))
    private void deepcharter$onlyObtainable(FeatureFlagSet features, CallbackInfo ci) {
        recipes = RecipeMap.create(ObtainableItems.loadable(
                Objects.requireNonNull(deepcharter$registry, "the recipe registry is kept by the constructor")));
        // As the constructor sets it: every recipe but the special ones.
        learnableRecipes = recipes.values().stream()
                .filter(holder -> !holder.value().isSpecial())
                .toList();
    }
}
