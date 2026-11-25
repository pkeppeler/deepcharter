package io.github.pkeppeler.deepcharter.client.layer.mixin;

import io.github.pkeppeler.deepcharter.client.layer.SeamClient;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the light of the background level of a seam (#1185, ADR 0089) from dirtying the shown level's sections. Target:
 * {@code ClientChunkCache.onLightUpdate}, at its head, which marks the section dirty in the client's one level extractor, whichever
 * level's light engine ran. Light at the same x and z as a held chunk would otherwise remesh the shown level's section there.
 */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheSeamMixin {
    @Shadow
    @Final
    private ClientLevel level;

    @Inject(
            method = "onLightUpdate(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/SectionPos;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void deepcharter$quietLightUpdate(LightLayer layer, SectionPos pos, CallbackInfo ci) {
        if (SeamClient.isBackground(level)) {
            ci.cancel();
        }
    }
}
