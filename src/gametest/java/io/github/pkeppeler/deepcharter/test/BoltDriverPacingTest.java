package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.arm;
import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.bolts;
import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.charter;
import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.corridor;
import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.figure;
import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.fire;
import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.walker;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.charter.CharterId;
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
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.Optional;
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
    /** The slot of {@code BoltRoom} the tunnel starts at; it spans this and the next. */
    private static final int TUNNEL_COLUMN = 21;
    /**
     * Blocks of tunnel. No other corridor may have air within a cave-in's stretch radius ({@link CaveInTuning#stretchRadius()}) of either
     * end, or the test reads that roof as loose when the neighbour carves second (#1084).
     */
    private static final int TUNNEL = 40;
    /** The rock of a cave-in has fallen, and the last of it is down, this many ticks after the warning. */
    private static final int ROCK_FELL_TICKS = 30;
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
        Corridor corridor = corridor(helper, 14, 16);
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
                Tally tally = TestEntities.mob(CreatureRegistry.TALLY, level);
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
    private static int looseWithBoltsEvery(
            GameTestHelper helper, Corridor corridor, int first, int spacing, int[] placed) {
        ServerLevel level = corridor.level();
        for (int along = 0; along < corridor.length(); along++) {
            BlockPos cell = corridor.roof(along).below();
            if (level.getBlockState(cell).is(LayerBlocks.ROOF_BOLT)) {
                level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        placed[0] = 0;
        for (int along = first; along < corridor.length(); along += spacing) {
            BlockPos roof = corridor.roof(along);
            Optional<String> refusal = CaveIns.boltRefusal(level, roof, DRIVER.boltSpacing());
            if (refusal.isPresent()) {
                throw failure(
                        helper,
                        "a bolt at %d (spacing %d, roof %s, cell %s) was refused: %s",
                        along,
                        spacing,
                        level.getBlockState(roof),
                        level.getBlockState(roof.below()),
                        refusal.get());
            }
            CaveIns.hangBolt(level, roof);
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
        // Slot 22 stays unused: this tunnel's bed reaches into it, and BoltRoom.cut refuses it.
        Corridor corridor = corridor(helper, TUNNEL, TUNNEL_COLUMN);
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent()) {
                return;
            }
            // The widest spacing at which, with the first bolt placed where it covers the tunnel's start, no roof is
            // left loose.
            int reach = (int) Math.floor(Math.sqrt(CAVE_IN.boltReach() * CAVE_IN.boltReach() - 1.0));
            int[] placed = {0};
            int spacing = 0;
            for (int candidate = WIDEST_SPACING; candidate > DRIVER.boltSpacing() && spacing == 0; candidate--) {
                if (looseWithBoltsEvery(helper, corridor, reach, candidate, placed) == 0) {
                    spacing = candidate;
                }
            }
            if (spacing == 0) {
                throw failure(helper, "no spacing of roof bolts holds the tunnel");
            }
            looseWithBoltsEvery(helper, corridor, reach, spacing, placed);
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

    /** The creak lead is the warning every hazard gives, 3 s: at least this many ticks from a blast to the first rock falling, and at most this many. */
    private static final int LEAD_MIN = 60;

    private static final int LEAD_MAX = 65;
    /** Roof cells that fall in a cave-in: a disc of the stretch's radius holds 29, so this band holds a stretch that is small, never a whole tunnel. */
    private static final int FALLEN_MIN = 21;

    private static final int FALLEN_MAX = 29;

    @GameTest(maxTicks = MAX_TICKS)
    public void aCaveInCreaksForThreeSecondsAndBringsDownARoundStretchOfRoof(GameTestHelper helper) {
        Corridor corridor = corridor(helper, 14, 35);
        ServerLevel level = corridor.level();
        BlockPos middle = corridor.floor(7);
        int half = CAVE_IN.stretchRadius();
        // The corridor is 1 wide: open it into a room as wide as the stretch, so the fall's radius is measured in two
        // dimensions.
        RoomCarver.carve(
                level,
                corridor.x(),
                corridor.x() + 13,
                middle.getY(),
                middle.getY() + 1,
                middle.getZ() - half,
                middle.getZ() + half,
                Blocks.AIR);
        MockPlayer under = walker(helper, corridor, "pacing-under", 7, EAST);
        long[] blastedAt = {-1};
        long[] firstFall = {-1};
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            long now = level.getGameTime();
            if (blastedAt[0] < 0) {
                // Their own charter's blast, so the PvP rule does not matter.
                CharterId mine = charter(helper, under);
                CaveIns.blasted(level, corridor.stand(7).add(0, 1, 0), Optional.of(mine));
                blastedAt[0] = now;
                return;
            }
            if (firstFall[0] < 0 && fallenRoof(level, middle, half).cells > 0) {
                firstFall[0] = now - blastedAt[0];
            }
            if (now - blastedAt[0] != CAVE_IN.warnTicks() + ROCK_FELL_TICKS) {
                return;
            }
            Fallen fallen = fallenRoof(level, middle, half);
            LOGGER.info(
                    "[cave-in-pacing] measured: the first rock fell {} ticks after the blast ({} warns); {} roof cells fell, as far as {} cells from"
                            + " the person ({} is the stretch)",
                    firstFall[0],
                    CAVE_IN.warnTicks(),
                    fallen.cells,
                    fallen.farthest,
                    CAVE_IN.stretchRadius());
            if (firstFall[0] < LEAD_MIN || firstFall[0] > LEAD_MAX) {
                throw failure(
                        helper,
                        "the roof should creak %d to %d ticks before it falls, it creaked %d",
                        LEAD_MIN,
                        LEAD_MAX,
                        firstFall[0]);
            }
            if (fallen.cells < FALLEN_MIN || fallen.cells > FALLEN_MAX) {
                throw failure(helper, "%d to %d roof cells should fall, %d did", FALLEN_MIN, FALLEN_MAX, fallen.cells);
            }
            if (fallen.farthest < CAVE_IN.stretchRadius() - 1 || fallen.farthest > CAVE_IN.stretchRadius()) {
                throw failure(
                        helper,
                        "the fall should reach %d to %d cells from the person, it reached %.2f",
                        CAVE_IN.stretchRadius() - 1,
                        CAVE_IN.stretchRadius(),
                        fallen.farthest);
            }
            helper.succeed();
        });
    }

    /** How much roof has come down: the cells of the room's roof that are no longer stone, and the farthest of them from {@code middle}. */
    private record Fallen(int cells, double farthest) {}

    private static Fallen fallenRoof(ServerLevel level, BlockPos middle, int half) {
        int count = 0;
        double farthest = 0;
        for (int dx = -half - 1; dx <= half + 1; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                if (!level.getBlockState(middle.offset(dx, 2, dz)).is(Blocks.STONE)) {
                    count++;
                    farthest = Math.max(farthest, Math.sqrt(dx * dx + dz * dz));
                }
            }
        }
        return new Fallen(count, farthest);
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
                Consumable.BOLT.perPurchase(),
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
