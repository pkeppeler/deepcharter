package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.fuel.EngineSoot;
import io.github.pkeppeler.deepcharter.fuel.FuelRegistry;
import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodFuelItems;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Run;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Zone;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a trip earns on each fuel (#1280), for a player who does the rational thing and burns a fuel for many trips,
 * servicing when the engine forces it. Lines start with {@code [fuel-economy]} and make the PR's table.
 *
 * <p>The trip is {@link EarlyRunModel}'s: its ore, litres and seconds. The engine is the real one: {@link EngineSoot}
 * soots as the litres burn, its loss of output and burn are applied, and when it is in limp mode at the colony the
 * player pays the real service and clears it, until the engine has forced three services. Nothing is forced that the
 * player would not do. A coal pilot who never pays, and only scrapes the flue for free, is measured too: the scrape's
 * wait at the station is its price.
 *
 * <p>The effort has no invented constants. It is derived from the game's own data: the seconds to fell a fungal stalk
 * (the block's destroy time with bare hands, measured here), the cook times of the real recipes (a furnace for
 * charcoal, a campfire or a smoker for the mash), and the colony trip's 45 s off the pod ({@code
 * docs/design/mechanics.md}, "First-tier parts"). Cooking is passive and batched, so the model gives two bounds:
 * hands-on only (the felling) and waiting for each cook in turn. The ordering is asserted at both, and at half the
 * felling time, and the break-even prep time is printed.
 */
