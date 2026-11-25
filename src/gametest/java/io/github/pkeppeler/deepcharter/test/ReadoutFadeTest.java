package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.client.layer.ReadoutFade;
import io.github.pkeppeler.deepcharter.client.theme.ReadoutLook;
import io.github.pkeppeler.deepcharter.layer.BlockReadouts.Readout;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

/**
 * Server GameTests for how the block readout fades (#859). The state machine is plain Java over the crosshair's readout each tick, so no client is
 * needed; {@code BlockReadoutClientTest} checks the real HUD.
 */
public class ReadoutFadeTest {
    private static final ReadoutLook LOOK = new ReadoutLook(77, 2, 1.0, 0xFF00FF00, 0xFFFFFF00, 4, 6, 2);
    private static final Optional<Readout> STONE =
            Optional.of(new Readout(Component.literal("Stone"), Optional.empty()));
    private static final Optional<Readout> DIRT = Optional.of(new Readout(Component.literal("Dirt"), Optional.empty()));
    private static final Optional<Readout> NOTHING = Optional.empty();
    private static final float EPSILON = 1e-4f;

    private static void tick(ReadoutFade<Readout> fade, Optional<Readout> looked, int times) {
        for (int i = 0; i < times; i++) {
            fade.tick(looked, LOOK);
        }
    }

    @GameTest
    public void aReadoutFadesInOverTheLooksTicks(GameTestHelper helper) {
        ReadoutFade<Readout> fade = new ReadoutFade<>();
        tick(fade, STONE, 1);
        expect(helper, fade.alpha(1f) == 0f, "nothing shows before the crosshair has settled, got %s", fade.alpha(1f));
        tick(fade, STONE, 2);
        expect(helper, fade.shown().equals(STONE), "stone shows once settled, got %s", fade.shown());
        tick(fade, STONE, 4);
        expect(
                helper,
                Math.abs(fade.alpha(1f) - 1f) < EPSILON,
                "fully opaque after the fade-in, got %s",
                fade.alpha(1f));
        helper.succeed();
    }

    @GameTest
    public void aReadoutFadesOutWhenTheCrosshairLeaves(GameTestHelper helper) {
        ReadoutFade<Readout> fade = new ReadoutFade<>();
        tick(fade, STONE, 10);
        tick(fade, NOTHING, 3);
        float half = fade.alpha(1f);
        expect(helper, half > 0f && half < 1f, "half faded after 3 of 6 ticks, got %s", half);
        expect(helper, fade.shown().equals(STONE), "the readout stays while it fades");
        tick(fade, NOTHING, 3);
        expect(helper, fade.alpha(1f) == 0f && fade.shown().isEmpty(), "gone after the fade-out");
        helper.succeed();
    }

    @GameTest
    public void aSweepAcrossBlocksNeverShowsOneThatDidNotSettle(GameTestHelper helper) {
        ReadoutFade<Readout> fade = new ReadoutFade<>();
        tick(fade, STONE, 10);
        for (int i = 0; i < 6; i++) {
            tick(fade, i % 2 == 0 ? DIRT : STONE, 1);
            expect(helper, !fade.shown().equals(DIRT), "dirt flashed after %d ticks of a sweep", i + 1);
        }
        helper.succeed();
    }

    @GameTest
    public void aNewBlockReplacesTheOldOnceItHasSettledAndTheOldHasFaded(GameTestHelper helper) {
        ReadoutFade<Readout> fade = new ReadoutFade<>();
        tick(fade, STONE, 10);
        tick(fade, DIRT, 12);
        expect(helper, fade.shown().equals(DIRT), "dirt replaced stone, got %s", fade.shown());
        tick(fade, DIRT, 6);
        expect(helper, Math.abs(fade.alpha(1f) - 1f) < EPSILON, "dirt is opaque, got %s", fade.alpha(1f));
        helper.succeed();
    }

    @GameTest
    public void aBlinkOfNothingBetweenTwoLikeBlocksStillFadesRatherThanJumps(GameTestHelper helper) {
        ReadoutFade<Readout> fade = new ReadoutFade<>();
        tick(fade, STONE, 10);
        tick(fade, NOTHING, 1);
        float dipped = fade.alpha(1f);
        expect(helper, dipped < 1f && dipped > 0.5f, "one tick of a miss dims a little, got %s", dipped);
        helper.succeed();
    }

    @GameTest
    public void aBlockMustHoldTheCrosshairForTheSettleTicksBeforeItReplacesTheOne(GameTestHelper helper) {
        ReadoutLook slow = new ReadoutLook(77, 2, 1.0, 0xFF00FF00, 0xFFFFFF00, 4, 1, 5);
        ReadoutFade<Readout> fade = new ReadoutFade<>();
        for (int i = 0; i < 10; i++) {
            fade.tick(STONE, slow);
        }
        for (int i = 0; i < 4; i++) {
            fade.tick(DIRT, slow);
        }
        expect(
                helper,
                fade.shown().equals(STONE),
                "four of five settle ticks must not replace stone, got %s",
                fade.shown());
        fade.tick(DIRT, slow);
        expect(
                helper,
                fade.shown().equals(DIRT),
                "the fifth settle tick replaces stone with dirt, got %s",
                fade.shown());
        helper.succeed();
    }

    @GameTest
    public void theFadeInIsExactlyTheLooksTicks(GameTestHelper helper) {
        ReadoutFade<Readout> fade = new ReadoutFade<>();
        tick(fade, STONE, 2);
        float[] expected = {0.25f, 0.5f, 0.75f, 1f};
        for (int step = 0; step < expected.length; step++) {
            if (step > 0) {
                tick(fade, STONE, 1);
            }
            expect(
                    helper,
                    Math.abs(fade.alpha(1f) - expected[step]) < EPSILON,
                    "fade-in step %d: expected %s, got %s",
                    step + 1,
                    expected[step],
                    fade.alpha(1f));
        }
        helper.succeed();
    }
}
