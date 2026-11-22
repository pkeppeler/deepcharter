package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.PacketJitter;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@link PacketJitter} hold back the packets that move entities (a relative move, a teleport, a position sync), to give a test the
 * irregular network a real connection has. Inert unless a test starts the jitter. Test code only.
 */
@Mixin(value = ClientPacketListener.class, remap = false)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleMoveEntity", at = @At("HEAD"), cancellable = true)
    private void deepcharter$jitterMove(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
        if (PacketJitter.skips(() -> ((ClientPacketListener) (Object) this).handleMoveEntity(packet))) {
            ci.cancel();
        }
    }

    @Inject(method = "handleTeleportEntity", at = @At("HEAD"), cancellable = true)
    private void deepcharter$jitterTeleport(ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
        if (PacketJitter.skips(() -> ((ClientPacketListener) (Object) this).handleTeleportEntity(packet))) {
            ci.cancel();
        }
    }

    @Inject(method = "handleEntityPositionSync", at = @At("HEAD"), cancellable = true)
    private void deepcharter$jitterSync(ClientboundEntityPositionSyncPacket packet, CallbackInfo ci) {
        if (PacketJitter.skips(() -> ((ClientPacketListener) (Object) this).handleEntityPositionSync(packet))) {
            ci.cancel();
        }
    }
}
