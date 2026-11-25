package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.ForceSendProbe;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds {@link ForceSendProbe} each move packet and each force-send that passes vanilla's cooldown (#1317). */
@Mixin(value = ServerGamePacketListenerImpl.class, remap = false)
public abstract class ForceSendProbeMixin {
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
        ForceSendProbe.move(((ServerGamePacketListenerImpl) (Object) this).player, y, onGround);
    }

    @Inject(
            method = "forceSendPlayerSupportBlocks",
            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;info(Ljava/lang/String;Ljava/lang/Object;)V"))
    private void deepcharter$recordForceSend(CallbackInfo info) {
        ForceSendProbe.forceSend(((ServerGamePacketListenerImpl) (Object) this).player);
    }
}
