package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets go of the chunks a test forced ({@link FarChunks#force}) when it finishes, however it ends: the runner keeps every finished
 * {@code GameTestInfo} until the run ends and has no hook of its own for a test's end that a test can reach (#569). Test code only.
 */
@Mixin(value = GameTestInfo.class, remap = false)
public abstract class GameTestInfoMixin {
    @Inject(method = "finish", at = @At("TAIL"))
    private void deepcharter$releaseForcedChunks(CallbackInfo ci) {
        GameTestInfo info = (GameTestInfo) (Object) this;
        AABB bounds;
        try {
            bounds = info.getStructureBounds();
        } catch (IllegalStateException noTestBlock) {
            // The runner finishes a test whose block entity is missing; it never ran its body, so it holds no chunk. Do
            // not mask its failure.
            return;
        }
        FarChunks.release(bounds);
    }
}
