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

/** Drops a pilot's report of where its pod is (ADR 0075). */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow
    public ServerPlayer player;

    /** After {@code ensureRunningOnSameThread}: the first call, on the network thread, throws there, so the decision is made on the server thread only. */
    @Inject(
            method = "handleMoveVehicle",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                            shift = At.Shift.AFTER),
            cancellable = true)
    private void deepcharter$podMovesOnTheServer(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
        if (player.getRootVehicle() instanceof PodEntity) {
            ci.cancel();
        }
    }
}
