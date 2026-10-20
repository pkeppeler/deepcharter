package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.hangar.HangarTuning;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.market.WorkOrder;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.HardLanding;
import io.github.pkeppeler.deepcharter.pod.PodBrace;
import io.github.pkeppeler.deepcharter.pod.PodBraceTuning;
import io.github.pkeppeler.deepcharter.pod.PodDrill;
import io.github.pkeppeler.deepcharter.pod.PodRockBiteTuning;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Run;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Zone;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server GameTests for #201: the early purchases cost a number of early runs that a new charter will accept. The income comes
 * from {@link EarlyRunModel}, which works it out from the ore tables and the pod's stats. No world is built.
 *
 * <p>Each purchase is counted from an empty account, with no purchase before it. The bounds are the issue's: a tier 1 part after
 * one or two early runs, the Prospector restore near the end of onboarding.
 */
public class EconomyAffordabilityTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(EconomyAffordabilityTest.class);

    /** The thermal scanner tier, tier 2, is bought with this many layer 2 runs at most (tier 1 of every track is crafted, #483, #536). */
    private static final int EARLY_BUY_RUNS = 2;
    /** The liner's tier 2 and the seep sounder's, in runs of a Mole with tier 2 parts in layer 2, at most. Tier 1 of each is crafted. */
    private static final int LINER_TIER_TWO_RUNS = 4;

    private static final int SOUNDER_TIER_TWO_RUNS = 4;
    /** The console's tiers 2 and 3 (#502) are layer 2 buys, in runs of a Mole with tier 2 parts at most. Tier 1 is a crafted unit (FirstTierPartsCostTest). */
    private static final int CONSOLE_TIER_TWO_RUNS = 2;

    private static final int CONSOLE_TIER_THREE_RUNS = 4;
    /** The radiator matters from layer 3 on, and its first purchase, tier 2 now that tier 1 is crafted, costs what the others' tier 3 does: this many layer 2 runs of a Mole with tier 2 parts, at most. */
    private static final int RADIATOR_RUNS = 5;
    /** The Prospector restore takes this many runs of a Mole with tier 2 parts in layer 2, at least and at most. */
    private static final int RESTORE_RUNS_MIN = 3;

    private static final int RESTORE_RUNS_MAX = 6;
    /**
     * Cicatrium sells for $1,500. What a run finds of it in the deepest zone is at most this share of the run's other income (it is
     * about a fifth today). Half is room for a retune of the ore and still a stop for one that makes selling it the main income.
     */
    private static final double CATALYST_SALE_SHARE = 0.5;
    /** A straight layer 1 shaft, paid in blasting charges, costs this many stock layer 1 runs at most (#509). */
    private static final int STRAIGHT_SHAFT_RUNS = 2;
    /** A later charter's first refurbished Mole, with no pod yet, and its second one. */
    private static final int REFURBISHED_FIRST_RUNS = 2;

    private static final int REFURBISHED_SECOND_RUNS = 3;

    /**
     * Each repair station item against the run of the layer where it starts to matter (the transmitter, a layer 3 item, is measured in
     * layer 2 runs), as a band of that run's net. See {@link Consumable} for the scale.
     */
    private record Target(boolean layerTwo, double minRuns, double maxRuns) {}

    private static final Map<Consumable, Target> ITEM_TARGETS = Map.of(
            Consumable.RESERVE_FUEL_TANK, new Target(false, 0.25, 1),
            Consumable.DYNAMITE, new Target(false, 0.25, 1),
            Consumable.QUANTUM_TELEPORTER, new Target(true, 1.5, 2),
            Consumable.PLASTIC_EXPLOSIVES, new Target(true, 0.25, 1),
            Consumable.BLASTING_CHARGE, new Target(false, 0.25, 1),
            Consumable.SHAPED_CHARGE, new Target(false, 0.25, 1),
            Consumable.HULL_NANOBOTS, new Target(true, 0.25, 1),
            Consumable.MATTER_TRANSMITTER, new Target(true, 3.5, 4));

    private static Run stockRunInLayerOne() {
        return EarlyRunModel.run(Zone.load("topsoil_claims"), PodStats.base(), 0);
    }

    private static Run upgradedRunInLayerTwo() {
        return upgradedRunInLayerTwo(0f);
    }

    private static Run upgradedRunInLayerTwo(float extraMass) {
        return EarlyRunModel.run(
                Zone.load("upper_levels"), EarlyRunModel.mole(2, 2, 2), EarlyRunModel.layerOneBlocks(), extraMass);
    }

    // assertionException(String, Object...) leaves the placeholders unfilled in the report.
    @FormatMethod
    private static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }

    /**
     * Tier 1 of every track is a crafted unit (#483, #536): the terminal charges nothing for it. What it costs is crates, a furnace, a bench and ore withdrawn
     * from the pod's cargo, in minutes against the minutes its old price took to earn (FirstTierPartsCostTest, ScannerCostTest). Tier 2 and up have their own bands below.
     */
    @GameTest
    public void everyTierOneIsCraftedAndCostsNoMoney(GameTestHelper helper) {
        LOGGER.info("[economy] stock Mole, layer 1 topsoil: {}", stockRunInLayerOne());
        for (ComponentTrack track : ComponentTrack.values()) {
            if (UpgradeTuning.DEFAULT.price(track, 1) != 0) {
                throw failure(
                        helper,
                        "tier 1 %s costs $%d, but it is crafted and costs no money",
                        track.id(),
                        UpgradeTuning.DEFAULT.price(track, 1));
            }
        }
        helper.succeed();
    }

    /**
     * The spoil hopper (#313) is the first lava counterplay. It carries a full bay and rack, which cut lift (W2): the climb and the Prospector's layer 2 runs
     * must come out as they do without it. Its unit takes about the minute of the one layer 1 run its $100 took (FirstTierPartsCostTest).
     */
    @GameTest
    public void aFullHopperBayDoesNotSlowTheClimb(GameTestHelper helper) {
        float mass = EarlyRunModel.hopperMass();
        PodStats stock = PodStats.base();
        Run run = EarlyRunModel.run(Zone.load("topsoil_claims"), stock, 0, mass);
        LOGGER.info(
                "[economy] spoil hopper: a layer 1 run with a full bay is {}; a full bay and rack weigh {} of {} engine power",
                run,
                mass,
                stock.enginePower());
        if (EarlyRunModel.climbSpeed(stock, mass) != stock.maxClimbSpeed()) {
            throw failure(
                    helper,
                    "a full bay and rack of mass %.1f slow the climb to %.3f from the rotor's %.3f",
                    mass,
                    EarlyRunModel.climbSpeed(stock, mass),
                    stock.maxClimbSpeed());
        }
        int restoreRuns = upgradedRunInLayerTwo(mass)
                .toAfford(HangarTuning.DEFAULT.restoreCost(Chassis.PROSPECTOR).money());
        if (restoreRuns != PROSPECTOR_RUNS) {
            throw failure(
                    helper,
                    "with a full bay and rack the Prospector restore takes %d layer 2 runs, expected %d",
                    restoreRuns,
                    PROSPECTOR_RUNS);
        }
        helper.succeed();
    }

    /**
     * The liner (#339) is the lava ladder's second rung and is bought in layer 2: a run there pays for tier 2 in at most {@value #LINER_TIER_TWO_RUNS} runs.
     * Tier 1 is a crafted unit (#536). The run is a hopper pod's (the liner lines from its rack, which the hopper fills) with the liner's drill penalty, so a dearer liner is a slower income.
     */
    @GameTest
    public void theLinerTierTwoIsAffordableAfterItsLayerTwoRuns(GameTestHelper helper) {
        Run run = EarlyRunModel.run(
                Zone.load("upper_levels"),
                EarlyRunModel.withLiner(EarlyRunModel.mole(2, 2, 2), 2),
                EarlyRunModel.layerOneBlocks(),
                EarlyRunModel.hopperMass());
        long price = UpgradeTuning.DEFAULT.price(ComponentTrack.LINER, 2);
        LOGGER.info("[economy] liner tier 2 ${}: {} layer 2 runs of {}", price, run.toAfford(price), run);
        if (run.toAfford(price) > LINER_TIER_TWO_RUNS) {
            throw failure(
                    helper,
                    "liner tier 2 costs $%d, which is %d layer 2 runs of $%.0f; at most %d are allowed",
                    price,
                    run.toAfford(price),
                    run.net(),
                    LINER_TIER_TWO_RUNS);
        }
        helper.succeed();
    }

    /**
     * The console (#502) answers the signal decay at the foot of layer 1. Its tier 1 is a crafted unit (#536, FirstTierPartsCostTest), so the terminal sells tiers 2 and 3, which are layer 2 buys:
     * {@value #CONSOLE_TIER_TWO_RUNS} and {@value #CONSOLE_TIER_THREE_RUNS} runs of a Mole with tier 2 parts at most. Tier 3 is a Prospector's (a Mole takes tier 2), so the immune console is not a Mole's first buy.
     */
    @GameTest
    public void theConsoleTiersTwoAndThreeAreAffordableAfterTheirLayerTwoRuns(GameTestHelper helper) {
        Run layerTwo = upgradedRunInLayerTwo();
        int[] allowed = {CONSOLE_TIER_TWO_RUNS, CONSOLE_TIER_THREE_RUNS};
        for (int tier = 2; tier <= ComponentTrack.CONSOLE.maxTier(); tier++) {
            long price = UpgradeTuning.DEFAULT.price(ComponentTrack.CONSOLE, tier);
            int runs = layerTwo.toAfford(price);
            LOGGER.info(
                    "[economy] console tier {} ${}: {} layer 2 runs of ${}",
                    tier,
                    price,
                    runs,
                    Math.round(layerTwo.net()));
            if (runs > allowed[tier - 2]) {
                throw failure(
                        helper,
                        "console tier %d costs $%d, which is %d runs; at most %d are allowed",
                        tier,
                        price,
                        runs,
                        allowed[tier - 2]);
            }
        }
        if (allowed.length != ComponentTrack.CONSOLE.maxTier() - 1) {
            throw failure(
                    helper,
                    "the test has bands for %d console tiers, the track has %d bought tiers",
                    allowed.length,
                    ComponentTrack.CONSOLE.maxTier() - 1);
        }
        helper.succeed();
    }

    /**
     * A lined run that reaches layer 2 nets more than an unlined layer 1 run (#363): the liner's drill penalty and the hopper's mass must not
     * sink a layer 2 run below the layer 1 run that a charter does without them. Slag brick costs no money, so there is no brick bill to take off.
     */
    @GameTest
    public void aLinedRunToLayerTwoNetsMoreThanAnUnlinedLayerOneRun(GameTestHelper helper) {
        Run layerOne = stockRunInLayerOne();
        Run lined = EarlyRunModel.run(
                Zone.load("upper_levels"),
                EarlyRunModel.withLiner(EarlyRunModel.mole(2, 2, 2), 2),
                EarlyRunModel.layerOneBlocks(),
                EarlyRunModel.hopperMass());
        LOGGER.info(
                "[economy] lined layer 2 run {}; an unlined layer 1 run nets ${}", lined, Math.round(layerOne.net()));
        if (lined.net() <= layerOne.net()) {
            throw failure(
                    helper,
                    "a lined run to layer 2 nets $%.0f, no more than the $%.0f of an unlined layer 1 run",
                    lined.net(),
                    layerOne.net());
        }
        helper.succeed();
    }

    /**
     * The breach brace (#378) burns ore for hull, and never at a dearer rate than Hull Nanobots charge for the same hull: its cap is the nanobots' price a hull times
     * the hull of one patch. The cheapest ore is a discount inside that, and an ore above the cap is refused (PodBraceTest pins the refusal on a pod).
     */
    @GameTest
    public void theBraceNeverCostsMoreAHullThanNanobotsAndTheCheapestOreIsADiscount(GameTestHelper helper) {
        float hullPerOre = PodBraceTuning.DEFAULT.hullPerOre();
        float nanobotsPerHull = (float) Consumable.HULL_NANOBOTS.price() / RepairTuning.DEFAULT.nanobotHp();
        int cheapest =
                Arrays.stream(OreType.values()).mapToInt(OreType::value).min().orElseThrow();
        LOGGER.info(
                "[economy] brace: cheapest ore ${} for {} hull is ${} a hull; nanobots ${} a hull; the cap is ${} an ore",
                cheapest,
                hullPerOre,
                cheapest / hullPerOre,
                nanobotsPerHull,
                PodBrace.oreValueCap());
        if (!(cheapest / hullPerOre > 0f && cheapest / hullPerOre < nanobotsPerHull)) {
            throw failure(
                    helper,
                    "the cheapest ore patches at $%.2f a hull, which must be under the nanobots' $%.2f",
                    cheapest / hullPerOre,
                    nanobotsPerHull);
        }
        if (Math.abs(PodBrace.oreValueCap() / hullPerOre - nanobotsPerHull) > 0.001f) {
            throw failure(
                    helper,
                    "the cap is the nanobots' rate: $%.2f an ore for %s hull, not $%.2f a hull",
                    PodBrace.oreValueCap(),
                    hullPerOre,
                    nanobotsPerHull);
        }
        for (OreType ore : OreType.values()) {
            boolean allowed = ore.value() <= PodBrace.oreValueCap();
            if (allowed != (ore.value() / hullPerOre <= nanobotsPerHull)) {
                throw failure(
                        helper,
                        "%s at $%d is %s by the cap but costs $%.2f a hull against the nanobots' $%.2f",
                        ore,
                        ore.value(),
                        allowed ? "allowed" : "refused",
                        ore.value() / hullPerOre,
                        nanobotsPerHull);
            }
        }
        helper.succeed();
    }

    /**
     * The seep sounder (#373) is the gas ladder's second rung and is bought in layer 2: a run there pays for tier 2 in at most
     * {@value #SOUNDER_TIER_TWO_RUNS} runs. Tier 1 is a crafted unit (#536). The run is a Mole's with tier 2 parts and the sounder's drill penalty, so a dearer sounder is a slower income.
     */
    @GameTest
    public void theSounderTierTwoIsAffordableAfterItsLayerTwoRuns(GameTestHelper helper) {
        Run run = EarlyRunModel.run(
                Zone.load("upper_levels"),
                EarlyRunModel.withSounder(EarlyRunModel.mole(2, 2, 2), 2),
                EarlyRunModel.layerOneBlocks());
        long price = UpgradeTuning.DEFAULT.price(ComponentTrack.SOUNDER, 2);
        LOGGER.info("[economy] sounder tier 2 ${}: {} layer 2 runs of {}", price, run.toAfford(price), run);
        if (run.toAfford(price) > SOUNDER_TIER_TWO_RUNS) {
            throw failure(
                    helper,
                    "sounder tier 2 costs $%d, which is %d layer 2 runs of $%.0f; at most %d are allowed",
                    price,
                    run.toAfford(price),
                    run.net(),
                    SOUNDER_TIER_TWO_RUNS);
        }
        helper.succeed();
    }

    /** The radiator's first purchase is its tier 2 (#536), a layer 2 buy: a run there, with tier 2 parts, pays for it in {@value #RADIATOR_RUNS} runs at most. */
    @GameTest
    public void theRadiatorsFirstPurchaseIsAffordableInAFewLayerTwoRuns(GameTestHelper helper) {
        Run run = upgradedRunInLayerTwo();
        long price = UpgradeTuning.DEFAULT.price(ComponentTrack.RADIATOR, 2);
        int runs = run.toAfford(price);
        LOGGER.info("[economy] radiator tier 2 ${}: {} layer 2 runs of {}", price, runs, run);
        if (runs > RADIATOR_RUNS) {
            throw failure(
                    helper,
                    "the radiator's tier 2 costs $%d, which is %d layer 2 runs of $%.0f; at most %d are allowed",
                    price,
                    runs,
                    run.net(),
                    RADIATOR_RUNS);
        }
        helper.succeed();
    }

    /**
     * The thermal tier (#300) is the scanner's tier 2, which the standard ladder prices with the other tier 2 parts. A tier 2 part is a
     * layer 2 buy: a run there, with tier 2 parts, pays for it in two runs at most.
     */
    @GameTest
    public void theThermalScannerTierIsAffordableAfterOneOrTwoLayerTwoRuns(GameTestHelper helper) {
        Run run = upgradedRunInLayerTwo();
        int tier = ScannerTuning.DEFAULT.lavaTier();
        long price = UpgradeTuning.DEFAULT.price(ComponentTrack.SCANNER, tier);
        int runs = run.toAfford(price);
        LOGGER.info("[economy] thermal scanner, tier {} ${}: {} layer 2 runs of {}", tier, price, runs, run);
        if (runs > EARLY_BUY_RUNS) {
            throw failure(
                    helper,
                    "the thermal scanner (tier %d) costs $%d, which is %d layer 2 runs of $%.0f; at most %d are allowed",
                    tier,
                    price,
                    runs,
                    run.net(),
                    EARLY_BUY_RUNS);
        }
        helper.succeed();
    }

    /**
     * Tank refills that a one-way bore of all of layer 1 takes, for a Mole whose tank is of tier 0 to 6, in the deepest zone (it
     * changes drill time per slab through the ore's hardness, so it is the dearest). Literals: a change to the tank ladder, the burn
     * rates, the drill or layer 1's height moves them.
     */
    private static final int[] BORE_TANKS_BY_TANK_TIER = {14, 10, 6, 4, 3, 2, 1};

    private static final int BORE_LITRES = 139;
    /** Litres (in tenths) of a braked drive down layer 1's 192 blocks at 0.6 blocks per tick: 16 s, the rotor on half of it. */
    private static final int DRIVE_DOWN_DECILITRES = 20;
    /** The terminal sink speed of a pod in blocks per tick: gravity 0.08 times drag 0.98 over the 0.02 the drag removes. */
    private static final double FREE_FALL_TERMINAL_SINK = 3.92;

    private static final int PROSPECTOR_RUNS = 4;

    @GameTest
    public void aOneWayBoreOfLayerOneTakesTheTanksThatTheLadderGives(GameTestHelper helper) {
        Zone zone = Zone.load("deep_claim");
        double litres = EarlyRunModel.boreLitres(zone, PodStats.base(), EarlyRunModel.layerOneBlocks());
        LOGGER.info("[fuel] one-way bore of layer 1: {} L", litres);
        if (BORE_TANKS_BY_TANK_TIER.length != ComponentTrack.FUEL_TANK.maxTier() + 1) {
            throw failure(
                    helper,
                    "the test pins %d tank tiers, the track has %d",
                    BORE_TANKS_BY_TANK_TIER.length,
                    ComponentTrack.FUEL_TANK.maxTier() + 1);
        }
        if (Math.round(litres) != BORE_LITRES) {
            throw failure(helper, "a one-way bore of layer 1 burns %.1f L, expected %d", litres, BORE_LITRES);
        }
        for (int tier = 0; tier < BORE_TANKS_BY_TANK_TIER.length; tier++) {
            int tanks = (int) Math.ceil(litres / EarlyRunModel.mole(0, tier, 0).tankLitres());
            LOGGER.info("[fuel] tank tier {}: {} tanks", tier, tanks);
            if (tanks != BORE_TANKS_BY_TANK_TIER[tier]) {
                throw failure(
                        helper,
                        "a tier %d tank needs %d refills for the bore, expected %d",
                        tier,
                        tanks,
                        BORE_TANKS_BY_TANK_TIER[tier]);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void aStockMoleDrivesDownItsOwnShaftBrakedButCannotFallDownIt(GameTestHelper helper) {
        PodStats stock = PodStats.base();
        double litres = EarlyRunModel.driveDownLitres(stock, EarlyRunModel.layerOneBlocks());
        LOGGER.info("[fuel] a braked drive down layer 1's shaft burns {} L of a {} L tank", litres, stock.tankLitres());
        if (Math.round(litres * 10) != DRIVE_DOWN_DECILITRES) {
            throw failure(
                    helper,
                    "a braked drive down the shaft burns %.2f L, expected %.1f",
                    litres,
                    DRIVE_DOWN_DECILITRES / 10.0);
        }
        if (EarlyRunModel.DRIVE_DOWN_SINK > stock.hardLandingSpeed()
                || HardLanding.hullDamage(stock, FREE_FALL_TERMINAL_SINK, 1f) < stock.maxHull()) {
            throw failure(
                    helper,
                    "a braked drive at %s should land softly and a free fall at %s should wreck a stock hull",
                    EarlyRunModel.DRIVE_DOWN_SINK,
                    FREE_FALL_TERMINAL_SINK);
        }
        helper.succeed();
    }

    /** The layer 2 runs assume a pod that starts at the bottom of layer 1's shaft; the Prospector takes this many of them. */
    @GameTest
    public void theProspectorRestoreTakesTheLayerTwoRunsThePlanSays(GameTestHelper helper) {
        int runs = upgradedRunInLayerTwo()
                .toAfford(HangarTuning.DEFAULT.restoreCost(Chassis.PROSPECTOR).money());
        if (runs != PROSPECTOR_RUNS) {
            throw failure(helper, "the Prospector restore takes %d layer 2 runs, expected %d", runs, PROSPECTOR_RUNS);
        }
        helper.succeed();
    }

    @GameTest
    public void everyTierCostsMoreThanTheOneBelow(GameTestHelper helper) {
        for (ComponentTrack track : ComponentTrack.values()) {
            for (int tier = 2; tier <= track.maxTier(); tier++) {
                long below = UpgradeTuning.DEFAULT.price(track, tier - 1);
                long price = UpgradeTuning.DEFAULT.price(track, tier);
                if (price < 2 * below) {
                    throw failure(
                            helper,
                            "%s tier %d costs $%d, under twice the $%d of tier %d",
                            track.id(),
                            tier,
                            price,
                            below,
                            tier - 1);
                }
            }
        }
        helper.succeed();
    }

    @GameTest
    public void theProspectorRestoreLandsNearTheEndOfOnboarding(GameTestHelper helper) {
        Run run = upgradedRunInLayerTwo();
        long money = HangarTuning.DEFAULT.restoreCost(Chassis.PROSPECTOR).money();
        int runs = run.toAfford(money);
        LOGGER.info(
                "[economy] Mole with tier 2 drill, tank and bay, layer 2 upper levels: {}; Prospector restore ${}: {} runs",
                run,
                money,
                runs);
        if (runs < RESTORE_RUNS_MIN || runs > RESTORE_RUNS_MAX) {
            throw failure(
                    helper,
                    "the Prospector restore costs $%d, which is %d runs of $%.0f; %d to %d are allowed",
                    money,
                    runs,
                    run.net(),
                    RESTORE_RUNS_MIN,
                    RESTORE_RUNS_MAX);
        }
        helper.succeed();
    }

    /** The invariant of issue 209: the Company's advance covers the restore's Cicatrium, so the ore never gates the Prospector. */
    @GameTest
    public void theCompanyAdvanceIsAtLeastTheCicatriumTheProspectorRestoreTakes(GameTestHelper helper) {
        HangarTuning.RestoreCost cost = HangarTuning.DEFAULT.restoreCost(Chassis.PROSPECTOR);
        Run run = EarlyRunModel.run(
                Zone.load("prospectors_run"), EarlyRunModel.mole(2, 2, 2), EarlyRunModel.layerOneBlocks());
        LOGGER.info(
                "[economy] Prospector restore: {} Cicatrium, {} advanced; from ore alone, at {} per run in the deepest zone, it would take {} runs",
                cost.catalysts(),
                cost.advance(),
                run.catalysts(),
                Math.ceil(cost.catalysts() / run.catalysts()));
        if (cost.advance() < cost.catalysts()) {
            throw failure(
                    helper,
                    "the Prospector restore takes %d Cicatrium but the Company advances only %d, so the ore gates it again",
                    cost.catalysts(),
                    cost.advance());
        }
        helper.succeed();
    }

    @GameTest
    public void cicatriumFoundInTheOreIsNotAnIncomeStream(GameTestHelper helper) {
        Zone deepest = Zone.load("prospectors_run");
        Run run = EarlyRunModel.run(deepest, EarlyRunModel.mole(2, 2, 2), EarlyRunModel.layerOneBlocks());
        double sold = run.catalysts() * OreType.CICATRIUM.value();
        LOGGER.info(
                "[economy] a layer 2 run in prospectors_run: {} Cicatrium, ${} if sold, against ${} of other ore",
                run.catalysts(),
                Math.round(sold),
                Math.round(run.net()));
        if (sold > CATALYST_SALE_SHARE * run.net()) {
            throw failure(
                    helper,
                    "Cicatrium sells for $%d, so a run's %.3f of it is $%.0f, over %.0f%% of the $%.0f that its other ore nets",
                    OreType.CICATRIUM.value(),
                    run.catalysts(),
                    sold,
                    CATALYST_SALE_SHARE * 100,
                    run.net());
        }
        helper.succeed();
    }

    @GameTest
    public void aRefurbishedMoleIsModestForALaterCharter(GameTestHelper helper) {
        Run run = stockRunInLayerOne();
        long first = HangarTuning.DEFAULT.refurbishedPrice(0);
        long second = HangarTuning.DEFAULT.refurbishedPrice(1);
        int firstRuns = run.toAfford(first);
        int secondRuns = run.toAfford(second);
        LOGGER.info(
                "[economy] refurbished Mole: ${} for the first pod ({} runs), ${} for the second ({} runs)",
                first,
                firstRuns,
                second,
                secondRuns);
        if (firstRuns > REFURBISHED_FIRST_RUNS) {
            throw failure(
                    helper,
                    "a first refurbished Mole costs $%d, which is %d runs of $%.0f; at most %d are allowed",
                    first,
                    firstRuns,
                    run.net(),
                    REFURBISHED_FIRST_RUNS);
        }
        if (secondRuns > REFURBISHED_SECOND_RUNS) {
            throw failure(
                    helper,
                    "a second refurbished Mole costs $%d, which is %d runs of $%.0f; at most %d are allowed",
                    second,
                    secondRuns,
                    run.net(),
                    REFURBISHED_SECOND_RUNS);
        }
        helper.succeed();
    }

    @GameTest
    public void mendingAStockHullCostsNoMoreThanOneRun(GameTestHelper helper) {
        Run run = stockRunInLayerOne();
        long full = Math.round(PodStats.base().maxHull()) * RepairTuning.DEFAULT.repairCostPerHp();
        LOGGER.info("[economy] full repair of the stock hull: ${} against a run of ${}", full, Math.round(run.net()));
        if (full > run.net()) {
            throw failure(
                    helper, "a full repair of the stock hull costs $%d, more than the $%.0f of a run", full, run.net());
        }
        helper.succeed();
    }

    @GameTest
    public void theFoundersHandsRewardIsWhatTheTenBronziumFetch(GameTestHelper helper) {
        WorkOrder order = WorkOrder.FOUNDERS_HANDS;
        long sold = (long) order.quantity() * OreType.BRONZIUM.value();
        if (order.reward() != sold) {
            throw failure(
                    helper,
                    "the order pays $%d, but its %d bronzium sell for $%d",
                    order.reward(),
                    order.quantity(),
                    sold);
        }
        helper.succeed();
    }

    @GameTest
    public void everyRepairItemIsAffordableForTheLayerItMattersIn(GameTestHelper helper) {
        if (ITEM_TARGETS.size() != Consumable.values().length) {
            throw failure(
                    helper,
                    "the test has targets for %d items, the shop sells %d",
                    ITEM_TARGETS.size(),
                    Consumable.values().length);
        }
        Run layerOne = stockRunInLayerOne();
        Run layerTwo = upgradedRunInLayerTwo();
        for (Consumable item : Consumable.values()) {
            Target target = Objects.requireNonNull(ITEM_TARGETS.get(item), () -> "no target for " + item);
            Run run = target.layerTwo() ? layerTwo : layerOne;
            double runs = item.price() / run.net();
            LOGGER.info("[economy] {} ${}: {} runs of ${}", item, item.price(), runs, Math.round(run.net()));
            if (runs > target.maxRuns() || runs < target.minRuns()) {
                throw failure(
                        helper,
                        "%s costs $%d, which is %.2f runs of $%.0f; %.2f to %.2f are allowed",
                        item,
                        item.price(),
                        runs,
                        run.net(),
                        target.minRuns(),
                        target.maxRuns());
            }
        }
        helper.succeed();
    }

    @GameTest
    public void theDearerItemOfEachPairCostsMore(GameTestHelper helper) {
        if (Consumable.DYNAMITE.price() >= Consumable.PLASTIC_EXPLOSIVES.price()) {
            throw failure(
                    helper,
                    "the plastic explosives outdo the dynamite, so they must cost more than its $%d, not $%d",
                    Consumable.DYNAMITE.price(),
                    Consumable.PLASTIC_EXPLOSIVES.price());
        }
        if (Consumable.QUANTUM_TELEPORTER.price() >= Consumable.MATTER_TRANSMITTER.price()) {
            throw failure(
                    helper,
                    "the transmitter must cost more than the teleporter's $%d, not $%d",
                    Consumable.QUANTUM_TELEPORTER.price(),
                    Consumable.MATTER_TRANSMITTER.price());
        }
        helper.succeed();
    }

    /**
     * The blasting charge (#509) is the price of a straight shaft. A straight layer 1 bore needs one charge for each slab the drill refuses (one charge clears 2
     * slab rows, so a refusal under a hole already blasted costs nothing more; the bore bot measured 5.3 a bore against the 5.4 counted here). The whole shaft must cost a
     * few stock layer 1 runs at most: a shaft is a one-off the charter keeps, but a price that no run could pay for would make the answer a joke.
     */
    @GameTest
    public void aStraightLayerOneShaftCostsAtMostTwoStockRunsInCharges(GameTestHelper helper) {
        int height = EarlyRunModel.layerOneBlocks();
        double slabCells = Chassis.MOLE.slabCells();
        double refusedSlabs = 0;
        for (int zone = 0; zone < Zones.COUNT; zone++) {
            String name = zone == 0 ? "topsoil_claims" : zone == 1 ? "stone_benches" : "deep_claim";
            Zones.Span span = Zones.span(0, height, zone);
            // Layer 1's crust (3 blocks) is bored by hand through the breach, not blasted.
            int blocks = span.size() - (zone == Zones.COUNT - 1 ? 3 : 0);
            double slabs = blocks / 2.0;
            refusedSlabs += slabs * (1 - Math.pow(1 - EarlyRunModel.companyRockChance(name), slabCells));
        }
        double cost = refusedSlabs * Consumable.BLASTING_CHARGE.price();
        Run run = stockRunInLayerOne();
        LOGGER.info(
                "[economy] straight layer 1 shaft: {} refused slabs, ${} in blasting charges, {} runs of ${}",
                refusedSlabs,
                Math.round(cost),
                cost / run.net(),
                Math.round(run.net()));
        if (refusedSlabs < 1) {
            throw failure(
                    helper,
                    "a layer 1 shaft meets %.2f refused slabs, so the charge has nothing to answer",
                    refusedSlabs);
        }
        if (cost > STRAIGHT_SHAFT_RUNS * run.net()) {
            throw failure(
                    helper,
                    "a straight layer 1 shaft needs about %.1f charges at $%d, $%.0f, more than %d stock runs of $%.0f",
                    refusedSlabs,
                    Consumable.BLASTING_CHARGE.price(),
                    cost,
                    STRAIGHT_SHAFT_RUNS,
                    run.net());
        }
        helper.succeed();
    }

    /**
     * The shaped charge (#526) is the dearer rung: it must cost more than the two blasting charges that clear a short stretch and less than the three that clear a thick one, so
     * it is the buy for a thick stretch and never for a short one.
     */
    @GameTest
    public void theShapedChargeCostsMoreThanTwoBlastingChargesAndLessThanThree(GameTestHelper helper) {
        long blasting = Consumable.BLASTING_CHARGE.price();
        long shaped = Consumable.SHAPED_CHARGE.price();
        if (shaped <= 2 * blasting || shaped >= 3 * blasting) {
            throw failure(
                    helper,
                    "the shaped charge costs $%d, it must be over two blasting charges ($%d) and under three ($%d)",
                    shaped,
                    2 * blasting,
                    3 * blasting);
        }
        if (RepairTuning.DEFAULT.shapedDepth() < 3 * RepairTuning.DEFAULT.chargeDepth()) {
            throw failure(
                    helper,
                    "a shaped charge reaches %d rows, it must reach the 3 x %d that three blasting charges do",
                    RepairTuning.DEFAULT.shapedDepth(),
                    RepairTuning.DEFAULT.chargeDepth());
        }
        helper.succeed();
    }

    /**
     * The drill that bites Company rock (#526) is slow and no cheaper per slab than a blasting charge: the tier buys freedom from carrying charges, not a discount, so charges stay the
     * cheaper, quicker answer for a few slabs. A bitten slab at layer 2 depth, in fuel and hull repair, must cost between one and two times a blasting charge's share of a slab (its price
     * over the rows it clears), and take at least five times as long as a stone slab. The tier's price is in the log: it is the Prospector's best drill, which a pilot buys for its speed.
     */
    @GameTest
    public void aBittenCompanyRockSlabCostsOneToTwoBlastingChargeSlabsAndTakesMuchLongerThanStone(
            GameTestHelper helper) {
        int tier = PodRockBiteTuning.DEFAULT.minTier();
        PodStats drill = EarlyRunModel.mole(tier, 0, 0);
        double depth = EarlyRunModel.layerOneBlocks() + 50;
        EarlyRunModel.Bite bite = EarlyRunModel.biteSlab(drill, depth);
        double chargePerSlab = (double) Consumable.BLASTING_CHARGE.price() / RepairTuning.DEFAULT.chargeDepth();
        double stoneSeconds = PodDrill.drillTicks(
                        drill, Blocks.STONE.defaultDestroyTime(), (int) (depth * LayerTuning.DEFAULT.feetPerBlock()))
                / 20.0;
        long price = UpgradeTuning.DEFAULT.price(ComponentTrack.DRILL, tier);
        Run layerTwo = upgradedRunInLayerTwo();
        LOGGER.info(
                "[economy] bitten slab at depth {}: {} s, {} L, {} hull, ${} against ${} a slab of a blasting charge and {} s of stone; the tier {} drill costs ${}, {} layer 2 runs of ${}",
                depth,
                bite.seconds(),
                bite.litres(),
                bite.hull(),
                bite.dollars(),
                chargePerSlab,
                stoneSeconds,
                tier,
                price,
                price / layerTwo.net(),
                Math.round(layerTwo.net()));
        if (bite.dollars() < chargePerSlab || bite.dollars() > 2 * chargePerSlab) {
            throw failure(
                    helper,
                    "a bitten slab costs $%.2f, it must be between one and two times a blasting charge's $%.2f a slab",
                    bite.dollars(),
                    chargePerSlab);
        }
        if (bite.seconds() < 5 * stoneSeconds) {
            throw failure(
                    helper,
                    "a bitten slab takes %.1f s, it must take at least five times a stone slab's %.1f s",
                    bite.seconds(),
                    stoneSeconds);
        }
        helper.succeed();
    }

    @GameTest
    public void theNanobotsCostMoreThanMendingTheSameHullAtTheStation(GameTestHelper helper) {
        long atStation = Math.round(RepairTuning.DEFAULT.nanobotHp()) * RepairTuning.DEFAULT.repairCostPerHp();
        if (Consumable.HULL_NANOBOTS.price() <= atStation) {
            throw failure(
                    helper,
                    "the nanobots cost $%d, no more than the $%d that the station charges for their %.0f HP",
                    Consumable.HULL_NANOBOTS.price(),
                    atStation,
                    RepairTuning.DEFAULT.nanobotHp());
        }
        helper.succeed();
    }
}
