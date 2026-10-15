package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Server GameTests for {@link TestTicks}. */
public class TestTicksTest {
    private static final int LAST_TICK = 10;
    private static final String NESTED_MESSAGE =
            "TestTicks.each must be called from the test method, not from a tick callback";

    private static void assertRanThrough(GameTestHelper helper, List<Long> ticks, int last) {
        List<Long> expected =
                IntStream.rangeClosed(0, last).mapToObj(Long::valueOf).toList();
        if (!expected.equals(ticks)) {
            throw helper.assertionException("body ran on ticks " + ticks + ", expected " + expected);
        }
    }

    @GameTest
    public void bodyRunsOnEveryTick(GameTestHelper helper) {
        List<Long> ticks = new ArrayList<>();
        TestTicks.each(helper, () -> ticks.add(helper.getTick()));
        helper.succeedOnTickWhen(LAST_TICK, () -> assertRanThrough(helper, ticks, LAST_TICK));
    }

    @GameTest
    public void bodyRunsWithASucceedWhen(GameTestHelper helper) {
        List<Long> ticks = new ArrayList<>();
        TestTicks.each(helper, () -> ticks.add(helper.getTick()));
        helper.succeedWhen(() -> {
            if (helper.getTick() < LAST_TICK) {
                throw helper.assertionException("waiting");
            }
            assertRanThrough(helper, ticks, (int) helper.getTick());
        });
    }

    @GameTest
    public void bodyRunsWithAnOwnSequence(GameTestHelper helper) {
        List<Long> ticks = new ArrayList<>();
        TestTicks.each(helper, () -> ticks.add(helper.getTick()));
        helper.startSequence()
                .thenIdle(LAST_TICK)
                .thenExecute(() -> assertRanThrough(helper, ticks, (int) helper.getTick()))
                .thenSucceed();
    }

    @GameTest
    public void callingFromATickCallbackThrows(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            try {
                TestTicks.each(helper, () -> {});
            } catch (IllegalStateException expected) {
                if (!NESTED_MESSAGE.equals(expected.getMessage())) {
                    throw helper.assertionException("wrong message: " + expected.getMessage());
                }
                helper.succeed();
                return;
            }
            throw helper.assertionException("a nested call should throw IllegalStateException");
        });
    }
}
