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
 * The surface rule's column writes go through {@link SurfaceWrites} (ADR 0090). The target is the anonymous {@code BlockColumn}
 * {@code MaterialSystem.buildSurface} hands the rule, {@code MaterialSystem$1}, and its {@code setBlock(int, BlockState)}. No
 * Fabric event reaches it: a rule writes a block per row, and the per-write bookkeeping of vanilla's path is a large part of layer 1's excess over a layer (#1160).
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
