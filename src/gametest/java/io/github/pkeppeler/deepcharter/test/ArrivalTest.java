package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.test.support.Arrival;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Server GameTests for {@link Arrival}; none needs the world, they only need the runner. */
public class ArrivalTest {
    private static Arrival<String> pod() {
        return new Arrival<>("pod", "the far chunk ticks");
    }

    @GameTest
    public void getBeforeSetThrowsAndNamesTheValueAndTheStep(GameTestHelper helper) {
        try {
            pod().get();
        } catch (IllegalStateException expected) {
            String message = String.valueOf(expected.getMessage());
            if (!message.contains("pod") || !message.contains("the far chunk ticks")) {
                throw helper.assertionException("the message names neither the value nor the step: " + message);
            }
            helper.succeed();
            return;
        }
        throw helper.assertionException("get() before set() should throw IllegalStateException");
    }

    @GameTest
    public void getAfterSetReturnsTheValue(GameTestHelper helper) {
        Arrival<String> arrival = pod();
        arrival.set("mole");
        if (!"mole".equals(arrival.get()) || !arrival.isPresent()) {
            throw helper.assertionException("get() after set() did not return the value");
        }
        helper.succeed();
    }

    @GameTest
    public void aSecondSetThrowsAndKeepsTheFirstValue(GameTestHelper helper) {
        Arrival<String> arrival = pod();
        arrival.set("mole");
        try {
            arrival.set("other");
        } catch (IllegalStateException expected) {
            if (!"mole".equals(arrival.get())) {
                throw helper.assertionException("the failed second set replaced the value");
            }
            helper.succeed();
            return;
        }
        throw helper.assertionException("a second set() should throw IllegalStateException");
    }

    @GameTest
    public void replaceOverwritesAnArrivedValueAndThrowsBeforeArrival(GameTestHelper helper) {
        Arrival<String> arrival = pod();
        try {
            arrival.replace("early");
            throw helper.assertionException("replace() before set() should throw IllegalStateException");
        } catch (IllegalStateException expected) {
            // expected
        }
        arrival.set("mole");
        arrival.replace("other");
        if (!"other".equals(arrival.get())) {
            throw helper.assertionException("replace() did not overwrite the value");
        }
        helper.succeed();
    }

    @GameTest
    public void ifPresentSkipsBeforeArrivalAndRunsAfter(GameTestHelper helper) {
        Arrival<String> arrival = pod();
        List<String> seen = new ArrayList<>();
        arrival.ifPresent(seen::add);
        if (arrival.isPresent() || !seen.isEmpty()) {
            throw helper.assertionException("ifPresent() ran before the value arrived: " + seen);
        }
        arrival.set("mole");
        arrival.ifPresent(seen::add);
        if (!List.of("mole").equals(seen)) {
            throw helper.assertionException("ifPresent() should run once with the value, saw " + seen);
        }
        helper.succeed();
    }
}
