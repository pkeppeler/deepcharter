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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a trip earns on each fuel (#1280): the same trip, depth and ore, burned on pump fuel and on each solid fuel, in credits a
 * minute of effort. Lines start with {@code [fuel-economy]} and make the PR's table.
 *
 * <p>The trip is {@link EarlyRunModel}'s: its ore, litres and seconds. The engine is the real one: {@link EngineSoot} fouls as the
 * litres burn and charges the service, and a fouled engine burns more, so the litres grow as the soot does. (A) The effort model is
 * the hands-on seconds to put one item in the pack ({@link #PREP_SECONDS}), and {@link #WALK_SECONDS} for the walk, the boarding, the
 * fuel and the sale that a colony trip pays whatever it burns. Fuel from the pump is paid for at the pump's price, and solid fuel
 * costs its service bill.
 */
public class FuelTripEconomyTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(FuelTripEconomyTest.class);

    /** (A) Seconds of hands-on work for one item: fell a stalk and char it (charcoal), mine a colony coal block (coal), fell three stalks, cook three mash and craft (biofuel). */
    private static final Map<Item, Double> PREP_SECONDS =
            Map.of(Items.CHARCOAL, 10.0, Items.COAL, 6.0, FuelRegistry.BIOFUEL, 26.0);
    /** (A) The seconds a colony trip spends off the pod: the walk to the pump and the processor, boarding, fuel and the sale. */
    private static final double WALK_SECONDS = 45;
    /** The litres of a slice of the burn; the engine fouls as it goes. */
    private static final float SLICE = 0.05f;

    /** One trip on one fuel: what it cost, what it earned and the effort it took. */
    private record Result(String fuel, double litres, double soot, double dollars, double net, double minutes) {
        double perMinute() {
            return net / minutes;
        }
    }

    private static Result trip(PodEntity pod, String name, Run run, double seconds, @Nullable Item fuel) {
        // Burn off what the last trip left in the tank, then clear the soot it made.
        EngineSoot.burned(pod, 1_000_000f);
        EngineSoot.service(pod);
        float perLitre = fuel == null ? 0f : PodFuelItems.sootOf(new ItemStack(fuel));
        if (fuel != null) {
            EngineSoot.refuelled(pod, 1_000f, perLitre);
        }
        double base = run.litres();
        double burned = 0;
        while (base > 1e-9) {
            float slice = (float) Math.min(SLICE, base);
            float litres = slice * EngineSoot.burnMultiplier(pod);
            EngineSoot.burned(pod, litres);
            burned += litres;
            base -= slice;
        }
        double soot = EngineSoot.soot(pod);
        double dollars;
        double prep = 0;
        if (fuel == null) {
            dollars = burned * FuelTuning.DEFAULT.pricePerLitre();
        } else {
            dollars = EngineSoot.serviceCost(pod);
            double litresAnItem = PodFuelItems.litresOf(new ItemStack(fuel)).orElseThrow();
            prep = burned / litresAnItem * Objects.requireNonNull(PREP_SECONDS.get(fuel), "no prep time for " + fuel);
        }
        return new Result(name, burned, soot, dollars, run.gross() - dollars, (seconds + WALK_SECONDS + prep) / 60);
    }

    /** The same trip on each fuel. */
    private record Table(Result pump, Result biofuel, Result coal, Result charcoal) {}

    private static Table table(GameTestHelper helper, String label, Run run, double seconds) {
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        try {
            Table results = new Table(
                    trip(pod, "pump", run, seconds, null),
                    trip(pod, "biofuel", run, seconds, FuelRegistry.BIOFUEL),
                    trip(pod, "coal", run, seconds, Items.COAL),
                    trip(pod, "charcoal", run, seconds, Items.CHARCOAL));
            for (Result r : List.of(results.pump(), results.biofuel(), results.coal(), results.charcoal())) {
                LOGGER.info(
                        "[fuel-economy] {}: {} -> {} L burned, soot {} L, fuel costs ${}, ore ${}, net ${} in {} min = ${} a minute",
                        label,
                        r.fuel(),
                        r.litres(),
                        r.soot(),
                        r.dollars(),
                        run.gross(),
                        r.net(),
                        r.minutes(),
                        r.perMinute());
            }
            return results;
        } finally {
            pod.discard();
        }
    }

    private static void ordered(GameTestHelper helper, String label, Table r) {
        String line = String.format(
                "%s: pump $%.2f net %.2f a minute, biofuel $%.2f %.2f, coal $%.2f %.2f, charcoal $%.2f %.2f",
                label,
                r.pump().net(),
                r.pump().perMinute(),
                r.biofuel().net(),
                r.biofuel().perMinute(),
                r.coal().net(),
                r.coal().perMinute(),
                r.charcoal().net(),
                r.charcoal().perMinute());
        expect(
                helper,
                r.charcoal().net() < r.pump().net()
                        && r.charcoal().perMinute() < r.pump().perMinute(),
                "a trip on charcoal should earn less than one on pump fuel, in a trip and in a minute. %s",
                line);
        expect(
                helper,
                r.charcoal().perMinute() < r.biofuel().perMinute()
                        && r.biofuel().perMinute() < r.pump().perMinute(),
                "a trip on biofuel should earn between charcoal and pump fuel a minute. %s",
                line);
        expect(
                helper,
                r.charcoal().perMinute() < r.coal().perMinute()
                        && r.coal().perMinute() < r.pump().perMinute(),
                "a trip on coal should earn between charcoal and pump fuel a minute. %s",
                line);
    }

    @GameTest
    public void aFirstTripOnCharcoalEarnsLessThanOnPumpFuelAndBiofuelLiesBetween(GameTestHelper helper) {
        Zone zone = Zone.load("topsoil_claims");
        PodStats stock = PodStats.base();
        Run run = EarlyRunModel.run(zone, stock, 0);
        ordered(
                helper,
                "stock Mole, layer 1",
                table(helper, "stock Mole, layer 1", run, EarlyRunModel.runSeconds(zone, stock, 0, run)));
        helper.succeed();
    }

    @GameTest
    public void aLongerTripFoulsTheEngineAndOnCharcoalStillEarnsLess(GameTestHelper helper) {
        Zone zone = Zone.load("topsoil_claims");
        // A tank of tier 3 runs the pricing share of a bigger tank: more litres, so the soot passes the grace.
        PodStats big = EarlyRunModel.mole(0, 3, 0);
        Run run = EarlyRunModel.run(zone, big, 0);
        Table results =
                table(helper, "Mole with tier 3 tank, layer 1", run, EarlyRunModel.runSeconds(zone, big, 0, run));
        ordered(helper, "Mole with tier 3 tank, layer 1", results);
        expect(
                helper,
                results.charcoal().soot() > FuelTuning.DEFAULT.soot().graceLitres(),
                "the longer trip should burn charcoal past the %s L grace, soot was %s",
                FuelTuning.DEFAULT.soot().graceLitres(),
                results.charcoal().soot());
        helper.succeed();
    }
}
