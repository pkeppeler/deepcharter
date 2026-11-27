package io.github.pkeppeler.deepcharter.handbook.mixin;

import java.util.stream.Stream;
import net.minecraft.advancements.Advancement;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.server.ServerAdvancementManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Drops vanilla's advancements from the set the server loads, so none is tracked, granted or toasted and none unlocks a recipe.
 * The mod's directive advancements, and any other mod's, stay (ADR 0067).
 */
@Mixin(ServerAdvancementManager.class)
public abstract class ServerAdvancementManagerMixin {
    @Redirect(
            method = "<init>",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/core/HolderLookup$RegistryLookup;listElements()Ljava/util/stream/Stream;"))
    private Stream<Holder.Reference<Advancement>> deepcharter$withoutVanilla(
            HolderLookup.RegistryLookup<Advancement> advancements) {
        return advancements
                .listElements()
                .filter(holder -> !holder.key().identifier().getNamespace().equals(Identifier.DEFAULT_NAMESPACE));
    }
}
