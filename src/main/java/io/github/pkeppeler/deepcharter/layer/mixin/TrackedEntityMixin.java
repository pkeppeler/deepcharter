package io.github.pkeppeler.deepcharter.layer.mixin;

import java.util.Set;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps a vehicle tracked for its own riders while a send of its chunk is pending (#1302, ADR 0092). Target: the call
 * to {@code ChunkMap.isChunkTracked} in {@code ChunkMap$TrackedEntity.updatePlayer}. {@code isChunkTracked} is false
 * while the chunk is queued to be sent, which {@code LightSync} makes it for a chunk it re-sends round a carve. A pod
 * that changes section then was untracked for its own rider: the client got a remove-entity, dropped the pod and
 * unmounted the pilot. The rider's next move packet ({@code ChunkMap.move}) tracks it again, but only once the chunk
 * is sent, so the pod is gone for the whole wait and comes back with a fresh add and mount.
 *
 * <p>Only a vehicle the rider is tracking already stays tracked: one whose chunk was never sent is added when the chunk
 * is, as vanilla does, so no entity reaches a client before the chunk it stands in.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin {
    @Shadow
    @Final
    Entity entity;

    @Shadow
    @Final
    Set<ServerPlayerConnection> seenBy;

    @Redirect(
            method = "updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/level/ChunkMap;isChunkTracked(Lnet/minecraft/server/level/ServerPlayer;II)Z"))
    private boolean deepcharter$keepRiddenTracked(ChunkMap map, ServerPlayer player, int chunkX, int chunkZ) {
        if (seenBy.contains(player.connection)
                && entity.hasIndirectPassenger(player)
                && player.getChunkTrackingView().contains(chunkX, chunkZ)) {
            return true;
        }
        return map.isChunkTracked(player, chunkX, chunkZ);
    }
}
