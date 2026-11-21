package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.LightTrace;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds {@link LightTrace} the chunk, light and block packets the client handles, for the report of a stale client light. Test code only. */
@Mixin(value = ClientPacketListener.class, remap = false)
public abstract class ClientLightTraceMixin {
    @Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"))
    private void deepcharter$traceChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        LightTrace.record(
                "CLI",
                "CHUNK-RECEIVED",
                packet.x(),
                packet.z(),
                "sky sections " + packet.lightData().skyYMask().cardinality() + ", block sections "
                        + packet.lightData().blockYMask().cardinality());
    }

    @Inject(method = "handleLightUpdatePacket", at = @At("HEAD"))
    private void deepcharter$traceLight(ClientboundLightUpdatePacket packet, CallbackInfo ci) {
        LightTrace.record(
                "CLI",
                "LIGHT-RECEIVED",
                packet.x(),
                packet.z(),
                "sky sections " + packet.lightData().skyYMask().cardinality() + ", block sections "
                        + packet.lightData().blockYMask().cardinality());
    }

    @Inject(method = "handleForgetLevelChunk", at = @At("HEAD"))
    private void deepcharter$traceForget(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        LightTrace.record(
                "CLI", "CHUNK-FORGOTTEN", packet.pos().x(), packet.pos().z(), "");
    }
}
