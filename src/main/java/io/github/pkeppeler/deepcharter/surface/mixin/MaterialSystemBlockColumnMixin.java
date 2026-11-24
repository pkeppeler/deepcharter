package io.github.pkeppeler.deepcharter.surface.mixin;

import io.github.pkeppeler.deepcharter.surface.SurfaceWrites;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Routes the surface rule's block writes through {@link SurfaceWrites}; no Fabric event reaches them. Target and reasoning:
 * [ADR 0090](../../../../../../../../../../docs/adr/0090-the-surface-rule-writes-a-block-for-a-block-of-the-same-bookkeeping-straight-into-the-section.md).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.material.MaterialSystem$1")
public abstract class MaterialSystemBlockColumnMixin {
    @Redirect(
            method = "setBlock(ILnet/minecraft/world/level/block/state/BlockState;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/chunk/ChunkAccess;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private @Nullable BlockState deepcharter$writeNeutrally(ChunkAccess chunk, BlockPos pos, BlockState state) {
        return SurfaceWrites.set(chunk, pos, state);
    }
}
