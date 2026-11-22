package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.test.support.TestBatches;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Server GameTests for {@code GameTestBatchFactoryMixin} (#1026). */
public class TestBatchesTest {
    /**
     * No batch the runner made holds more tests than the cap of its environment. Without the mixin (dropped from the config, its
     * target gone, its value ignored) a batch holds vanilla's 50 and the heap peak is not the one {@code gameTestHeap} was measured
     * with. A run of a few tests makes no big batch, so this proves the cap only in a run of more than the cap.
     */
    @GameTest
    public void noBatchHoldsMoreThanItsCap(GameTestHelper helper) {
        Map<String, Integer> largest = TestBatches.largest();
        expect(helper, !largest.isEmpty(), "the batching recorded no batch");
        largest.forEach((environment, size) -> expect(
                helper,
                size <= TestBatches.limit(environment),
                "a batch of %s holds %s tests, over its cap of %s",
                environment,
                size,
                TestBatches.limit(environment)));
        helper.succeed();
    }
}
