package io.github.pkeppeler.deepcharter.client.layer.mixin;

import io.github.pkeppeler.deepcharter.client.layer.SeamClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the background level of a seam (#1185, ADR 0089) from marking sections dirty in the renderer, which it shares with the level the
 * client shows. Target: the four methods of {@code ClientLevel} that pass a dirty mark on to the level extractor, at their heads;
 * {@code ClientChunkCacheSeamMixin} does the same for the light update of its chunk cache. The
 * background level is meshed by nothing until the client adopts it, and adopting it rebuilds the renderer from what it holds.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelSeamMixin {
    @Inject(
            method =
                    "sendBlockUpdated(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;I)V",
            at = @At("HEAD"),
            cancellable = true)
    private void deepcharter$quietBlockUpdated(
            BlockPos pos, BlockState old, BlockState current, int updateFlags, CallbackInfo ci) {
        quietIfBackground(ci);
    }

    @Inject(
            method =
                    "setBlocksDirty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void deepcharter$quietBlocksDirty(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        quietIfBackground(ci);
    }

    @Inject(method = "setSectionDirtyWithNeighbors(III)V", at = @At("HEAD"), cancellable = true)
    private void deepcharter$quietSectionDirty(int chunkX, int chunkY, int chunkZ, CallbackInfo ci) {
        quietIfBackground(ci);
    }

    @Inject(method = "setSectionRangeDirty(IIIIII)V", at = @At("HEAD"), cancellable = true)
    private void deepcharter$quietRangeDirty(
            int minSectionX,
            int minSectionY,
            int minSectionZ,
            int maxSectionX,
            int maxSectionY,
            int maxSectionZ,
            CallbackInfo ci) {
        quietIfBackground(ci);
    }

    private void quietIfBackground(CallbackInfo ci) {
        if (SeamClient.isBackground((ClientLevel) (Object) this)) {
            ci.cancel();
        }
    }
}
