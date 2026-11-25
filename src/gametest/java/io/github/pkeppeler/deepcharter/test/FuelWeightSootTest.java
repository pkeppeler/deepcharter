package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.fuel.EngineSoot;
import io.github.pkeppeler.deepcharter.fuel.FuelRegistry;
import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.ore.CargoWeight;
import io.github.pkeppeler.deepcharter.ore.OreEncumbrance;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodFuelItems;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.UseEntity;
import java.util.Locale;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Solid fuel is bulky, heavy and sooty (#1280). Each test prints its numbers on a line that starts with {@code
 * [fuel-economy]}, and puts them in its failure message, so the PR can carry them as a table.
 */
public class FuelWeightSootTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(FuelWeightSootTest.class);
    private static final float TOLERANCE = 0.001f;

    private static void near(GameTestHelper helper, String what, double expected, double actual) {
        expect(helper, Math.abs(expected - actual) <= TOLERANCE, "%s should be %s, got %s", what, expected, actual);
    }

    @GameTest
    public void coalCharcoalAndBiofuelStackToSixteenAndWeighWhatTheTuningSays(GameTestHelper helper) {
        for (Item fuel : new Item[] {Items.COAL, Items.CHARCOAL, FuelRegistry.BIOFUEL}) {
            ItemStack one = new ItemStack(fuel);
            float litres = (float) PodFuelItems.litresOf(one).orElseThrow();
            float mass = CargoWeight.of(one);
            LOGGER.info(
                    "[fuel-economy] {}: stack {}, {} L an item, {} mass an item, {} L a stack, {} mass a stack",
                    one.getHoverName().getString(),
                    one.getMaxStackSize(),
                    litres,
                    mass,
                    litres * one.getMaxStackSize(),
                    mass * one.getMaxStackSize());
            expect(helper, one.getMaxStackSize() == 16, "%s should stack to 16, got %s", fuel, one.getMaxStackSize());
            expect(helper, mass > 0f, "%s should weigh something, got %s", fuel, mass);
        }
        FuelTuning.Weight weight = FuelTuning.DEFAULT.weight();
        near(helper, "coal's mass", 0.6, weight.coal());
        near(helper, "charcoal's mass", 0.4, weight.charcoal());
        near(helper, "biofuel's mass", 0.5, weight.biofuel());
        near(helper, "a coal's weight", weight.coal(), CargoWeight.of(new ItemStack(Items.COAL)));
        near(helper, "16 charcoal's weight", 16 * weight.charcoal(), CargoWeight.of(new ItemStack(Items.CHARCOAL, 16)));
        near(helper, "a biofuel's weight", weight.biofuel(), CargoWeight.of(new ItemStack(FuelRegistry.BIOFUEL)));
        near(helper, "a stick's weight", 0, CargoWeight.of(new ItemStack(Items.STICK)));
        helper.succeed();
    }

    @GameTest
    public void charcoalIsOneLitreCoalTwoAndBiofuelThreeAndMoreIsCleanerPerSlot(GameTestHelper helper) {
        near(
                helper,
                "charcoal litres",
                1.0,
                PodFuelItems.litresOf(new ItemStack(Items.CHARCOAL)).orElseThrow());
        near(
                helper,
                "coal litres",
                2.0,
                PodFuelItems.litresOf(new ItemStack(Items.COAL)).orElseThrow());
        near(
                helper,
                "biofuel litres",
                3.0,
                PodFuelItems.litresOf(new ItemStack(FuelRegistry.BIOFUEL)).orElseThrow());
        near(helper, "charcoal soot", 1.0, PodFuelItems.sootOf(new ItemStack(Items.CHARCOAL)));
        near(helper, "coal soot", 0.5, PodFuelItems.sootOf(new ItemStack(Items.COAL)));
        near(helper, "biofuel soot", 0.0, PodFuelItems.sootOf(new ItemStack(FuelRegistry.BIOFUEL)));
        helper.succeed();
    }

    @GameTest
    public void aPilotsFuelPackCutsThePodsLiftAndSlowsThePilot(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        MockPlayer pilot = MockPlayers.join(helper, "fuel-hauler");
        try {
            expect(helper, pilot.player().startRiding(pod), "the pilot should mount the pod");
            near(helper, "extra mass before any fuel", 0, PodEvents.extraMass(pod));
            pilot.player().getInventory().add(new ItemStack(Items.COAL, 16));
            pilot.player().getInventory().add(new ItemStack(Items.COAL, 16));
            pilot.player().getInventory().add(new ItemStack(Items.CHARCOAL, 16));
            double expected = 32 * FuelTuning.DEFAULT.weight().coal()
                    + 16 * FuelTuning.DEFAULT.weight().charcoal();
            double lift = PodStats.of(pod).enginePower() - PodEvents.extraMass(pod);
            LOGGER.info(
                    "[fuel-economy] a pilot with 2 stacks of coal and 1 of charcoal: pod extra mass {} of {} engine power, "
                            + "lift left {}, pilot's carried mass {}",
                    PodEvents.extraMass(pod),
                    PodStats.of(pod).enginePower(),
                    lift,
                    OreEncumbrance.carriedMass(pilot.player()));
            near(helper, "the pod's extra mass with a fuel pack", expected, PodEvents.extraMass(pod));
            near(helper, "the pilot's carried mass", expected, OreEncumbrance.carriedMass(pilot.player()));
            helper.succeed();
        } finally {
            pilot.leave();
            pod.discard();
        }
    }

    /**
     * Burns {@code litres} of the pod's tank the way its tick does, through the soot, and answers what the engine
     * really burned.
     */
    private static float burn(PodEntity pod, float litres) {
        float burned = litres * EngineSoot.burnMultiplier(pod);
        EngineSoot.burned(pod, burned);
        return burned;
    }

    @GameTest
    public void charcoalFoulsTheEngineAfterFiveLitresBurnedAndAServiceClearsIt(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        MockPlayer player = MockPlayers.join(helper, "sooter");
        try {
            player.player().setGameMode(GameType.SURVIVAL);
            FuelTuning.Soot soot = FuelTuning.DEFAULT.soot();
            pod.setFuel(0f);
            for (int item = 0; item < 10; item++) {
                player.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CHARCOAL));
                UseEntity.on(player.player(), pod.level(), InteractionHand.MAIN_HAND, pod);
            }
            near(helper, "soot before anything burns", 0, EngineSoot.soot(pod));
            float first = burn(pod, soot.graceLitres());
            LOGGER.info(
                    "[fuel-economy] charcoal: {} L burned -> soot {}, burn x{}, fouled {}",
                    first,
                    EngineSoot.soot(pod),
                    EngineSoot.burnMultiplier(pod),
                    (EngineSoot.extraBurn(EngineSoot.soot(pod)) > 0));
            near(helper, "soot after the grace's litres of charcoal", soot.graceLitres(), EngineSoot.soot(pod));
            near(helper, "the burn at the grace", 1.0, EngineSoot.burnMultiplier(pod));
            expect(helper, !(EngineSoot.extraBurn(EngineSoot.soot(pod)) > 0), "the engine is not fouled at the grace");
            burn(pod, 1f);
            double expected = 1 + soot.penaltyPerLitre() * 1.0;
            LOGGER.info(
                    "[fuel-economy] charcoal: one litre more -> soot {}, burn x{}, fouled {}, service ${}",
                    EngineSoot.soot(pod),
                    EngineSoot.burnMultiplier(pod),
                    (EngineSoot.extraBurn(EngineSoot.soot(pod)) > 0),
                    EngineSoot.serviceCost(pod));
            expect(helper, (EngineSoot.extraBurn(EngineSoot.soot(pod)) > 0), "past the grace the engine is fouled");
            near(helper, "the burn one litre past the grace", expected, EngineSoot.burnMultiplier(pod));
            expect(
                    helper,
                    EngineSoot.serviceCost(pod) == (long) Math.ceil(EngineSoot.soot(pod) * soot.servicePerLitre()),
                    "a service should cost the soot's litres at $%s, got $%s",
                    soot.servicePerLitre(),
                    EngineSoot.serviceCost(pod));
            EngineSoot.service(pod);
            near(helper, "soot after a service", 0, EngineSoot.soot(pod));
            near(helper, "the burn after a service", 1.0, EngineSoot.burnMultiplier(pod));
            // Never past the cap, however much burns.
            EngineSoot.burned(pod, 1f);
            for (int i = 0; i < 100; i++) {
                EngineSoot.refuelled(pod, 1f, 1f);
                EngineSoot.burned(pod, 1f);
            }
            near(
                    helper,
                    "the burn of a fouled engine at its cap",
                    1 + soot.maxPenalty(),
                    EngineSoot.burnMultiplier(pod));
            helper.succeed();
        } finally {
            player.leave();
            pod.discard();
        }
    }

    @GameTest
    public void coalFoulsHalfAsFastAsCharcoalAndBiofuelAndPumpFuelBurnClean(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        MockPlayer player = MockPlayers.join(helper, "clean-burner");
        try {
            player.player().setGameMode(GameType.SURVIVAL);
            float[] sootPerFuel = new float[3];
            Item[] fuels = {Items.CHARCOAL, Items.COAL, FuelRegistry.BIOFUEL};
            for (int i = 0; i < fuels.length; i++) {
                EngineSoot.service(pod);
                pod.setFuel(0f);
                int items = Math.round(6
                        / (float) PodFuelItems.litresOf(new ItemStack(fuels[i])).orElseThrow());
                for (int n = 0; n < items; n++) {
                    player.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(fuels[i]));
                    UseEntity.on(player.player(), pod.level(), InteractionHand.MAIN_HAND, pod);
                }
                EngineSoot.burned(pod, 6f);
                sootPerFuel[i] = EngineSoot.soot(pod);
                EngineSoot.burned(pod, 100f);
                EngineSoot.service(pod);
            }
            LOGGER.info(
                    "[fuel-economy] soot from 6 L burned: charcoal {}, coal {}, biofuel {}",
                    sootPerFuel[0],
                    sootPerFuel[1],
                    sootPerFuel[2]);
            near(helper, "soot from 6 L of charcoal", 6.0, sootPerFuel[0]);
            near(helper, "soot from 6 L of coal", 3.0, sootPerFuel[1]);
            near(helper, "soot from 6 L of biofuel", 0.0, sootPerFuel[2]);
            // Pump fuel is not an item: litres bought leave no soot to come.
            EngineSoot.burned(pod, 6f);
            near(helper, "soot from 6 L of pump fuel", 0, EngineSoot.soot(pod));
            helper.succeed();
        } finally {
            player.leave();
            pod.discard();
        }
    }

    @GameTest(maxTicks = 160)
    public void aRealTickingPodBurnsItsSolidFuelIntoSootAndTheFirstTimeItSaysSo(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        MockPlayer pilot = MockPlayers.join(helper, "soot-reader");
        try {
            expect(helper, pilot.player().startRiding(pod), "the pilot should mount the pod");
            pod.setFuel(50f);
            EngineSoot.refuelled(pod, 100f, 1f);
            // Fill the engine to the grace in one go, then let the pod's own tick take the next litres.
            EngineSoot.burned(pod, FuelTuning.DEFAULT.soot().graceLitres() - 0.01f);
            float before = EngineSoot.soot(pod);
            helper.runAfterDelay(100, () -> {
                try {
                    float grown = EngineSoot.soot(pod) - before;
                    float idleLitres = PodStats.of(pod).idleLitresPerSecond() * 5f;
                    LOGGER.info(
                            "[fuel-economy] a real pod idling 5 s on charcoal: soot grew {} L for {} L burned",
                            grown,
                            idleLitres);
                    expect(
                            helper,
                            grown > 0 && Math.abs(grown - idleLitres) < idleLitres * 0.2f,
                            "5 s of idle burn should leave about %s L of soot, got %s",
                            idleLitres,
                            grown);
                    expect(
                            helper,
                            pilot.chatMessages().stream()
                                    .anyMatch(line -> line.getString()
                                            .toLowerCase(Locale.ROOT)
                                            .contains("soot")),
                            "the pilot should be told once that soot fouls the engine, heard %s",
                            pilot.chatMessages());
                    helper.succeed();
                } finally {
                    pilot.leave();
                    pod.discard();
                }
            });
        } catch (RuntimeException e) {
            pilot.leave();
            pod.discard();
            throw failure(helper, "setup failed: %s", e);
        }
    }
}
