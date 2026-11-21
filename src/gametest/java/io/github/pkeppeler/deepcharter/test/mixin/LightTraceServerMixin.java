package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.test.support.LightTrace;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds {@link LightTrace} the holds and the light sends of {@link LightSync}, for the report of a stale client light. Test code only. */
@Mixin(value = LightSync.class, remap = false)
public abstract class LightTraceServerMixin {
    @Inject(
            method = "holdSendUntilLit(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;)V",
            at = @At("HEAD"))
    private static void deepcharter$traceHold(ServerLevel level, ChunkPos chunk, CallbackInfo ci) {
        LightTrace.record(
                "SRV",
                "HOLD-REQUESTED tick " + level.getServer().getTickCount(),
                chunk.x(),
                chunk.z(),
                level.dimension().identifier().toString());
    }

    @Inject(method = "sendLight", at = @At("HEAD"))
    private static void deepcharter$traceLight(
            ServerPlayer player, ClientboundLightUpdatePacket light, CallbackInfo ci) {
        LightTrace.record(
                "SRV",
                "LIGHT-SENT-AT-HOLD-END tick " + player.level().getServer().getTickCount(),
                light.x(),
                light.z(),
                "sky sections " + light.lightData().skyYMask().cardinality() + ", block sections "
                        + light.lightData().blockYMask().cardinality());
    }
}
