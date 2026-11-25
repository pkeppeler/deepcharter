package io.github.pkeppeler.deepcharter.layer.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps a vehicle tracked for its own riders while a send of its chunk is pending (#1302, ADR 0092). Target: the call to
 * {@code ChunkMap.isChunkTracked} in {@code ChunkMap$TrackedEntity.updatePlayer}. {@code isChunkTracked} is false while the
 * chunk is queued to be sent, which {@code LightSync} makes it for a chunk it re-sends round a carve; a pod ridden by that
 * player then dropped off the client, and a rider sends no move packet that would track it again.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin {
    @Shadow
    @Final
    Entity entity;

    @Redirect(
            method = "updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/level/ChunkMap;isChunkTracked(Lnet/minecraft/server/level/ServerPlayer;II)Z"))
    private boolean deepcharter$keepRiddenTracked(ChunkMap map, ServerPlayer player, int chunkX, int chunkZ) {
        if (entity.hasIndirectPassenger(player) && player.getChunkTrackingView().contains(chunkX, chunkZ)) {
            return true;
        }
        return map.isChunkTracked(player, chunkX, chunkZ);
    }
}
