package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.LightTrace;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds {@link LightTrace} the chunks the server marks pending and sends, for the report of a stale client light. Test code only. */
@Mixin(value = PlayerChunkSender.class, remap = false)
public abstract class PlayerChunkSenderTraceMixin {
    @Inject(method = "markChunkPendingToSend", at = @At("HEAD"))
    private void deepcharter$tracePending(LevelChunk chunk, CallbackInfo ci) {
        LightTrace.record(
                "SRV", "CHUNK-PENDING", chunk.getPos().x(), chunk.getPos().z(), "");
    }

    @Inject(method = "sendChunk", at = @At("HEAD"))
    private static void deepcharter$traceSend(
            ServerGamePacketListenerImpl listener, ServerLevel level, LevelChunk chunk, CallbackInfo ci) {
        LightTrace.record(
                "SRV",
                "CHUNK-SENT tick " + level.getServer().getTickCount(),
                chunk.getPos().x(),
                chunk.getPos().z(),
                "");
    }
}