public class FuelTripEconomyTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(FuelTripEconomyTest.class);

    /**
     * The seconds a colony trip spends off the pod: the walk to the pump and the processor, boarding, fuel and sale.
     */
    private static final double WALK_SECONDS = 45;

    /** The litres of a slice of the burn; the engine soots and slows as it goes. */
    private static final float SLICE = 0.05f;

    private static final int SERVICES = 3;

    /** How far ahead of coal, a minute, the pump must be on the stock trip: the reason to fill up at the colony. */
    private static final double PUMP_MARGIN_OVER_COAL = 1.10;

    /** Hands-on and waiting seconds to put one item of a fuel in the pack, derived. */
    private record Prep(double handsOn, double withWaiting) {}

    /** What a strategy cost and earned over trips: the same trip, burned over and over. */
    private record Result(
            String fuel, int trips, int services, double litres, double cash, double gross, double minutes) {
        double cashPerLitre() {
            return cash / litres;
        }

        double netPerTrip() {
            return (gross - cash) / trips;
        }

        double perMinute() {
            return (gross - cash) / minutes;
        }
    }

    private static double seconds(GameTestHelper helper, ServerLevel level, MockPlayer player, Block block, Item tool) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        player.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(tool));
        BlockState state = block.defaultBlockState();
        float progress = state.getDestroyProgress(player.player(), level, pos);
        expect(helper, progress > 0f, "%s should be breakable, progress %s", block, progress);
        return 1.0 / progress / 20.0;
    }

    private static int cookTicks(
            GameTestHelper helper,
            RecipeType<? extends net.minecraft.world.item.crafting.AbstractCookingRecipe> type,
            Item input) {
        ServerLevel level = helper.getLevel();
        return level.recipeAccess()
                .getRecipeFor(type, new SingleRecipeInput(new ItemStack(input)), level)
                .map(holder -> holder.value().cookingTime())
                .orElseThrow(() -> helper.assertionException("no %s recipe for %s", type, input));
    }

    private static int craftCount(GameTestHelper helper, ItemStack ingredient) {
        ServerLevel level = helper.getLevel();
        CraftingInput input = CraftingInput.of(1, 1, List.of(ingredient));
        return level.recipeAccess()
                .getRecipeFor(RecipeType.CRAFTING, input, level)
                .map(holder -> holder.value().assemble(input).getCount())
                .orElse(0);
    }

    /**
     * Burns the trip over and over until the engine has forced {@link #SERVICES} visits to the station: a paid service
     * each, or, when {@code scrapeOnly}, the free flue scrape and its wait.
     */
    private static Result trips(
            PodEntity pod,
            String name,
            Run run,
            double seconds,
            @Nullable Item fuel,
            Prep prep,
            boolean waiting,
            boolean scrapeOnly) {
        EngineSoot.burned(pod, 1_000_000f);
        EngineSoot.flush(pod);
        EngineSoot.service(pod);
        FuelTuning tuning = FuelTuning.DEFAULT;
        float perLitre = fuel == null ? 0f : PodFuelItems.sootOf(new ItemStack(fuel));
        float tank = PodStats.of(pod).tankLitres();
        int trips = 0;
        int services = 0;
        double litres = 0;
        double cash = 0;
        double minutes = 0;
        double itemsBurned = 0;
        while (services < SERVICES && trips < (fuel == null ? 200 : 5000)) {
            if (EngineSoot.isLimp(pod)) {
                // At the colony the pod cannot launch in limp mode: the player pays the service and clears it, or
                // scrapes the flue for free and waits.
                if (scrapeOnly) {
                    minutes += tuning.soot().scrapeTicks() / 20.0 / 60;
                    EngineSoot.scrape(pod);
                } else {
                    cash += EngineSoot.serviceCost(pod);
                    EngineSoot.service(pod);
                }
                services++;
                continue;
            }
            trips++;
            pod.setFuel(100f);
            if (fuel != null) {
                EngineSoot.refuelled(pod, tank, perLitre);
            }
            double base = run.litres();
            double burned = 0;
            while (base > 1e-9) {
                float slice = (float) Math.min(SLICE, base);
                float used = slice * EngineSoot.burnMultiplier(pod);
                pod.setFuel(Math.max(0f, pod.fuel() - used / tank * 100f));
                EngineSoot.burned(pod, used);
                burned += used;
                base -= slice;
            }
            EngineSoot.flush(pod);
            litres += burned;
            if (fuel == null) {
                cash += burned * tuning.pricePerLitre();
            } else {
                itemsBurned +=
                        burned / PodFuelItems.litresOf(new ItemStack(fuel)).orElseThrow();
            }
            minutes += (seconds + WALK_SECONDS) / 60;
        }
        if (fuel != null) {
            minutes += itemsBurned * (waiting ? prep.withWaiting() : prep.handsOn()) / 60;
        }
        return new Result(name, trips, services, litres, cash, run.gross() * trips, minutes);
    }

    private static void table(
            GameTestHelper helper,
            String label,
            boolean stock,
            Run run,
            double seconds,
            Prep charcoal,
            Prep coal,
            Prep biofuel) {
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        try {
            for (boolean waiting : new boolean[] {false, true}) {
                String bound = waiting ? "waiting for each cook" : "hands-on only";
                Result pump = trips(pod, "pump", run, seconds, null, charcoal, waiting, false);
                Result bio = trips(pod, "biofuel", run, seconds, FuelRegistry.BIOFUEL, biofuel, waiting, false);
                Result coalR = trips(pod, "coal", run, seconds, Items.COAL, coal, waiting, false);
                Result charR = trips(pod, "charcoal", run, seconds, Items.CHARCOAL, charcoal, waiting, false);
                Result coalScraped = trips(pod, "coal, scrape only", run, seconds, Items.COAL, coal, waiting, true);
                for (Result r : List.of(pump, bio, coalR, charR, coalScraped)) {
                    LOGGER.info(
                            "[fuel-economy] {} ({}): {}: {} trips, {} services, {} L burned, ${} cash = ${} a litre, net ${} a trip, ${} a minute",
                            label,
                            bound,
                            r.fuel(),
                            r.trips(),
                            r.services(),
                            r.litres(),
                            r.cash(),
                            r.cashPerLitre(),
                            r.netPerTrip(),
                            r.perMinute());
                }
                String line = String.format(
                        "%s (%s): a minute pump %.2f, biofuel %.2f, coal %.2f, charcoal %.2f; cash a litre pump %.2f, biofuel %.2f, coal %.2f, charcoal %.2f",
                        label,
                        bound,
                        pump.perMinute(),
                        bio.perMinute(),
                        coalR.perMinute(),
                        charR.perMinute(),
                        pump.cashPerLitre(),
                        bio.cashPerLitre(),
                        coalR.cashPerLitre(),
                        charR.cashPerLitre());
                // Cash: pump fuel is cheapest of the soot fuels, which is the only reason to carry one, the field.
                expect(
                        helper,
                        pump.cashPerLitre() < coalR.cashPerLitre() && coalR.cashPerLitre() < charR.cashPerLitre(),
                        "pump fuel should cost less cash a litre than coal and coal less than charcoal, with the services: %s",
                        line);
                // A pump trip out-earns every solid-fuel trip a minute, biofuel included.
                for (Result solid : List.of(bio, coalR, charR)) {
                    expect(
                            helper,
                            pump.perMinute() > solid.perMinute(),
                            "a trip on pump fuel should earn more a minute than one on %s: %s",
                            solid.fuel(),
                            line);
                }
                if (stock) {
                    LOGGER.info(
                            "[fuel-economy] {} ({}): pump is {}% a minute over coal",
                            label, bound, 100 * (pump.perMinute() / coalR.perMinute() - 1));
                    expect(
                            helper,
                            pump.perMinute() >= coalR.perMinute() * PUMP_MARGIN_OVER_COAL,
                            "the pump should earn at least %s%% a minute more than coal on the stock trip: %s",
                            100 * (PUMP_MARGIN_OVER_COAL - 1),
                            line);
                }
                // The free scrape is the way out for a broke pilot, never a strategy: a coal pilot who only scrapes
                // earns less a minute than one who services, and less than the pump.
                LOGGER.info(
                        "[fuel-economy] {} ({}): coal a minute, scrape only {}, serviced {}, pump {}",
                        label,
                        bound,
                        coalScraped.perMinute(),
                        coalR.perMinute(),
                        pump.perMinute());
                expect(
                        helper,
                        coalScraped.perMinute() < coalR.perMinute() && coalScraped.perMinute() < pump.perMinute(),
                        "a coal pilot who only scrapes should earn less a minute (%s) than one who services (%s) and"
                                + " than the pump (%s): %s",
                        coalScraped.perMinute(),
                        coalR.perMinute(),
                        pump.perMinute(),
                        line);
                expect(
                        helper,
                        charR.services() == SERVICES
                                && coalR.services() == SERVICES
                                && bio.services() == SERVICES
                                && coalScraped.services() == SERVICES,
                        "every solid fuel should have reached the limit and been serviced %s times: %s",
                        SERVICES,
                        line);
            }
            // The break-even: the most prep time per biofuel item at which a biofuel trip would still match the pump.
            Result pumpTrip = trips(pod, "pump", run, seconds, null, biofuel, false, false);
            Result bioFree = trips(pod, "biofuel", run, seconds, FuelRegistry.BIOFUEL, new Prep(0, 0), false, false);
            double breakEven = breakEvenPrep(pumpTrip, bioFree, FuelRegistry.BIOFUEL);
            LOGGER.info(
                    "[fuel-economy] {}: biofuel matches pump a minute only if an item takes {} s or less to prepare; derived hands-on {} s, waiting {} s",
                    label,
                    breakEven,
                    biofuel.handsOn(),
                    biofuel.withWaiting());
            expect(
                    helper,
                    breakEven < biofuel.handsOn() / 2,
                    "biofuel should stay below pump a minute even if felling were twice as fast: break-even %s s, hands-on %s s",
                    breakEven,
                    biofuel.handsOn());
        } finally {
            pod.discard();
        }
    }

    /** Prep seconds an item can take before biofuel's net a minute falls to the pump's. */
    private static double breakEvenPrep(Result pump, Result biofuelFree, Item fuel) {
        double items = biofuelFree.litres()
                / PodFuelItems.litresOf(new ItemStack(fuel)).orElseThrow();
        // net_b / (t_b + items * p / 60) = net_p / t_p, solved for p.
        double netB = biofuelFree.gross() - biofuelFree.cash();
        double netP = pump.gross() - pump.cash();
        double minutesAtPump = netB / (netP / pump.minutes());
        return (minutesAtPump - biofuelFree.minutes()) * 60 / items;
    }

    @GameTest
    public void aTripOnPumpFuelOutEarnsEverySolidFuelAFirstTripAndALongerOne(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MockPlayer player = MockPlayers.join(helper, "effort-meter");
        try {
            Block stalk =
                    BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("deepcharter", "fungal_stalk"));
            Item stalkItem =
                    BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("deepcharter", "fungal_stalk"));
            double fell = seconds(helper, level, player, stalk, Items.AIR);
            double mine = seconds(helper, level, player, Blocks.COAL_BLOCK, Items.STONE_PICKAXE);
            int perBlock = craftCount(helper, new ItemStack(Items.COAL_BLOCK));
            int furnace = cookTicks(helper, RecipeType.SMELTING, stalkItem);
            int smoker = cookTicks(helper, RecipeType.SMOKING, stalkItem);
            int campfire = cookTicks(helper, RecipeType.CAMPFIRE_COOKING, stalkItem);
            int mash = 3;
            LOGGER.info(
                    "[fuel-economy] derived effort: fell a stalk {} s bare-handed, mine a coal block {} s with a stone pickaxe for {} coal, furnace {} ticks, smoker {} ticks, campfire {} ticks",
                    fell,
                    mine,
                    perBlock,
                    furnace,
                    smoker,
                    campfire);
            expect(helper, perBlock > 0, "a coal block should craft into coal");
            // Hands-on: the felling and the mining. Waiting: add each cook in turn (the fastest, the smoker, for
            // the mash).
            Prep charcoal = new Prep(fell, fell + furnace / 20.0);
            Prep coal = new Prep(mine / perBlock, mine / perBlock);
            Prep biofuel = new Prep(mash * fell, mash * (fell + smoker / 20.0));
            Zone zone = Zone.load("topsoil_claims");
            PodStats stock = PodStats.base();
            Run first = EarlyRunModel.run(zone, stock, 0);
            table(
                    helper,
                    "stock Mole, " + String.format("%.2f", first.litres()) + " L",
                    true,
                    first,
                    EarlyRunModel.runSeconds(zone, stock, 0, first),
                    charcoal,
                    coal,
                    biofuel);
            PodStats big = EarlyRunModel.mole(0, 3, 0);
            Run longer = EarlyRunModel.run(zone, big, 0);
            table(
                    helper,
                    "tier 3 tank, " + String.format("%.2f", longer.litres()) + " L",
                    false,
                    longer,
                    EarlyRunModel.runSeconds(zone, big, 0, longer),
                    charcoal,
                    coal,
                    biofuel);
            helper.succeed();
        } finally {
            player.leave();
        }
    }
}
