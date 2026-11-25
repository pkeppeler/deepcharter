package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceFragments;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceTier;
import io.github.pkeppeler.deepcharter.sound.ambience.ClockCaller;
import io.github.pkeppeler.deepcharter.sound.ambience.ClockCaller.Call;
import io.github.pkeppeler.deepcharter.sound.ambience.ClockCaller.Kind;
import io.github.pkeppeler.deepcharter.sound.ambience.ClockTuning;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Server GameTests for #938: when the punch clock calls an hour and which. The caller is pure (a seeded random in its seam), so each rule
 * runs over days of ticks in a moment: it is rare, it is near-only, it never repeats a call, it is sometimes wrong and sometimes skips the
 * world's hour, a due call waits for anything sounding, and the dial maths and the sound events agree.
 */
public class ClockCallerTest {
    private static final int DAYS = 200;
    private static final long TICKS = DAYS * 24_000L;

    private static List<Call> run(long seed, boolean quiet, int worldHour, long ticks) {
        ClockCaller caller = new ClockCaller(ClockTuning.DEFAULT, new SplittableRandom(seed));
        List<Call> calls = new ArrayList<>();
        for (long tick = 0; tick < ticks; tick++) {
            caller.tick(quiet, worldHour).ifPresent(calls::add);
        }
        return calls;
    }

    @GameTest
    public void aCallIsRareAboutOnceAnHourNearTheClock(GameTestHelper helper) {
        // 200 game days is 4,800,000 ticks, or 66 hours of play: about one call an hour, never one every few minutes.
        List<Call> calls = run(1, false, 3, TICKS);
        double perHour = calls.size() / (TICKS / 72_000.0);
        expect(helper, perHour > 0.7 && perHour < 1.4, "%s calls an hour of play", perHour);
        helper.succeed();
    }

