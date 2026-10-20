package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.PendingWork;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.Collection;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The runner retains finished infos, so clear what their callbacks capture; the ticker calls this for every done info. */
@Mixin(value = GameTestInfo.class, remap = false)
public abstract class GameTestInfoMixin implements PendingWork {
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

    @Override
    public void deepcharter$dropPendingWork() {
        sequences.clear();
        runAtTickTimeMap.clear();
    }
}
