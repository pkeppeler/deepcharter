package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.test.support.TestBatches;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Server GameTests for {@code GameTestBatchFactoryMixin} (#1026). */
public class TestBatchesTest {
    /**
     * The runner's batching asked for the cap before this test ran. Without it (the mixin dropped from the config, or its target gone
     * from the batching), the batches hold 50 tests and the suite's heap peak is the one {@code gameTestHeap} was not measured with.
     */
    @GameTest
    public void theRunnerBatchesWithTheCap(GameTestHelper helper) {
        Map<String, Integer> sized = TestBatches.sized();
        expect(
                helper,
                sized.values().stream().anyMatch(cap -> cap == TestBatches.MAX_TESTS),
                "the runner batched without TestBatches.cap, so a batch holds vanilla's 50 tests, not %s: %s",
                TestBatches.MAX_TESTS,
                sized);
        helper.succeed();
    }

    /**
     * A batch whose key names a campaign environment got the smaller cap, and no other did. A run of one test class sizes no campaign
     * environment, so this proves the key's form only in a full run.
     */
    @GameTest
    public void aCampaignEnvironmentGetsTheSmallerCap(GameTestHelper helper) {
        for (Map.Entry<String, Integer> sized : TestBatches.sized().entrySet()) {
            boolean campaign = TestBatches.campaignEnvironments().stream().anyMatch(sized.getKey()::contains);
            int expected = campaign ? TestBatches.MAX_CAMPAIGN_TESTS : TestBatches.MAX_TESTS;
            expect(
                    helper,
                    sized.getValue() == expected,
                    "the batch %s got the cap %s, not %s",
                    sized.getKey(),
                    sized.getValue(),
                    expected);
        }
        expect(
                helper,
                TestBatches.sized().keySet().stream()
                        .noneMatch(key -> key.contains("campaign_")
                                && !TestBatches.campaignEnvironments().stream().anyMatch(key::contains)),
                "a batch key names a campaign environment that CampaignEnvironments does not: %s",
                TestBatches.sized());
        helper.succeed();
    }
}
