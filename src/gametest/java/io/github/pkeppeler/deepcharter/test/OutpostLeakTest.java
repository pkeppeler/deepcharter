package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.outpost.OutpostData;
import io.github.pkeppeler.deepcharter.outpost.OutpostData.Outpost;
import io.github.pkeppeler.deepcharter.outpost.OutpostHabitability;
import io.github.pkeppeler.deepcharter.outpost.OutpostHabitability.Status;
import io.github.pkeppeler.deepcharter.outpost.Ratings;
import io.github.pkeppeler.deepcharter.suit.OutpostRisks;
import io.github.pkeppeler.deepcharter.suit.SuitState;
import io.github.pkeppeler.deepcharter.suit.SuitState.Mode;
import io.github.pkeppeler.deepcharter.suit.SuitTicker;
import io.github.pkeppeler.deepcharter.suit.SuitTuning;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes.Hall;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes.Pocket;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;

/**
 * Server GameTests for what an uninhabitable hall does to the suit (#993, ADR 0083): the timer keeps running inside, at half the outside
 * rate however many blocks fall short, and a habitable hall (any hall in layer 1, which asks nothing) refills it. The unbuilt depth is
 * the one depth with a row to fall short of today, and no hall holds there.
 */
public class OutpostLeakTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 400;
    private static final float EPSILON = 0.001f;
    private static final float CAPACITY = SuitTuning.DEFAULT.capacities().getFirst();
    private static final float DRY_RATE = 5.5f;
    /** A row the kit's plates (2 on every axis) hold on the suit's axes and fall short of on quake. */
    private static final Ratings QUAKE_ABOVE_THE_KIT = new Ratings(2, 2, 2, 3);

    private static boolean near(float actual, float expected) {
        return Math.abs(actual - expected) < EPSILON;
    }

    /** An unrated hall costs half what standing outside costs, every second, and says it leaks. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void anUnratedHallRunsTheSuitAtHalfTheOutsideRate(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, 1);
        Pocket pocket = SuitScenes.unbuilt(level, 6580);
        MockPlayer mock = MockPlayers.join(helper, "leak-half");
        Outpost outpost = new Outpost(new CharterId(UUID.randomUUID()), 1, level.dimension(), pocket.floor());
        FarChunks.awaitEntityTicking(helper, level, pocket.floor(), () -> {
            WorldData.with(level.getServer(), OutpostData.TYPE, SuitScenes.dataWith(outpost), () -> {
                SuitScenes.setReserve(mock.player(), CAPACITY, true);
                SuitScenes.onFootIn(mock, pocket, () -> {
                    SuitScenes.seconds(mock.player(), 1);
                    SuitState inside = SuitScenes.state(mock.player());
                    expect(
                            helper,
                            inside.mode() == Mode.LEAKING
                                    && near(inside.rate(), DRY_RATE * SuitTuning.DEFAULT.leakShare())
                                    && near(inside.reserve(), CAPACITY - DRY_RATE / 2),
                            "inside an unrated hall the suit runs at half, it reads %s",
                            inside);
                    // However many blocks are bad, the rate is the same: the hall is made of rock here, every block of
                    // its shell unrated.
                    SuitScenes.setReserve(mock.player(), CAPACITY, true);
                    int seconds = SuitScenes.secondsToEmpty(mock.player(), 200);
                    expect(
                            helper,
                            seconds == 66,
                            "an unrated hall doubles the 33 s outside to 66, it took %s",
                            seconds);
                    expect(
                            helper,
                            SuitScenes.state(mock.player()).mode() == Mode.BREACHED,
                            "a spent suit in a leaking hall is breached");
                });
            });
            helper.succeed();
        });
    }

    /**
     * The suit is judged on its own axes: a hall whose shell holds heat, pressure and flooding but falls short on quake alone shelters the
     * suit, refills it and says it holds. No built layer asks quake yet, so the hall is judged against a staged row that does.
     */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aHallShortOnlyOnQuakeSheltersTheSuitAndShowsNoLeak(GameTestHelper helper) {
        Hall hall = SuitScenes.stageHall(helper, 6956, "leak-quake");
        SuitScenes.whenHallReady(helper, hall, () -> {
            Outpost outpost = hall.build();
            // The kit's pit and hatch, of crust and rock, patched with slag brick: every block holds 2 on every axis.
            for (BlockPos pos : BlockPos.betweenClosed(
                    hall.floor().offset(-2, 0, -2), hall.floor().offset(2, 6, 2))) {
                int dy = pos.getY() - hall.floor().getY();
                if ((dy == 0 || dy == 6)
                        && !outpost.encloses(pos)
                        && !hall.level().getBlockState(pos).isAir()) {
                    hall.level().setBlock(pos, SlagBrick.BLOCK.defaultBlockState(), 3);
                }
            }
            Optional<Ratings> row = Optional.of(QUAKE_ABOVE_THE_KIT);
            Status status = OutpostHabitability.of(hall.level(), outpost, row);
            expect(
                    helper,
                    status.habitable()
                            && status.unrated() == 0
                            && status.shortOn().isEmpty(),
                    "a shell short only on quake holds for the suit, it reads %s",
                    status);
            List<Component> lines = OutpostRisks.lines(row, status);
            expect(
                    helper,
                    lines.size() == 1
                            && lines.getFirst().getContents() instanceof TranslatableContents line
                            && line.getKey().equals("notice.deepcharter.outpost.holds"),
                    "the risk line says the shell holds and names no leak, it said %s",
                    lines);
            SuitScenes.setReserve(hall.player(), 60f, true);
            try {
                hall.standIn();
                for (int second = 0; second < 2; second++) {
                    SuitTicker.second(hall.player(), (level, judged) -> row);
                }
                SuitState sheltered = SuitScenes.state(hall.player());
                expect(
                        helper,
                        sheltered.mode() == Mode.SHELTERED && near(sheltered.reserve(), 90f),
                        "a hall short on quake alone shelters the suit and gives back 2 * 15, it reads %s",
                        sheltered);
            } finally {
                hall.player().setGameMode(GameType.SPECTATOR);
            }
        });
    }

    /** A kit hall in layer 1 holds its (empty) row, so the suit reads SHELTERED there and takes its reserve back. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aLayerOneHallSheltersTheSuitAndRefillsIt(GameTestHelper helper) {
        Hall hall = SuitScenes.stageHall(helper, 6640, "leak-sheltered");
        SuitScenes.whenHallReady(helper, hall, () -> {
            hall.build();
            SuitScenes.setReserve(hall.player(), 60f, true);
            try {
                hall.standIn();
                SuitScenes.seconds(hall.player(), 2);
                SuitState sheltered = SuitScenes.state(hall.player());
                expect(
                        helper,
                        sheltered.mode() == Mode.SHELTERED && near(sheltered.reserve(), 90f),
                        "two seconds in a habitable hall give back 2 * 15, it reads %s",
                        sheltered);
                SuitScenes.seconds(hall.player(), 20);
                expect(
                        helper,
                        near(SuitScenes.state(hall.player()).reserve(), CAPACITY),
                        "a rest fills the suit and no more, it reads %s",
                        SuitScenes.state(hall.player()));
                // The hall's data is unreadable: the suit falls back to the depth and never throws.
                UnreadableChecks.assertSavedDataNoThrow(
                        helper,
                        "suit",
                        hall.level().getServer(),
                        OutpostData.TYPE,
                        Map.of("second", () -> SuitScenes.seconds(hall.player(), 1)));
            } finally {
                hall.player().setGameMode(GameType.SPECTATOR);
            }
        });
    }
}
