package io.github.pkeppeler.deepcharter.layer.mixin;

import io.github.pkeppeler.deepcharter.layer.SeamSender;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the send of a chunk a seam crossing's rider holds already (#1185, ADR 0089). Target: {@code ChunkMap.markChunkPendingToSend(ServerPlayer,
 * ChunkPos)}, at its head, which vanilla calls for each chunk of the new view when the player joins the level.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @Inject(
            method =
                    "markChunkPendingToSend(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void deepcharter$skipPreSent(ServerPlayer player, ChunkPos pos, CallbackInfo ci) {
        if (SeamSender.takePreSent(player, level, pos)) {
            ci.cancel();
        }
    }
}
