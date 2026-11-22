package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.PacketJitter;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets {@link PacketJitter} hold back relative entity moves, to give a test the irregular network a real connection has. Inert unless a test starts the jitter. Test code only. */
@Mixin(value = ClientPacketListener.class, remap = false)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleMoveEntity", at = @At("HEAD"), cancellable = true)
    private void deepcharter$jitter(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
        if (PacketJitter.skips((ClientPacketListener) (Object) this, packet)) {
            ci.cancel();
        }
    }
}
