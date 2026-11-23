package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.outpost.OutpostData;
import io.github.pkeppeler.deepcharter.outpost.OutpostData.Outpost;
import io.github.pkeppeler.deepcharter.outpost.RatingTuning;
import io.github.pkeppeler.deepcharter.outpost.Ratings;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.suit.SuitDrain;
import io.github.pkeppeler.deepcharter.suit.SuitState.Mode;
import io.github.pkeppeler.deepcharter.suit.SuitTicker;
import io.github.pkeppeler.deepcharter.suit.SuitTuning;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes.Pocket;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pacing of the suit timer in player units (#993, ADR 0083): how many seconds a stock suit lasts at a depth, how long a rest takes, how long
 * a breach leaves a person. It prints {@code [suit-pacing]} lines that the design log's table is made from. What the unbuilt depth asks
 * is measured by running the suit; a row that no layer has yet is modelled from the drain's formula. The test fails if a layer-3 row ever
 * leaves a stock suit under a minute.
 */
public class SuitPacingTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(SuitPacingTest.class);
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1_000;
    private static final float CAPACITY = SuitTuning.DEFAULT.capacity();
    private static final int LIMIT = 1_000;
    /** The shortest a stock suit may last in a layer's dry row from layer 3 on. */
    private static final int LAYER_THREE_FLOOR_SECONDS = 60;

    private static void print(String what, String how, double seconds) {
        LOGGER.info("[suit-pacing] {} | {} | {}", what, how, String.format(Locale.ROOT, "%.1f s", seconds));
    }

    /** Whole seconds until the reserve is spent: the second that takes it to zero counts. */
    private static int modelled(Ratings row, boolean wet) {
        float rate = SuitDrain.perSecond(row, SuitTuning.DEFAULT.stock(), wet, SuitTuning.DEFAULT);
        return (int) Math.ceil(CAPACITY / rate);
    }

    /** Dry in the unbuilt depth, in an unrated hall there, and the rest that follows: the suit run as it is. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void measuredDryUnratedHallAndRest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Pocket pocket = SuitScenes.unbuilt(level, 7400);
        MockPlayer mock = MockPlayers.join(helper, "pacing-dry");
        Outpost hall = new Outpost(new CharterId(UUID.randomUUID()), 1, level.dimension(), pocket.floor());
        SuitScenes.whenReady(helper, pocket, () -> {
            ServerPlayer player = mock.player();
            SuitScenes.onFootIn(mock, pocket, () -> {
                SuitScenes.setReserve(player, CAPACITY, true);
                int dry = SuitScenes.secondsToEmpty(player, LIMIT);
                print("unbuilt depth, dry", "measured", dry);
                expect(helper, dry == 33, "a stock suit lasts 33 s dry in unbuilt depth, it lasted %s", dry);

                WorldData.with(level.getServer(), OutpostData.TYPE, SuitScenes.dataWith(hall), () -> {
                    SuitScenes.setReserve(player, CAPACITY, true);
                    int unrated = SuitScenes.secondsToEmpty(player, LIMIT);
                    print("unbuilt depth, unrated hall", "measured", unrated);
                    expect(helper, unrated == 66, "an unrated hall doubles it to 66 s, it lasted %s", unrated);
                });

                PodEntity pod = TestEntities.create(PodRegistry.POD, level);
                pod.setPos(pocket.stand());
                level.addFreshEntity(pod);
                try {
                    player.startRiding(pod, true, false);
                    SuitScenes.setReserve(player, 0f, true);
                    int rest = 0;
                    while (SuitScenes.state(player).reserve() < CAPACITY && rest < LIMIT) {
                        SuitTicker.second(player);
                        rest++;
                    }
                    print("refill from empty, powered pod seat", "measured", rest);
                    expect(
                            helper,
                            rest == 12 && SuitScenes.state(player).mode() == Mode.POD_REFILL,
                            "a powered seat refills an empty suit in 12 s, it took %s",
                            rest);
                } finally {
                    player.stopRiding();
                    pod.discard();
                }
            });
            helper.succeed();
        });
    }

    /** Submerged in the unbuilt depth the flooding term joins: 10.5 a second. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void measuredSubmerged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Pocket pocket = SuitScenes.unbuilt(level, 7464);
        MockPlayer mock = MockPlayers.join(helper, "pacing-wet");
        ServerPlayer player = mock.player();
        SuitScenes.sequence(helper, pocket)
                .thenExecute(() -> {
                    for (BlockPos pos : BlockPos.betweenClosed(
                            pocket.floor().offset(-2, 1, -2), pocket.floor().offset(2, 3, 2))) {
                        level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
                    }
                    SuitScenes.setReserve(player, CAPACITY, true);
                    mock.teleportTo(level, pocket.stand(), 0f, 0f);
                    player.setGameMode(GameType.SURVIVAL);
                })
                .thenWaitUntil(() -> {
                    if (!player.isUnderWater()) {
                        throw helper.assertionException("waiting for the player to be under water");
                    }
                })
                .thenExecute(() -> {
                    int wet = SuitScenes.secondsToEmpty(player, LIMIT);
                    player.setGameMode(GameType.SPECTATOR);
                    print("unbuilt depth, submerged", "measured", wet);
                    expect(helper, wet == 18, "a stock suit lasts 18 s submerged in unbuilt depth, it lasted %s", wet);
                })
                .thenSucceed();
    }

    /** From the first hit of a breach to death takes ten hits, forty ticks apart: about twenty seconds from zero. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void measuredFromBreachToDeath(GameTestHelper helper) {
        Pocket pocket = SuitScenes.unbuilt(helper.getLevel(), 7528);
        MockPlayer mock = MockPlayers.join(helper, "pacing-death");
        ServerPlayer player = mock.player();
        long[] ticks = {-1, -1};
        SuitScenes.sequence(helper, pocket)
                .thenExecute(() -> {
                    player.getFoodData().setFoodLevel(10);
                    SuitScenes.setReserve(player, 0f, true);
                    mock.teleportTo(pocket.level(), pocket.stand(), 0f, 0f);
                    player.setGameMode(GameType.SURVIVAL);
                    ticks[0] = helper.getTick();
                })
                .thenWaitUntil(() -> {
                    if (!player.isDeadOrDying()) {
                        throw helper.assertionException(
                                "waiting for the breach to kill, health %s", player.getHealth());
                    }
                    ticks[1] = helper.getTick();
                })
                .thenExecute(() -> {
                    double seconds = (ticks[1] - ticks[0]) / 20.0;
                    print("zero to death, full health", "measured", seconds);
                    expect(
                            helper,
                            seconds >= 18.0 && seconds <= 21.0,
                            "a breach should kill a full-health person in about 20 s, it took %s",
                            seconds);
                })
                .thenSucceed();
    }

    /** The rows no layer has yet, from the formula; and the floor a layer-3 row may never go below. */
    @GameTest
    public void modelledRowsAndTheLayerThreeFloor(GameTestHelper helper) {
        int one = modelled(new Ratings(1, 1, 1, 0), false);
        int two = modelled(new Ratings(2, 2, 2, 0), false);
        int wet = modelled(new Ratings(2, 3, 3, 0), true);
        int hot = modelled(new Ratings(5, 3, 0, 0), false);
        print("row (1,1,1)", "modelled", one);
        print("row (2,2,2)", "modelled", two);
        print("row (2,3,3), wet", "modelled", wet);
        print("row (5,3,0)", "modelled", hot);
        expect(helper, one == 120 && two == 72 && wet == 30 && hot == 40, "modelled %s %s %s %s", one, two, wet, hot);

        int built = RatingTuning.DEFAULT.layers().size();
        for (int layer = 3; layer <= built; layer++) {
            Ratings row = RatingTuning.DEFAULT.layers().get(layer - 1);
            int seconds = modelled(row, false);
            print("layer " + layer + " row " + row, "modelled", seconds);
            expect(
                    helper,
                    seconds >= LAYER_THREE_FLOOR_SECONDS,
                    "layer %s's row %s leaves a stock suit %s s dry, under the %s s floor: lower the row or raise the capacity",
                    layer,
                    row,
                    seconds,
                    LAYER_THREE_FLOOR_SECONDS);
        }
        helper.succeed();
    }
}
