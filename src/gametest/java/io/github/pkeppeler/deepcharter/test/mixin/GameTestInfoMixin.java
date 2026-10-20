package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.Collection;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets go of what a test holds when it finishes, however it ends: the runner keeps every finished {@code GameTestInfo} until the
 * run ends and has no hook of its own for a test's end that a test can reach. Test code only.
 * <ul>
 *   <li>The chunks it forced ({@link FarChunks#force}) (#569).</li>
 *   <li>Its scheduled callbacks and sequence events (#656). A callback still pending when the test ends, such as the next-tick
 *       entry of {@code TestTicks.each}, captures the test's mock players, and through them their {@code PlayerAdvancements}.</li>
 * </ul>
 */
@Mixin(value = GameTestInfo.class, remap = false)
public abstract class GameTestInfoMixin {
    @Shadow
    @Final
    private Collection<GameTestSequence> sequences;

    @Shadow
    @Final
    private Object2LongMap<Runnable> runAtTickTimeMap;

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

    // After the whole tick, so nothing clears a collection the runner is iterating. A test is done for good by then.
    @Inject(method = "tick", at = @At("TAIL"))
    private void deepcharter$dropPendingCallbacks(GameTestRunner runner, CallbackInfo ci) {
        if (((GameTestInfo) (Object) this).isDone()) {
            sequences.clear();
            runAtTickTimeMap.clear();
        }
    }
}
