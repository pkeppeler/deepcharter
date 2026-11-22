package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.arm;
import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.bolts;
import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.corridor;
import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.figure;
import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.fire;
import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.walker;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.Tally;
import io.github.pkeppeler.deepcharter.layer.CaveInTuning;
import io.github.pkeppeler.deepcharter.layer.CaveIns;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.repair.BoltDriverTuning;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.test.support.BoltRoom.Corridor;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Run;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Zone;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pacing of the bolt driver (#987): what a fight and a tunnel cost in bolts, and what those bolts cost against what a run earns. The
 * shots a creature takes, and the spacing of roof bolts along a tunnel, are measured on the real driver, creature and cave-in. The
 * dollars are modelled: a bolt costs its pack's price over the pack, and a run nets what {@link EarlyRunModel} works out from the ore
 * tables and the pod's stats, which is the figure {@code EconomyAffordabilityTest} prices every item against.
 */
public class BoltDriverPacingTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(BoltDriverPacingTest.class);
    private static final BoltDriverTuning DRIVER = BoltDriverTuning.DEFAULT;
    private static final CaveInTuning CAVE_IN = CaveInTuning.DEFAULT;
    private static final float EAST = -90f;
    private static final int SPAWN_PROTECTION_TICKS = 100;
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1200;
    private static final int TUNNEL = 40;
    /** The longest spacing tried, in blocks: far more than a bolt reaches. */
    private static final int WIDEST_SPACING = 12;

    /** A Retained takes this many bolts, at least and at most: more than one, so a fight is a choice, and few enough to be a fight and not a siege. */
    private static final int RETAINED_SHOTS_MIN = 2;

    private static final int RETAINED_SHOTS_MAX = 4;
    /** Retained met in one run of layer 2 for the cost bound: CREATURES section 4 has them common there, so this is generous. */
    private static final int RETAINED_PER_RUN = 3;
    /** Bolting 100 blocks of tunnel costs this share of a run of layer 2, at least and at most: a real expense, never most of a run. */
    private static final double HUNDRED_BLOCKS_SHARE_MIN = 0.05;

    private static final double HUNDRED_BLOCKS_SHARE_MAX = 0.40;
    /** The roof bolts a tunnel needs are one every this many blocks, at least and at most. */
    private static final int SPACING_MIN = 4;

    private static final int SPACING_MAX = 6;
    /** The Retained met in a run cost at most this share of the run's net. */
    private static final double FIGHTS_SHARE_MAX = 0.15;

    private static double boltPrice() {
        return (double) Consumable.BOLT.price() / Consumable.BOLT.perPurchase();
    }

    private static Run layerTwoRun() {
        return EarlyRunModel.layerTwoRun(Zone.load("upper_levels"), EarlyRunModel.mole(2, 2, 2));
    }

    /** Fires every {@code DRIVER.fireCooldownTicks()} ticks, the driver's own rest, until {@code target} is dead: the bolts that took. */
    private static final class Fight {
        private final MockPlayer shooter;
        private final LivingEntity target;
        private int shots;
        private long lastShot = -1000;

        private Fight(MockPlayer shooter, LivingEntity target) {
            this.shooter = shooter;
            this.target = target;
        }

        /** Takes the next shot if the driver has rested; true once the target is dead. */
        boolean step(long now) {
            if (!target.isAlive()) {
                return true;
            }
            if (now - lastShot >= DRIVER.fireCooldownTicks() + 1) {
                // A person aims: the bolt flies where they look, and a Tally hovers lower than a Retained's chest.
                shooter.player()
                        .lookAt(
                                EntityAnchorArgument.Anchor.EYES,
                                target.getBoundingBox().getCenter());
                fire(shooter.player());
                lastShot = now;
                shots++;
            }
            return !target.isAlive();
        }
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aRetainedTakesAFewBoltsAndATallyOne(GameTestHelper helper) {
        Corridor corridor = corridor(helper, 14, 20);
        MockPlayer shooter = walker(helper, corridor, "pacing-shooter", 1, EAST);
        arm(shooter.player(), 24);
        ServerLevel level = corridor.level();
        Fight[] fights = {null, null};
        TestTicks.each(helper, () -> {
            long now = helper.getTick();
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            if (fights[0] == null) {
                fights[0] = new Fight(shooter, figure(level, corridor.stand(11)));
            }
            if (!fights[0].step(now)) {
                return;
            }
            if (fights[1] == null) {
                Tally tally = TestEntities.create(CreatureRegistry.TALLY, level);
                tally.setPos(corridor.stand(11));
                tally.setNoAi(true);
                level.addFreshEntity(tally);
                fights[1] = new Fight(shooter, tally);
            }
            if (!fights[1].step(now)) {
                return;
            }
            int retainedShots = fights[0].shots;
            int tallyShots = fights[1].shots;
            double fight = retainedShots * boltPrice();
            Run layerTwo = layerTwoRun();
            double perRun = RETAINED_PER_RUN * fight / layerTwo.net();
            LOGGER.info(
                    "[bolt-pacing] measured: {} bolts a Retained, {} a Tally; modelled: a Retained fight ${}, {} of them a layer 2 run"
                            + " (${}) cost {}",
                    retainedShots,
                    tallyShots,
                    fight,
                    RETAINED_PER_RUN,
                    Math.round(layerTwo.net()),
                    perRun);
            if (bolts(shooter.player()) != 24 - retainedShots - tallyShots) {
                throw failure(
                        helper,
                        "the shots spent %d bolts, the pack lost %d",
                        retainedShots + tallyShots,
                        24 - bolts(shooter.player()));
            }
            if (retainedShots < RETAINED_SHOTS_MIN || retainedShots > RETAINED_SHOTS_MAX) {
                throw failure(
                        helper,
                        "a Retained should take %d to %d bolts, it took %d",
                        RETAINED_SHOTS_MIN,
                        RETAINED_SHOTS_MAX,
                        retainedShots);
            }
            if (tallyShots != 1) {
                throw failure(helper, "one blow breaks a Tally, and a bolt is a blow: it took %d", tallyShots);
            }
            if (perRun > FIGHTS_SHARE_MAX) {
                throw failure(
                        helper,
                        "%d Retained cost $%.0f, %.0f%% of a layer 2 run's $%.0f; %.0f%% is the most",
                        RETAINED_PER_RUN,
                        RETAINED_PER_RUN * fight,
                        perRun * 100,
                        layerTwo.net(),
                        FIGHTS_SHARE_MAX * 100);
            }
            helper.succeed();
        });
    }

    /** Roof bolts at {@code first}, {@code first + spacing}, and so on along the corridor; the number of roof blocks left loose to a person anywhere in it. */
    private static int looseWithBoltsEvery(Corridor corridor, int first, int spacing, int[] placed) {
        ServerLevel level = corridor.level();
        for (int along = 0; along < corridor.length(); along++) {
            BlockPos cell = corridor.roof(along).below();
            if (level.getBlockState(cell).is(LayerBlocks.ROOF_BOLT)) {
                level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        placed[0] = 0;
        for (int along = first; along < corridor.length(); along += spacing) {
            if (CaveIns.setBolt(level, corridor.roof(along), DRIVER.boltSpacing())
                    .isPresent()) {
                throw new IllegalStateException("a bolt at " + along + " was refused");
            }
            placed[0]++;
        }
        int loose = 0;
        for (int along = 0; along < corridor.length(); along++) {
            loose += CaveIns.looseRoof(level, corridor.floor(along)).size();
        }
        return loose;
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aTunnelNeedsARoofBoltEveryFewBlocks(GameTestHelper helper) {
        Corridor corridor = corridor(helper, TUNNEL, 21);
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent()) {
                return;
            }
            // The widest spacing at which, with the first bolt placed where it covers the tunnel's start, no roof is
            // left loose.
            int reach = (int) Math.floor(Math.sqrt(CAVE_IN.boltReach() * CAVE_IN.boltReach() - 1.0));
            int[] placed = {0};
            int spacing = 0;
            for (int candidate = WIDEST_SPACING; candidate >= 1 && spacing == 0; candidate--) {
                if (looseWithBoltsEvery(corridor, reach, candidate, placed) == 0) {
                    spacing = candidate;
                }
            }
            if (spacing == 0) {
                throw failure(helper, "no spacing of roof bolts holds the tunnel");
            }
            looseWithBoltsEvery(corridor, reach, spacing, placed);
            double per100 = 100.0 / spacing;
            double cost = per100 * boltPrice();
            Run layerTwo = layerTwoRun();
            double share = cost / layerTwo.net();
            LOGGER.info(
                    "[bolt-pacing] measured: one roof bolt every {} blocks ({} in a {} block tunnel); modelled: 100 blocks cost {} bolts,"
                            + " ${}, {} of a layer 2 run (${})",
                    spacing,
                    placed[0],
                    TUNNEL,
                    per100,
                    cost,
                    share,
                    Math.round(layerTwo.net()));
            if (spacing < SPACING_MIN || spacing > SPACING_MAX) {
                throw failure(
                        helper,
                        "a roof bolt should hold %d to %d blocks of tunnel, it holds %d",
                        SPACING_MIN,
                        SPACING_MAX,
                        spacing);
            }
            if (share < HUNDRED_BLOCKS_SHARE_MIN || share > HUNDRED_BLOCKS_SHARE_MAX) {
                throw failure(
                        helper,
                        "bolting 100 blocks costs $%.0f, %.0f%% of a layer 2 run's $%.0f; %.0f%% to %.0f%% is allowed",
                        cost,
                        share * 100,
                        layerTwo.net(),
                        HUNDRED_BLOCKS_SHARE_MIN * 100,
                        HUNDRED_BLOCKS_SHARE_MAX * 100);
            }
            helper.succeed();
        });
    }

    @GameTest
    public void theDriverAndItsPackAreWithinReachOfTheFirstRuns(GameTestHelper helper) {
        Run layerOne = EarlyRunModel.run(Zone.load("topsoil_claims"), PodStats.base(), 0);
        Run layerTwo = layerTwoRun();
        double driver = Consumable.BOLT_DRIVER.price() / layerTwo.net();
        double pack = Consumable.BOLT.price() / layerTwo.net();
        LOGGER.info(
                "[bolt-pacing] modelled: the driver ${} is {} of a layer 2 run, a pack of {} bolts ${} is {} (a layer 1 run nets ${})",
                Consumable.BOLT_DRIVER.price(),
                driver,
                DRIVER.packSize(),
                Consumable.BOLT.price(),
                pack,
                Math.round(layerOne.net()));
        // One run pays for the driver and some bolts: no one is made to grind to start on foot.
        if (Consumable.BOLT_DRIVER.price() + Consumable.BOLT.price() > layerTwo.net()) {
            throw failure(
                    helper,
                    "the driver and a pack cost $%d, more than the $%.0f of a layer 2 run",
                    Consumable.BOLT_DRIVER.price() + Consumable.BOLT.price(),
                    layerTwo.net());
        }
        helper.succeed();
    }
}
