package io.github.pkeppeler.deepcharter.handbook.mixin;

import io.github.pkeppeler.deepcharter.handbook.KnownRecipes;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps vanilla's recipe unlocks from leaking a recipe the player's charter does not know (ADR 0070). Targets
 * {@code ServerRecipeBook.addRecipes(Collection, ServerPlayer)}, which {@code /recipe give} and advancement rewards call, and
 * {@code ServerRecipeBook.sendInitialRecipeBook(ServerPlayer)}, which sends the whole book at login.
 */
@Mixin(ServerRecipeBook.class)
public abstract class ServerRecipeBookMixin {
    @Shadow
    public abstract int addRecipes(Collection<RecipeHolder<?>> recipes, ServerPlayer player);

    /** Passes on only the recipes the player knows. The recipes our own code adds never come through here. */
    @Inject(method = "addRecipes", at = @At("HEAD"), cancellable = true)
    private void deepcharter$onlyKnown(
            Collection<RecipeHolder<?>> recipes, ServerPlayer player, CallbackInfoReturnable<Integer> cir) {
        Set<Identifier> known = KnownRecipes.knownFor(player.level().getServer(), player.getUUID());
        List<RecipeHolder<?>> allowed = recipes.stream()
                .filter(recipe -> known.contains(recipe.id().identifier()))
                .toList();
        if (allowed.size() != recipes.size()) {
            cir.setReturnValue(addRecipes(allowed, player));
        }
    }

    /** Makes the book match what the player knows before it is sent, so a recipe saved in the player's data is not sent. */
    @Inject(method = "sendInitialRecipeBook", at = @At("HEAD"))
    private void deepcharter$conformBeforeSending(ServerPlayer player, CallbackInfo ci) {
        KnownRecipes.conform(player);
    }
}