    @GameTest
    public void theFirstCallIsNotSoonerThanTheFloorAfterArriving(GameTestHelper helper) {
        for (long seed = 0; seed < 20; seed++) {
            ClockCaller caller = new ClockCaller(ClockTuning.DEFAULT, new SplittableRandom(seed));
            for (int i = 0; i < ClockTuning.DEFAULT.firstMinTicks() - 1; i++) {
                expect(helper, caller.tick(false, 5).isEmpty(), "called %s ticks after arriving", i);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void theGapsVaryAndAreNeverShorterThanTheFloor(GameTestHelper helper) {
        ClockCaller caller = new ClockCaller(ClockTuning.DEFAULT, new SplittableRandom(4));
        Set<Long> gaps = new HashSet<>();
        long last = -1;
        for (long tick = 0; tick < TICKS; tick++) {
            if (caller.tick(false, 7).isPresent()) {
                if (last >= 0) {
                    long gap = tick - last;
                    expect(helper, gap >= ClockTuning.DEFAULT.gapMinTicks(), "a gap of %s ticks", gap);
                    expect(helper, gap <= ClockTuning.DEFAULT.gapMaxTicks(), "a gap of %s ticks", gap);
                    gaps.add(gap / 600);
                }
                last = tick;
            }
        }
        expect(helper, gaps.size() > 20, "only %s different gaps in minutes: it has a period", gaps.size());
        helper.succeed();
    }

    @GameTest
    public void aCallIsNeverTheSameHoursTwiceInARow(GameTestHelper helper) {
        for (int world : new int[] {1, 6, 10, 12}) {
            List<Call> calls = run(5L + world, false, world, TICKS);
            for (int i = 1; i < calls.size(); i++) {
                expect(
                        helper,
                        !calls.get(i).hours().equals(calls.get(i - 1).hours()),
                        "world hour %s: %s twice in a row",
                        world,
                        calls.get(i).hours());
            }
        }
        helper.succeed();
    }

    @GameTest
    public void aCallIsRightWrongOrSkippedInTheRightProportionsAndTheWrongOnesAreWrong(GameTestHelper helper) {
        int world = 4;
        EnumSet<Kind> seen = EnumSet.noneOf(Kind.class);
        int[] counts = new int[Kind.values().length];
        List<Call> calls = run(6, false, world, TICKS * 3);
        for (Call call : calls) {
            seen.add(call.kind());
            counts[call.kind().ordinal()]++;
            List<Integer> hours = call.hours();
            switch (call.kind()) {
                case RIGHT -> expect(helper, hours.equals(List.of(world)), "a right call struck %s", hours);
                case WRONG ->
                    expect(
                            helper,
                            hours.size() == 1
                                    && hours.getFirst() != world
                                    && hours.getFirst() >= 1
                                    && hours.getFirst() <= 12,
                            "a wrong call struck %s at %s",
                            hours,
                            world);
                case SKIPPED ->
                    expect(helper, hours.equals(List.of(3, 5)), "a skipping call struck %s at %s", hours, world);
            }
        }
        expect(helper, seen.size() == 3, "only %s kinds of call in %s calls", seen, calls.size());
        // A call is the world's hour less than half the time, so the clock is more often wrong than right.
        double right = counts[Kind.RIGHT.ordinal()] / (double) calls.size();
        expect(helper, right > 0.2 && right < 0.5, "%s of the calls were right", right);
        double skipped = counts[Kind.SKIPPED.ordinal()] / (double) calls.size();
        expect(helper, skipped > 0.1 && skipped < 0.3, "%s of the calls skipped the hour", skipped);
        helper.succeed();
    }

    @GameTest
    public void aSkippingCallStrikesTheSecondHourAfterTheFirstHasFinished(GameTestHelper helper) {
        for (Call call : run(7, false, 12, TICKS)) {
            if (call.kind() != Kind.SKIPPED) {
                continue;
            }
            expect(helper, call.hours().equals(List.of(11, 1)), "the dial did not wrap: %s", call.hours());
            ClockCaller.Chime first = call.chimes().getFirst();
            ClockCaller.Chime second = call.chimes().getLast();
            expect(helper, first.delayTicks() == 0, "the first strike is late");
            expect(
                    helper,
                    second.delayTicks() >= ClockTuning.DEFAULT.wordTicks(),
                    "the second hour starts at %s over the first's word",
                    second.delayTicks());
        }
        helper.succeed();
    }

    @GameTest
    public void theUnspokenTenIsNeverCalledAndAtTenTheClockSkipsAsLambertDoes(GameTestHelper helper) {
        for (int world = 1; world <= 12; world++) {
            List<Call> calls = run(20L + world, false, world, TICKS);
            expect(helper, !calls.isEmpty(), "no calls at world hour %s", world);
            for (Call call : calls) {
                expect(helper, !call.hours().contains(10), "world hour %s: a call struck ten: %s", world, call.hours());
                if (world == 10) {
                    expect(helper, call.kind() != Kind.RIGHT, "at ten the clock called ten");
                    expect(
                            helper,
                            call.kind() == Kind.WRONG || call.hours().equals(List.of(9, 11)),
                            "at ten a call was %s %s",
                            call.kind(),
                            call.hours());
                }
            }
        }
        List<Call> atTen = run(40, false, 10, TICKS);
        expect(helper, atTen.stream().anyMatch(call -> call.kind() == Kind.SKIPPED), "ten never skipped");
        helper.succeed();
    }

    @GameTest
    public void aDueCallWaitsForAnythingSoundingAndIsThenMadeOnce(GameTestHelper helper) {
        ClockCaller caller = new ClockCaller(ClockTuning.DEFAULT, new SplittableRandom(8));
        long quietUntil = 400_000;
        List<Long> at = new ArrayList<>();
        for (long tick = 0; tick < quietUntil + 5_000; tick++) {
            Optional<Call> call = caller.tick(tick < quietUntil, 9);
            if (call.isPresent()) {
                at.add(tick);
            }
        }
        expect(helper, at.size() == 1, "%s calls at %s", at.size(), at);
        expect(helper, at.getFirst() >= quietUntil, "a call was made at %s over a sound", at.getFirst());
        expect(
                helper,
                at.getFirst() - quietUntil <= ClockTuning.DEFAULT.retryMaxTicks(),
                "the call waited %s ticks after the quiet",
                at.getFirst() - quietUntil);
        helper.succeed();
    }

    @GameTest
    public void theDialIsTwelveHoursAndStartsAtSixInTheMorning(GameTestHelper helper) {
        expect(helper, ClockCaller.worldHour(0, 24_000) == 6, "tick 0 is %s", ClockCaller.worldHour(0, 24_000));
        expect(helper, ClockCaller.worldHour(6_000, 24_000) == 12, "noon is %s", ClockCaller.worldHour(6_000, 24_000));
        expect(
                helper,
                ClockCaller.worldHour(18_000, 24_000) == 12,
                "midnight is %s",
                ClockCaller.worldHour(18_000, 24_000));
        expect(
                helper,
                ClockCaller.worldHour(23_999, 24_000) == 5,
                "the last tick is %s",
                ClockCaller.worldHour(23_999, 24_000));
        expect(helper, ClockCaller.dial(12, 1) == 1 && ClockCaller.dial(1, -1) == 12, "the dial does not wrap");
        helper.succeed();
    }

    @GameTest
    public void everyHourButTenHasAWordAndTheDeepFragmentsAreInTheBrokenPoolOnly(GameTestHelper helper) {
        for (int hour = 1; hour <= 12; hour++) {
            if (hour == 10) {
                continue;
            }
            expect(
                    helper,
                    AmbienceFragments.clockHour(hour).location().getPath().equals("ambience.clock.hour_" + hour),
                    "hour %s has the wrong word",
                    hour);
        }
        expect(
                helper,
                Arrays.stream(DeepSound.values())
                        .noneMatch(sound -> sound.event().location().getPath().endsWith("hour_10")),
                "there is a word for ten");
        expect(
                helper,
                AmbienceFragments.pools()
                                .getOrDefault(AmbienceTier.BROKEN, List.of())
                                .contains("clock")
                        && AmbienceFragments.sound(AmbienceTier.BROKEN, "clock")
                                == DeepSound.AMBIENCE_CLOCK_FRAGMENT.event(),
                "the broken pool has no clock fragment");
        for (AmbienceTier tier : List.of(AmbienceTier.FAINT, AmbienceTier.WORN)) {
            expect(
                    helper,
                    !AmbienceFragments.pools().getOrDefault(tier, List.of()).contains("clock"),
                    "the %s pool has the clock",
                    tier);
        }
        helper.succeed();
    }
}
