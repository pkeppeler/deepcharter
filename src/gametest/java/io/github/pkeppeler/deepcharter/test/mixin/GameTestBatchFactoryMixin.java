package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.TestBatches;
import net.minecraft.gametest.framework.GameTestBatchFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Replaces the 50 tests a batch holds ({@code MAX_TESTS_PER_BATCH}) with {@link TestBatches#MAX_TESTS}. The server run batches through
 * {@code divideIntoBatches}, the test command through {@code fromGameTestInfo()}; each holds the constant once, and the config's
 * {@code defaultRequire} fails the launch if a Minecraft update moves one.
 */
@Mixin(value = GameTestBatchFactory.class, remap = false)
public abstract class GameTestBatchFactoryMixin {
    @ModifyConstant(method = "lambda$divideIntoBatches$1", constant = @Constant(intValue = 50))
    private static int deepcharter$capServerBatch(int vanilla) {
        return TestBatches.cap();
    }

    @ModifyConstant(
            method = "fromGameTestInfo()Lnet/minecraft/gametest/framework/GameTestRunner$GameTestBatcher;",
            constant = @Constant(intValue = 50))
    private static int deepcharter$capCommandBatch(int vanilla) {
        return TestBatches.cap();
    }
}
