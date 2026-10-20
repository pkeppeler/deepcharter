package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.market.MarketTuning;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodLiningTuning;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.surface.SurfaceBlocks;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Run;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Zone;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server GameTests for #470, the renewable wood and scrap (mechanics.md, "Renewable salvage"): the ore processor presses a pod's spoil
 * into salvage crates. What a run of a hopper pod brings home comes from {@link EarlyRunModel}, and what a crate holds from the real loot
 * table with fixed seeds. The conversion itself, on a real pod and a real terminal, is pinned in {@link OreProcessorTest}. No world is built.
 */
public class SalvageRenewalTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(SalvageRenewalTest.class);

    /** Seeds to roll a crate's table with: enough that the mean of its uniform counts is within a few per cent of the exact value. */
    private static final int SAMPLED_CRATES = 4000;
    /** What the hopper cost at the terminal before its tier 1 became a crafted unit (#536): the recipe takes about the minutes that price took to earn. */
    private static final long HOPPER_OLD_PRICE = 100;

    /** The iron, copper and redstone of the four colony terminals (SalvageBootstrapTest, ADR 0047). */
    private static final int CHAIN_IRON = 23;

    private static final int CHAIN_COPPER = 21;
    private static final int CHAIN_REDSTONE = 13;

    /** A pod that presses every layer 1 run must not rebuild the colony within the first hour: this many runs at least. */
    private static final int MIN_RUNS_TO_PRESS_THE_CHAIN = 10;
    /** And a long world must not be starved: the iron of a tow cable (6) comes within this many runs. */
    private static final int MAX_RUNS_FOR_A_TOW_CABLE = 4;

    private static final int TOW_CABLE_IRON = 6;

    /** What a smelt costs in fuel: a wooden block burns 300 ticks and a smelt takes 200. */
    private static final double SMELTS_PER_PLANK = 1.5;

    private static Item item(String path) {
        return BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
    }

    @FormatMethod
    private static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }

    /** The mean of each item over {@value #SAMPLED_CRATES} crates broken, from the real table. */
    private static Map<Item, Double> meanDrops(GameTestHelper helper) {
        Map<Item, Long> total = new LinkedHashMap<>();
        for (int crate = 1; crate <= SAMPLED_CRATES; crate++) {
            for (ItemStack drop : SalvageBootstrapTest.drops(helper.getLevel(), SurfaceBlocks.SALVAGE_CRATE, crate)) {
                total.merge(drop.getItem(), (long) drop.getCount(), Long::sum);
            }
        }
        Map<Item, Double> mean = new LinkedHashMap<>();
        total.forEach((drop, count) -> mean.put(drop, (double) count / SAMPLED_CRATES));
        return mean;
    }

    private static double mean(Map<Item, Double> drops, Item what) {
        return drops.getOrDefault(what, 0.0);
    }

    /** The spoil a stock Mole with a hopper brings home from a layer 1 run: the slowest income of the press, and the one a new charter has. */
    private static int spoilOfALayerOneRun() {
        Zone zone = Zone.load("topsoil_claims");
        return EarlyRunModel.spoil(
                Chassis.MOLE, zone, EarlyRunModel.run(zone, PodStats.base(), 0, EarlyRunModel.hopperMass()));
    }

    /** Crates a layer 1 run presses, in expectation: the spoil that stays in the bay between runs is not lost, so the rate is a fraction. */
    private static double cratesPerLayerOneRun() {
        return (double) spoilOfALayerOneRun() / MarketTuning.DEFAULT.spoilPerCrate();
    }

    /**
     * A layer 1 run of a hopper pod brings home at least a crate's spoil and no more than a bay holds, so a run is worth pressing and
     * the bay does not cap the press.
     */
    @GameTest
    public void aHopperRunInLayerOneBringsHomeMoreThanACratesSpoil(GameTestHelper helper) {
        Zone zone = Zone.load("topsoil_claims");
        Run run = EarlyRunModel.run(zone, PodStats.base(), 0, EarlyRunModel.hopperMass());
        int spoil = spoilOfALayerOneRun();
        LOGGER.info(
                "[renewal] hopper pod, layer 1: {} slabs, {} spoil of a {} bay, {} crates",
                run.slabs(),
                spoil,
                PodLiningTuning.DEFAULT.spoilCapacity(),
                cratesPerLayerOneRun());
        if (spoil < MarketTuning.DEFAULT.spoilPerCrate() || spoil > PodLiningTuning.DEFAULT.spoilCapacity()) {
            throw failure(
                    helper,
                    "a layer 1 run keeps %d spoil, expected from a crate's %d to a bay's %d",
                    spoil,
                    MarketTuning.DEFAULT.spoilPerCrate(),
                    PodLiningTuning.DEFAULT.spoilCapacity());
        }
        helper.succeed();
    }

    /**
     * The press needs spoil, spoil needs the hopper, and the hopper takes the minutes of a whole early run: the first run of a charter cannot press a
     * crate. The yard's crates are the start (ADR 0047), and the press is the long game.
     */
    @GameTest
    public void thePressNeedsTheHopperWhichTakesAWholeRunToBuy(GameTestHelper helper) {
        Run firstRun = EarlyRunModel.run(Zone.load("topsoil_claims"), PodStats.base(), 0);
        // The hopper's tier 1 is a crafted unit (#536); its recipe is tuned to the minutes its old $100 took to earn
        // (FirstTierPartsCostTest).
        long hopper = HOPPER_OLD_PRICE;
        LOGGER.info("[renewal] the hopper took ${} to earn, a stock run nets ${}", hopper, Math.round(firstRun.net()));
        if (firstRun.toAfford(hopper) != 1) {
            throw failure(
                    helper,
                    "the hopper costs $%d, which is %d stock runs of $%.0f; one expected",
                    hopper,
                    firstRun.toAfford(hopper),
                    firstRun.net());
        }
        helper.succeed();
    }

    /**
     * A layer 1 run presses under two crates, so the colony's whole repair chain by the press alone is a long job, not the first hour: the
     * crates round the colony are still the bootstrap. The chain's crates are counted from the real loot table.
     */
    @GameTest
    public void pressingTheWholeRepairChainTakesTenRunsOrMore(GameTestHelper helper) {
        Map<Item, Double> drops = meanDrops(helper);
        double cratesForIron = CHAIN_IRON / mean(drops, item("scrap_iron"));
        double cratesForCopper = CHAIN_COPPER / mean(drops, item("scrap_copper"));
        double cratesForRedstone = CHAIN_REDSTONE / mean(drops, Items.REDSTONE);
        int crates = (int) Math.ceil(Math.max(cratesForIron, Math.max(cratesForCopper, cratesForRedstone)));
        int runs = (int) Math.ceil(crates / cratesPerLayerOneRun());
        LOGGER.info(
                "[renewal] a crate holds {}; the chain takes {} crates (iron {}, copper {}, redstone {}), {} layer 1 runs at {} crates a run",
                drops,
                crates,
                cratesForIron,
                cratesForCopper,
                cratesForRedstone,
                runs,
                cratesPerLayerOneRun());
        if (runs < MIN_RUNS_TO_PRESS_THE_CHAIN) {
            throw failure(
                    helper,
                    "the repair chain takes %d crates, %d layer 1 runs at %.2f a run; at least %d runs expected",
                    crates,
                    runs,
                    cratesPerLayerOneRun(),
                    MIN_RUNS_TO_PRESS_THE_CHAIN);
        }
        helper.succeed();
    }

    /** And it is not a starvation: the iron of a tow cable, the dearest iron item a charter makes more than once, comes within a few runs. */
    @GameTest
    public void aTowCablesIronIsPressedWithinAFewRuns(GameTestHelper helper) {
        double ironPerCrate = mean(meanDrops(helper), item("scrap_iron"));
        int runs = (int) Math.ceil(TOW_CABLE_IRON / (ironPerCrate * cratesPerLayerOneRun()));
        LOGGER.info(
                "[renewal] {} iron a crate, {} crates a layer 1 run: a tow cable's {} iron in {} runs",
                ironPerCrate,
                cratesPerLayerOneRun(),
                TOW_CABLE_IRON,
                runs);
        if (runs > MAX_RUNS_FOR_A_TOW_CABLE) {
            throw failure(
                    helper,
                    "a tow cable's %d iron takes %d runs of the press; at most %d expected",
                    TOW_CABLE_IRON,
                    runs,
                    MAX_RUNS_FOR_A_TOW_CABLE);
        }
        helper.succeed();
    }

    /**
     * A pressed crate pays for its own fuel: its planks burn longer than its scrap takes to smelt, so the press renews wood as well as
     * metal and a charter never needs a stalk for the furnace.
     */
    @GameTest
    public void aCratesPlanksSmeltMoreThanItsScrap(GameTestHelper helper) {
        Map<Item, Double> drops = meanDrops(helper);
        double smelts = mean(drops, Items.OAK_PLANKS) * SMELTS_PER_PLANK;
        double scrap = mean(drops, item("scrap_iron")) + mean(drops, item("scrap_copper"));
        LOGGER.info("[renewal] a crate's planks smelt {}, its scrap needs {}", smelts, scrap);
        if (smelts < scrap) {
            throw failure(helper, "a crate's planks smelt %.2f items but its scrap needs %.2f", smelts, scrap);
        }
        helper.succeed();
    }

    /**
     * The press touches no money: nothing a crate drops sells at the ore processor, so the early-run economy, the upgrade prices and the
     * teleport items of SPEC section 11 stay where {@code EconomyAffordabilityTest} pins them.
     */
    @GameTest
    public void noCrateDropIsAnOreAndSoThePressAddsNoIncome(GameTestHelper helper) {
        for (Item drop : meanDrops(helper).keySet()) {
            if (OreRegistry.typeOf(new ItemStack(drop)).isPresent()) {
                throw failure(helper, "a crate drops %s, which the ore processor buys", drop);
            }
        }
        helper.succeed();
    }
}
