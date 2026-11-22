package io.github.pkeppeler.deepcharter.pod.mixin;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops a pilot's report of where its pod is. Vanilla takes it for any vehicle the pilot controls, within ten blocks a tick, which
 * would let a modified client drive a pod several times faster than its engine. The server moves a pod from the pilot's input
 * ({@code PodMovement}) and a vanilla client never sends the report for one, so nothing legitimate is lost.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleMoveVehicle", at = @At("HEAD"), cancellable = true)
    private void deepcharter$podMovesOnTheServer(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
        if (player.getRootVehicle() instanceof PodEntity) {
            ci.cancel();
        }
    }
}
