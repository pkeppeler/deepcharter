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
import io.github.pkeppeler.deepcharter.suit.SuitTrack;
import io.github.pkeppeler.deepcharter.suit.SuitTracks;
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
import java.util.Optional;
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
 * is measured by running the suit; a row that no layer has yet is modelled from the drain's formula. The test fails if a built row that
 * asks something (from layer 3 on) leaves a stock suit under a minute, and shows that the check can fail.
 */
public class SuitPacingTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(SuitPacingTest.class);
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1_000;
    private static final float CAPACITY = SuitTuning.DEFAULT.capacities().getFirst();
    private static final int LIMIT = 1_000;
    /** The shortest a stock suit may last dry in a built layer's row that asks something (from layer 3 on). */
    private static final int FLOOR_SECONDS = 60;

    private static final int SECOND_TICKS = 20;

    private static void print(String what, String how, double seconds) {
        LOGGER.info("[suit-pacing] {} | {} | {}", what, how, String.format(Locale.ROOT, "%.1f s", seconds));
    }

    /** Whole seconds until the reserve is spent: the second that takes it to zero counts. */
    private static int modelled(Ratings row, boolean wet) {
        return modelled(row, wet, SuitTracks.STOCK);
    }

    /** As {@link #modelled(Ratings, boolean)} for a suit fitted with {@code tracks}, full to its own capacity. */
    private static int modelled(Ratings row, boolean wet, SuitTracks tracks) {
        float rate = SuitDrain.perSecond(row, tracks.ratings(), wet, SuitTuning.DEFAULT);
        return (int) Math.ceil(tracks.capacity(SuitTuning.DEFAULT) / rate);
    }

    /**
     * Why {@code row} breaks the floor, when it leaves a stock suit under {@value #FLOOR_SECONDS} s dry; empty when it does not. Every built
     * row that asks something is held to it: ratings start at layer 3, so today that is layer 3 and down.
     */
    private static Optional<String> floorBreach(Ratings row) {
        int seconds = modelled(row, false);
        return seconds >= FLOOR_SECONDS
                ? Optional.empty()
                : Optional.of(String.format(
                        Locale.ROOT,
                        "row %s leaves a stock suit %s s dry, under the %s s floor: lower the row or raise the capacity",
                        row,
                        seconds,
                        FLOOR_SECONDS));
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

                SuitScenes.setTracks(player, new SuitTracks(0, 0, 3));
                SuitScenes.setReserve(
                        player, SuitTracks.STOCK.with(SuitTrack.AIR, 3).capacity(SuitTuning.DEFAULT), true);
                int airThree = SuitScenes.secondsToEmpty(player, LIMIT);
                print("unbuilt depth, dry, air tier 3", "measured", airThree);
                expect(helper, airThree == 66, "an air tier 3 suit lasts 66 s dry, it lasted %s", airThree);
                SuitScenes.setTracks(player, new SuitTracks(3, 0, 0));
                SuitScenes.setReserve(player, CAPACITY, true);
                int heatThree = SuitScenes.secondsToEmpty(player, LIMIT);
                print("unbuilt depth, dry, heat tier 3", "measured", heatThree);
                expect(helper, heatThree == 45, "a heat tier 3 suit lasts 45 s dry, it lasted %s", heatThree);
                SuitScenes.setTracks(player, SuitTracks.STOCK);

                // An air canister (#1194) on a spent suit: a minute of reserve, which is all the suit has to spend.
                SuitScenes.setReserve(player, SuitTuning.DEFAULT.canisterSeconds(), true);
                int canister = SuitScenes.secondsToEmpty(player, LIMIT);
                print("unbuilt depth, dry, from empty with an air canister", "measured", canister);
                expect(helper, canister == 11, "a canister keeps a stock suit going 11 s dry, it lasted %s", canister);

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
                    SuitScenes.setTracks(player, new SuitTracks(0, 0, 3));
                    SuitScenes.setReserve(player, 0f, true);
                    int restThree = 0;
                    while (SuitScenes.state(player).reserve() < 360f && restThree < LIMIT) {
                        SuitTicker.second(player);
                        restThree++;
                    }
                    print("refill from empty, powered pod seat, air tier 3", "measured", restThree);
                    expect(
                            helper,
                            restThree == 24,
                            "the refill rate is flat, so an empty air tier 3 suit takes 24 s, it took %s",
                            restThree);
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
                    mock.teleportTo(level, pocket.stand(), 0f, 0f);
                    player.setGameMode(GameType.SURVIVAL);
                })
                .thenWaitUntil(() -> {
                    if (!player.isUnderWater()) {
                        throw helper.assertionException("waiting for the player to be under water");
                    }
                })
                .thenExecute(() -> {
                    // Filled here, not before the wait: the real tick pays a second out while the water settles.
                    SuitScenes.setReserve(player, CAPACITY, true);
                    int wet = SuitScenes.secondsToEmpty(player, LIMIT);
                    print("unbuilt depth, submerged", "measured", wet);
                    SuitScenes.setTracks(player, new SuitTracks(0, 0, 3));
                    SuitScenes.setReserve(player, 360f, true);
                    int airThree = SuitScenes.secondsToEmpty(player, LIMIT);
                    player.setGameMode(GameType.SPECTATOR);
                    print("unbuilt depth, submerged, air tier 3", "measured", airThree);
                    expect(helper, wet == 18, "a stock suit lasts 18 s submerged in unbuilt depth, it lasted %s", wet);
                    expect(helper, airThree == 48, "an air tier 3 suit lasts 48 s submerged, it lasted %s", airThree);
                })
                .thenSucceed();
    }

    /**
     * A breach kills a full-health person in ten hits, forty ticks apart: from the first hit to death is exactly nine gaps, 18 s. The first
     * hit lands within a second (the next pay-out finds the suit spent) and a breach cadence of a spent suit, so zero to death is about
     * 20 s.
     */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void measuredFromBreachToDeath(GameTestHelper helper) {
        Pocket pocket = SuitScenes.unbuilt(helper.getLevel(), 7528);
        MockPlayer mock = MockPlayers.join(helper, "pacing-death");
        ServerPlayer player = mock.player();
        SuitTuning tuning = SuitTuning.DEFAULT;
        int hits = (int) Math.ceil(player.getMaxHealth() / tuning.breachDamage());
        // The second that finds the suit spent, then the wait for the breach cadence, then a tick of slack for the
        // teleport.
        int firstHitBound = SECOND_TICKS + tuning.breachTicks() + 1;
        long[] ticks = {-1, -1, -1};
        SuitScenes.sequence(helper, pocket)
                .thenExecute(() -> {
                    player.getFoodData().setFoodLevel(10);
                    SuitScenes.setReserve(player, 0f, true);
                    mock.teleportTo(pocket.level(), pocket.stand(), 0f, 0f);
                    player.setGameMode(GameType.SURVIVAL);
                    ticks[0] = helper.getTick();
                })
                .thenWaitUntil(() -> {
                    if (player.getHealth() >= player.getMaxHealth()) {
                        throw helper.assertionException("waiting for the first breach hit");
                    }
                    ticks[1] = helper.getTick();
                })
                .thenWaitUntil(() -> {
                    if (!player.isDeadOrDying()) {
                        throw helper.assertionException(
                                "waiting for the breach to kill, health %s", player.getHealth());
                    }
                    ticks[2] = helper.getTick();
                })
                .thenExecute(() -> {
                    long toFirstHit = ticks[1] - ticks[0];
                    long hitToDeath = ticks[2] - ticks[1];
                    print("zero to first hit", "measured", toFirstHit / 20.0);
                    print("first hit to death, full health", "measured", hitToDeath / 20.0);
                    print("zero to death, full health", "measured", (ticks[2] - ticks[0]) / 20.0);
                    expect(
                            helper,
                            toFirstHit >= 1 && toFirstHit <= firstHitBound,
                            "the first breach hit should land within %s ticks of a spent suit, it took %s",
                            firstHitBound,
                            toFirstHit);
                    expect(
                            helper,
                            hitToDeath == (long) (hits - 1) * tuning.breachTicks(),
                            "%s hits %s ticks apart should kill in %s ticks from the first, it took %s",
                            hits,
                            tuning.breachTicks(),
                            (hits - 1) * tuning.breachTicks(),
                            hitToDeath);
                })
                .thenSucceed();
    }

    /**
     * What each tier buys at the unbuilt depth's (5,5,5), from the formula, in whole seconds: heat takes a half second off the rate a
     * level, air adds capacity and, wet, takes a second off a level, and the refill is flat (A), so a bigger suit takes longer to fill.
     */
    @GameTest
    public void theTierTableAtTheUnbuiltDepth(GameTestHelper helper) {
        Ratings row = SuitTuning.DEFAULT.uncharted();
        int[] heat = new int[4];
        int[] airDry = new int[4];
        int[] airWet = new int[4];
        for (int tier = 0; tier <= 3; tier++) {
            heat[tier] = modelled(row, false, new SuitTracks(tier, 0, 0));
            airDry[tier] = modelled(row, false, new SuitTracks(0, 0, tier));
            airWet[tier] = modelled(row, true, new SuitTracks(0, 0, tier));
            print("heat tier " + tier + ", dry", "modelled", heat[tier]);
            print("air tier " + tier + ", dry", "modelled", airDry[tier]);
            print("air tier " + tier + ", submerged", "modelled", airWet[tier]);
        }
        expect(
                helper,
                heat[0] == 33 && heat[1] == 36 && heat[2] == 40 && heat[3] == 45,
                "heat tiers 0 to 3 last 33, 36, 40 and 45 s dry, they last %s",
                java.util.Arrays.toString(heat));
        expect(
                helper,
                airDry[0] == 33 && airDry[1] == 44 && airDry[2] == 55 && airDry[3] == 66,
                "air tiers 0 to 3 last 33, 44, 55 and 66 s dry, they last %s",
                java.util.Arrays.toString(airDry));
        expect(
                helper,
                airWet[0] == 18 && airWet[1] == 26 && airWet[2] == 36 && airWet[3] == 48,
                "air tiers 0 to 3 last 18, 26, 36 and 48 s submerged, they last %s",
                java.util.Arrays.toString(airWet));
        SuitTracks heatAndPressure = new SuitTracks(3, 3, 0);
        SuitTracks all = new SuitTracks(3, 3, 3);
        int hp = modelled(row, false, heatAndPressure);
        int allDry = modelled(row, false, all);
        int allWet = modelled(row, true, all);
        int allHall = (int) Math.ceil(all.capacity(SuitTuning.DEFAULT)
                / (SuitDrain.perSecond(row, all.ratings(), false, SuitTuning.DEFAULT)
                        * SuitTuning.DEFAULT.leakShare()));
        print("heat tier 3 and pressure tier 3, dry", "modelled", hp);
        print("all tier 3, dry", "modelled", allDry);
        print("all tier 3, submerged", "modelled", allWet);
        print("all tier 3, unrated hall", "modelled", allHall);
        expect(helper, hp == 72, "heat 3 and pressure 3 last 72 s dry, they last %s", hp);
        expect(helper, allDry == 144, "all tier 3 lasts 144 s dry, it lasts %s", allDry);
        expect(helper, allWet == 80, "all tier 3 lasts 80 s submerged, it lasts %s", allWet);
        expect(helper, allHall == 288, "all tier 3 lasts 288 s in an unrated hall, it lasts %s", allHall);
        // A canister (#1194) adds a fixed minute whatever the suit holds, so it buys more of a better suit's seconds.
        int canisterAllThree = (int) Math.ceil(SuitTuning.DEFAULT.canisterSeconds()
                / SuitDrain.perSecond(row, all.ratings(), false, SuitTuning.DEFAULT));
        print("air canister from empty, all tier 3, dry", "modelled", canisterAllThree);
        expect(
                helper,
                canisterAllThree == 24,
                "a canister keeps an all tier 3 suit going 24 s dry, it lasts %s",
                canisterAllThree);
        float refill = SuitTuning.DEFAULT.refillPerSecond();
        int rest = (int) Math.ceil(all.capacity(SuitTuning.DEFAULT) / refill);
        print("refill from empty at air tier 3", "modelled", rest);
        expect(helper, rest == 24, "an empty air tier 3 suit refills in 24 s, it takes %s", rest);
        // Heat and pressure at tier 2 meet a (2,2,2) row dry, so only the base is paid, on a stock suit's capacity.
        int met = modelled(new Ratings(2, 2, 2, 0), false, new SuitTracks(2, 2, 0));
        print("row (2,2,2), heat tier 2 and pressure tier 2", "modelled", met);
        expect(
                helper,
                met == 360,
                "heat 2 and pressure 2 meet (2,2,2) and pay the base only: 360 s, they last %s",
                met);
        helper.succeed();
    }

    /** The rows no layer has yet, from the formula; and the floor no built row that asks something may go below. */
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

        int checked = 0;
        for (Ratings row : RatingTuning.DEFAULT.layers()) {
            if (SuitDrain.asks(row)) {
                print("built row " + row, "modelled", modelled(row, false));
                Optional<String> breach = floorBreach(row);
                expect(helper, breach.isEmpty(), "%s", breach.orElse(""));
                checked++;
            }
        }
        LOGGER.info("[suit-pacing] the floor checked {} built rows that ask something", checked);

        // The check can fail: the unbuilt depth's row leaves a stock suit 33 s, under the floor.
        Ratings breaking = SuitTuning.DEFAULT.uncharted();
        expect(
                helper,
                floorBreach(breaking).isPresent(),
                "the floor check should refuse %s, which leaves a stock suit %s s dry",
                breaking,
                modelled(breaking, false));
        expect(helper, floorBreach(new Ratings(1, 1, 1, 0)).isEmpty(), "a row (1,1,1) leaves 120 s, over the floor");
        helper.succeed();
    }
}
