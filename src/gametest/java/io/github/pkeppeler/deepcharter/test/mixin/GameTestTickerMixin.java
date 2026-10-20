package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.PendingWork;
import java.util.Collection;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestTicker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Releases a done test's pending work however it finished: {@code GameTestInfo.tick} returns early for a test finished outside it. */
@Mixin(value = GameTestTicker.class, remap = false)
public abstract class GameTestTickerMixin {
    @Shadow
    @Final
    private Collection<GameTestInfo> testInfos;

    // After every info has ticked, so nothing clears a collection the info is iterating. The ticker drops done infos
    // next.
    @Inject(
            method = "tick",
            at = @At(value = "INVOKE", target = "Ljava/util/Collection;removeIf(Ljava/util/function/Predicate;)Z"))
    private void deepcharter$dropDonePendingWork(CallbackInfo ci) {
        for (GameTestInfo info : testInfos) {
            if (info.isDone()) {
                ((PendingWork) info).deepcharter$dropPendingWork();
            }
        }
    }
}
