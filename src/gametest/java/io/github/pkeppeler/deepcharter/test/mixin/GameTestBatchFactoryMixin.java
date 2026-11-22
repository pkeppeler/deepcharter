package io.github.pkeppeler.deepcharter.test.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.pkeppeler.deepcharter.test.support.TestBatches;
import java.util.Map;
import net.minecraft.gametest.framework.GameTestBatchFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Replaces the 50 tests a batch holds ({@code MAX_TESTS_PER_BATCH}) with the cap of {@link TestBatches}. The server run batches through
 * {@code divideIntoBatches}, once per environment and dimension (the entry's key); the test command through {@code fromGameTestInfo()}.
 * Each holds the constant once, and the config's {@code defaultRequire} fails the launch if a Minecraft update moves one.
 */
@Mixin(value = GameTestBatchFactory.class, remap = false)
public abstract class GameTestBatchFactoryMixin {
    @ModifyExpressionValue(method = "lambda$divideIntoBatches$1", at = @At(value = "CONSTANT", args = "intValue=50"))
    private static int deepcharter$capServerBatch(int vanilla, @Local(argsOnly = true) Map.Entry<?, ?> batch) {
        return TestBatches.cap(String.valueOf(batch.getKey()));
    }

    @ModifyConstant(
            method = "fromGameTestInfo()Lnet/minecraft/gametest/framework/GameTestRunner$GameTestBatcher;",
            constant = @Constant(intValue = 50))
    private static int deepcharter$capCommandBatch(int vanilla) {
        return TestBatches.cap();
    }
}
