package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.ForceSendProbe;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds {@link ForceSendProbe} each on-foot move packet and each force-send that passes vanilla's cooldown (#1317). */
@Mixin(value = ServerGamePacketListenerImpl.class, remap = false)
public abstract class ForceSendProbeMixin {
    @Shadow
    public ServerPlayer player;

    @Shadow
    private Vec3 awaitingPositionFromClient;

    @Inject(method = "handlePlayerPositionChange", at = @At("HEAD"))
    private void deepcharter$recordMove(
            double x,
            double y,
            double z,
            float yRot,
            float xRot,
            boolean onGround,
            boolean horizontalCollision,
            CallbackInfo info) {
        // Vanilla drops a packet that comes before the client accepts a teleport, and a rider's moves are its
        // vehicle's.
        if (awaitingPositionFromClient == null && !player.isPassenger()) {
            ForceSendProbe.move(player, new Vec3(x, y, z), onGround);
        }
    }

    @Inject(
            method = "forceSendPlayerSupportBlocks",
            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;info(Ljava/lang/String;Ljava/lang/Object;)V"))
    private void deepcharter$recordForceSend(CallbackInfo info) {
        ForceSendProbe.forceSend(player);
    }
}
