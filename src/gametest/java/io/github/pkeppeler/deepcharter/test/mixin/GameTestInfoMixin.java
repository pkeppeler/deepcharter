package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.BlockingLoads;
import io.github.pkeppeler.deepcharter.test.support.ColumnCost;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.ItemSpawns;
import io.github.pkeppeler.deepcharter.test.support.PendingWork;
import io.github.pkeppeler.deepcharter.test.support.SlowTests;
import io.github.pkeppeler.deepcharter.test.support.TestCleanups;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.Collection;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The runner retains finished infos, so clear what their callbacks capture; the ticker calls this for every done info. Also names the
 * tests that hold the server thread ({@link SlowTests}).
 */
@Mixin(value = GameTestInfo.class, remap = false)
public abstract class GameTestInfoMixin implements PendingWork {
    @Shadow
    @Final
    private Collection<GameTestSequence> sequences;

    @Shadow
    @Final
    private Object2LongMap<Runnable> runAtTickTimeMap;

    @Unique
    private long deepcharter$tickStartNanos;

    @Inject(method = "tick", at = @At("HEAD"))
    private void deepcharter$startTick(GameTestRunner runner, CallbackInfo ci) {
        deepcharter$tickStartNanos = System.nanoTime();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void deepcharter$reportSlowTick(GameTestRunner runner, CallbackInfo ci) {
        SlowTests.tick((GameTestInfo) (Object) this, System.nanoTime() - deepcharter$tickStartNanos);
    }

    @Inject(method = "finish", at = @At("TAIL"))
    private void deepcharter$reportSlowTest(CallbackInfo ci) {
        SlowTests.finished((GameTestInfo) (Object) this);
    }

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
        BlockingLoads.release(bounds);
        ItemSpawns.release(bounds);
        TestCleanups.run(bounds);
        ColumnCost.release(bounds);
    }

    @Override
    public void deepcharter$dropPendingWork() {
        sequences.clear();
        runAtTickTimeMap.clear();
    }
}
