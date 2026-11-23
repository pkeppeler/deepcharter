package io.github.pkeppeler.deepcharter.test.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.pkeppeler.deepcharter.test.support.BatchKeyView;
import io.github.pkeppeler.deepcharter.test.support.TestBatches;
import java.util.List;
import java.util.Map;
import net.minecraft.gametest.framework.GameTestBatch;
import net.minecraft.gametest.framework.GameTestBatchFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces the 50 tests a batch holds ({@code MAX_TESTS_PER_BATCH}) with the cap of {@link TestBatches}, once per environment and
 * dimension (the entry's key), and records the batches that come out. The config's {@code defaultRequire} fails the launch if a
 * Minecraft update moves the constant.
 */
@Mixin(value = GameTestBatchFactory.class, remap = false)
public abstract class GameTestBatchFactoryMixin {
    @ModifyExpressionValue(method = "lambda$divideIntoBatches$1", at = @At(value = "CONSTANT", args = "intValue=50"))
    private static int deepcharter$capBatch(int vanilla, @Local(argsOnly = true) Map.Entry<?, ?> batch) {
        return TestBatches.cap((BatchKeyView) batch.getKey());
    }

    @Inject(method = "divideIntoBatches", at = @At("RETURN"))
    private static void deepcharter$recordBatches(CallbackInfoReturnable<List<GameTestBatch>> batches) {
        TestBatches.record(batches.getReturnValue());
    }
}
