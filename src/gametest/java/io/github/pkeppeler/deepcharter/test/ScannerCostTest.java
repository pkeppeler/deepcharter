package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.scanner.ScannerRegistry;
import io.github.pkeppeler.deepcharter.test.BootstrapCost.Needs;
import io.github.pkeppeler.deepcharter.test.BootstrapCost.Spread;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Run;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Zone;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server GameTests for #483, the cost of the crafted scanner against the bootstrap's timeline (mechanics.md, "The crafted scanner"). What the
 * recipe asks comes from the shipped recipe file, what a crate holds from the real loot table with fixed seeds (as {@link SalvageRenewalTest}
 * does), and the minutes from the walk to the yard, the break time of a crate and the furnace's cooking time, with the assumptions marked (A).
 * The kit is what a charter makes before its first run: the parts of the fuel pump, the ore processor and the upgrade terminal, and the Mole's
 * four parts at the hangar. No world is built.
 */
public class ScannerCostTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScannerCostTest.class);

    /** The scanner adds at most this share to the crates of the kit, and at most this many minutes to its time. */
    private static final double MOST_EXTRA_CRATE_SHARE = 0.25;

    private static final double MOST_EXTRA_MINUTES = 2.0;
    /** A salvage patch holds up to 8 crates (placed_feature/salvage_litter.json): the scanner alone is about one patch, and 90 starts in 100 need no more than this. */
    private static final int PATCH_CRATES = 8;

    private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }

    @GameTest(maxTicks = 200)
    public void theRecipeAsksForTheBootstrapsMetalAndNoMoreThanAFewCrates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Needs scanner = BootstrapCost.needs(BootstrapCost.path(ScannerRegistry.SCANNER_UNIT));
        Needs kit = BootstrapCost.kit();
        Spread alone = BootstrapCost.crates(level, scanner);
        Spread kitCrates = BootstrapCost.crates(level, kit);
        Spread both = BootstrapCost.crates(level, kit.plus(scanner));
        double extraShare = (both.mean() - kitCrates.mean()) / kitCrates.mean();
        double scannerMinutes = BootstrapCost.minutes(level, alone.mean(), scanner, 1);
        double kitMinutes = BootstrapCost.minutes(
                level, kitCrates.mean(), kit, BootstrapCost.kitParts().size());
        double bothMinutes = BootstrapCost.minutes(
                level, both.mean(), kit.plus(scanner), BootstrapCost.kitParts().size() + 1);
        LOGGER.info(
                "[scanner-cost] recipe: {} iron, {} copper, {} redstone",
                scanner.iron(),
                scanner.copper(),
                scanner.redstone());
        LOGGER.info(
                "[scanner-cost] kit before the first run: {} iron, {} copper, {} redstone",
                kit.iron(),
                kit.copper(),
                kit.redstone());
        LOGGER.info(
                "[scanner-cost] crates, the scanner alone: {}; the kit: {}; both: {}; the scanner adds {}%",
                alone, kitCrates, both, Math.round(extraShare * 100));
        LOGGER.info(
                "[scanner-cost] minutes, the scanner alone: {}; the kit: {}; both: {}; the scanner adds {}",
                String.format("%.1f", scannerMinutes),
                String.format("%.1f", kitMinutes),
                String.format("%.1f", bothMinutes),
                String.format("%.1f", bothMinutes - kitMinutes));
        if (alone.ninetieth() > PATCH_CRATES) {
            throw failure(
                    helper,
                    "the scanner alone takes up to %d crates in 90 starts of 100, more than one patch of %d",
                    alone.ninetieth(),
                    PATCH_CRATES);
        }
        if (extraShare > MOST_EXTRA_CRATE_SHARE) {
            throw failure(
                    helper,
                    "the scanner adds %.0f%% to the kit's crates, at most %.0f%% allowed",
                    extraShare * 100,
                    MOST_EXTRA_CRATE_SHARE * 100);
        }
        if (bothMinutes - kitMinutes > MOST_EXTRA_MINUTES) {
            throw failure(
                    helper,
                    "the scanner adds %.1f minutes to the kit's %.1f, at most %.1f allowed",
                    bothMinutes - kitMinutes,
                    kitMinutes,
                    MOST_EXTRA_MINUTES);
        }
        helper.succeed();
    }

    /**
     * The scanner used to be a $200 buy, two layer 1 runs of a stock Mole before the first one (EarlyRunModel); now its first tier costs no money, and
     * tier 2 keeps the standard ladder's price, which a layer 2 run pays in two runs at most (EconomyAffordabilityTest).
     */
    @GameTest
    public void theCraftedTierCostsNoMoneyAndTheNextTierKeepsItsPrice(GameTestHelper helper) {
        Run firstRun = EarlyRunModel.run(Zone.load("topsoil_claims"), PodStats.base(), 0);
        long oldPrice = 200;
        LOGGER.info(
                "[scanner-cost] the old tier 1 price of ${} was {} layer 1 runs of ${}",
                oldPrice,
                firstRun.toAfford(oldPrice),
                Math.round(firstRun.net()));
        if (UpgradeTuning.DEFAULT.price(ComponentTrack.SCANNER, 1) != 0
                || UpgradeTuning.DEFAULT.price(ComponentTrack.SCANNER, 2) != 500) {
            throw failure(
                    helper,
                    "the scanner's tier 1 is crafted (price 0) and tier 2 stays $500, got $%d and $%d",
                    UpgradeTuning.DEFAULT.price(ComponentTrack.SCANNER, 1),
                    UpgradeTuning.DEFAULT.price(ComponentTrack.SCANNER, 2));
        }
        helper.succeed();
    }
}
