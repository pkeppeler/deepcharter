package io.github.pkeppeler.deepcharter.layer.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import io.github.pkeppeler.deepcharter.layer.gen.StrataPrefill;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the campaign's noise fill write layer 1's strata rock in place of its default block ({@link StrataPrefill},
 * #1472). Target: {@code NoiseBasedChunkGenerator.doFill(NoiseChunk, ChunkAccess)}, its head (the generator's prefill,
 * once a chunk) and its one call to {@code Aquifer.computeSubstance(IIID)}, whose null is a solid block. Why the world
 * stays the same: ADR 0090's amendment.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    @Inject(method = "doFill", at = @At("HEAD"))
    private void deepcharter$planPrefill(
            NoiseChunk noiseChunk,
            ChunkAccess chunk,
            CallbackInfo ci,
            @Share("prefill") LocalRef<@Nullable StrataPrefill> prefill) {
        prefill.set(StrataPrefill.of(((NoiseBasedChunkGenerator) (Object) this).generatorSettings()));
    }

    @Redirect(
            method = "doFill",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/levelgen/Aquifer;computeSubstance(IIID)Lnet/minecraft/world/level/block/state/BlockState;"))
    private @Nullable BlockState deepcharter$prefill(
            Aquifer aquifer,
            int x,
            int y,
            int z,
            double density,
            @Share("prefill") LocalRef<@Nullable StrataPrefill> prefill) {
        BlockState substance = aquifer.computeSubstance(x, y, z, density);
        StrataPrefill plan = prefill.get();
        return substance == null && plan != null ? plan.at(y) : substance;
    }
}
