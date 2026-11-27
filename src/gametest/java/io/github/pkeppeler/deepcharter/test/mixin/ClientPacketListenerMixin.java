package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.ChunkPacketLog;
import io.github.pkeppeler.deepcharter.test.support.PacketJitter;
import io.github.pkeppeler.deepcharter.test.support.SwapProbes;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@link PacketJitter} hold back the packets that move entities (a relative move, a teleport, a position sync), to give a test the
 * irregular network a real connection has. Inert unless a test starts the jitter. Test code only.
 */
@Mixin(value = ClientPacketListener.class, remap = false)
public abstract class ClientPacketListenerMixin {
    @Shadow
    private ClientLevel level;

    /**
     * Logs each chunk packet the client handles, for {@link ChunkPacketLog}: after {@code ensureRunningOnSameThread}, so a packet that
     * arrives on the network thread is logged once, on the client thread, with the level it is applied to.
     */
    @Inject(
            method = "handleLevelChunkWithLight",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
                            shift = At.Shift.AFTER))
    private void deepcharter$logChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        ChunkPacketLog.record(level.dimension(), packet.x(), packet.z());
    }

    /** Counts each respawn the client handles, for {@link SwapProbes}: a swap handles none. */
    @Inject(
            method = "handleRespawn",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
                            shift = At.Shift.AFTER))
    private void deepcharter$countRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        SwapProbes.recordRespawn();
    }

    @Inject(method = "handleMoveEntity", at = @At("HEAD"), cancellable = true)
    private void deepcharter$jitterMove(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
        if (PacketJitter.skips(() -> ((ClientPacketListener) (Object) this).handleMoveEntity(packet))) {
            ci.cancel();
        }
    }

    /** A stall holds the speed the server reports with the moves, as a late server's packets arrive together; the jitter leaves it alone. */
    @Inject(method = "handleSetEntityMotion", at = @At("HEAD"), cancellable = true)
    private void deepcharter$stallMotion(ClientboundSetEntityMotionPacket packet, CallbackInfo ci) {
        if (PacketJitter.stalls(() -> ((ClientPacketListener) (Object) this).handleSetEntityMotion(packet))) {
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
