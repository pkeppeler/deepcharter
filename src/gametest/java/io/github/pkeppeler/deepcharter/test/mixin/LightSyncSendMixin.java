package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.test.support.LightSyncSends;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells {@link LightSyncSends} what {@link LightSync} sends at its one send point, for {@code StructureLightBorderTest}. Inert unless the test watches the player. */
@Mixin(value = LightSync.class, remap = false)
public abstract class LightSyncSendMixin {
    @Inject(method = "sendLight", at = @At("HEAD"))
    private static void deepcharter$recordLightSent(
            ServerPlayer player, ClientboundLightUpdatePacket light, CallbackInfo ci) {
        LightSyncSends.record(player, light);
    }
}
