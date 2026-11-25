package io.github.pkeppeler.deepcharter.layer.mixin;

import io.github.pkeppeler.deepcharter.layer.SeamSender;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells {@link SeamSender} a block changed, so a chunk sent ahead of a crossing and changed since is sent again at it (#1185, ADR 0089).
 * Target: {@code ServerChunkCache.blockChanged}, at its head, which vanilla calls for every block update a level broadcasts.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = "blockChanged(Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"))
    private void deepcharter$noteChange(BlockPos pos, CallbackInfo ci) {
        SeamSender.blockChanged(level, pos);
    }
}
