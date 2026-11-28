package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.CharterData;
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
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.Inputs;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.test.support.UseEntity;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Solid fuel is bulky, heavy and sooty (#1280). Each test prints its numbers on a line that starts with {@code
 * [fuel-economy]}, and puts them in its failure message, so the PR can carry them as a table.
 */
public class FuelWeightSootTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(FuelWeightSootTest.class);
    private static final float TOLERANCE = 0.001f;
    /** Pod ticks a sprinting pod is given to start a bore of the stone under it. */
    private static final int DRILL_TICKS = 80;
    /** Ticks until a mock has read what the server sent it: it reads its packets on its own tick. */
    private static final int HEARD_LAG = 2;

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
    public void charcoalIsOneLitreCoalTwoAndBiofuelThreeWithSootInThatOrder(GameTestHelper helper) {
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
        near(helper, "coal soot", 0.75, PodFuelItems.sootOf(new ItemStack(Items.COAL)));
        near(helper, "biofuel soot", 0.1, PodFuelItems.sootOf(new ItemStack(FuelRegistry.BIOFUEL)));
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
                    "[fuel-economy] a pilot with 2 stacks of coal and 1 of charcoal: pod extra mass {} of {} engine"
                            + " power, lift left {}, pilot's carried mass {}",
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

    /** Burns {@code litres} on the pod's solid fuel and writes it, as the pod's tick does a quantum at a time. */
    private static void burn(PodEntity pod, float litres) {
        EngineSoot.burned(pod, litres);
        EngineSoot.flush(pod);
    }

    private static void fill(PodEntity pod, MockPlayer player, Item fuel, int items) {
        pod.setFuel(0f);
        for (int item = 0; item < items; item++) {
            player.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(fuel));
            UseEntity.on(player.player(), pod.level(), InteractionHand.MAIN_HAND, pod);
        }
    }

    @GameTest
    public void charcoalSootsTheEngineSlowerThenLimpsAtTheLimitAndAServiceClearsIt(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        MockPlayer player = MockPlayers.join(helper, "sooter");
        try {
            player.player().setGameMode(GameType.SURVIVAL);
            FuelTuning.Soot soot = FuelTuning.DEFAULT.soot();
            PodStats clean = PodStats.of(pod);
            fill(pod, player, Items.CHARCOAL, 10);
            near(helper, "soot before anything burns", 0, EngineSoot.soot(pod));
            burn(pod, soot.graceLitres());
            near(helper, "soot at the grace", soot.graceLitres(), EngineSoot.soot(pod));
            near(helper, "the output at the grace", 1.0, EngineSoot.output(EngineSoot.soot(pod)));
            near(
                    helper,
                    "the top speed at the grace",
                    clean.horizontalSpeed(),
                    PodStats.of(pod).horizontalSpeed());
            // One litre past the grace is one ninth of the way to the limit: 1 - 0.5 / 9 and 1 + 0.5 / 9.
            burn(pod, 1f);
            PodStats sooted = PodStats.of(pod);
            LOGGER.info(
                    "[fuel-economy] charcoal 4 L of soot: output {}, burn x{}, top speed {} of {}, climb {} of {},"
                            + " service ${}",
                    EngineSoot.output(4f),
                    EngineSoot.burn(4f),
                    sooted.horizontalSpeed(),
                    clean.horizontalSpeed(),
                    sooted.maxClimbSpeed(),
                    clean.maxClimbSpeed(),
                    EngineSoot.serviceCost(pod));
            near(helper, "the output one litre past the grace", 1 - 0.5 / 9, EngineSoot.output(4f));
            near(helper, "the burn one litre past the grace", 1 + 0.5 / 9, EngineSoot.burnMultiplier(pod));
            near(
                    helper,
                    "the top speed with 4 L of soot",
                    clean.horizontalSpeed() * (1 - 0.5 / 9),
                    sooted.horizontalSpeed());
            near(
                    helper,
                    "the climb speed with 4 L of soot",
                    clean.maxClimbSpeed() * (1 - 0.5 / 9),
                    sooted.maxClimbSpeed());
            near(helper, "the engine power with 4 L of soot", clean.enginePower(), sooted.enginePower());
            expect(helper, !EngineSoot.isLimp(pod), "4 L of soot is not limp mode");
            expect(
                    helper,
                    EngineSoot.serviceCost(pod) == 4 * soot.servicePerLitre(),
                    "a service of 4 L should cost $%s a litre, got $%s",
                    soot.servicePerLitre(),
                    EngineSoot.serviceCost(pod));
            // Past the limit: limp mode, which burns double and moves at 30%, and still climbs.
            for (int i = 0; i < 10; i++) {
                EngineSoot.refuelled(pod, 1f, 1f);
            }
            burn(pod, soot.limpLitres() - 4f);
            PodStats limp = PodStats.of(pod);
            LOGGER.info(
                    "[fuel-economy] charcoal {} L of soot: limp, output {}, burn x{}, top speed {} of {}, climb {} of"
                            + " {}",
                    EngineSoot.soot(pod),
                    EngineSoot.output(EngineSoot.soot(pod)),
                    EngineSoot.burnMultiplier(pod),
                    limp.horizontalSpeed(),
                    clean.horizontalSpeed(),
                    limp.maxClimbSpeed(),
                    clean.maxClimbSpeed());
            expect(helper, EngineSoot.isLimp(pod), "%s L of soot should be limp mode", EngineSoot.soot(pod));
            near(helper, "the limp burn", soot.limpBurn(), EngineSoot.burnMultiplier(pod));
            near(helper, "the limp top speed", clean.horizontalSpeed() * soot.limpOutput(), limp.horizontalSpeed());
            near(helper, "the limp climb speed", clean.maxClimbSpeed() * soot.limpOutput(), limp.maxClimbSpeed());
            expect(
                    helper,
                    limp.maxClimbSpeed() > 0 && limp.horizontalSpeed() > 0,
                    "limp mode must still move and climb");
            near(helper, "the limp engine power", clean.enginePower(), limp.enginePower());
            EngineSoot.service(pod);
            near(helper, "soot after a service", 0, EngineSoot.soot(pod));
            near(helper, "the burn after a service", 1.0, EngineSoot.burnMultiplier(pod));
            near(
                    helper,
                    "the top speed after a service",
                    clean.horizontalSpeed(),
                    PodStats.of(pod).horizontalSpeed());
            helper.succeed();
        } finally {
            player.leave();
            pod.discard();
        }
    }

    @GameTest
    public void coalSootsThreeQuartersAsMuchAsCharcoalAndBiofuelATenthPerLitre(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        MockPlayer player = MockPlayers.join(helper, "clean-burner");
        try {
            player.player().setGameMode(GameType.SURVIVAL);
            float[] sootPerFuel = new float[3];
            Item[] fuels = {Items.CHARCOAL, Items.COAL, FuelRegistry.BIOFUEL};
            for (int i = 0; i < fuels.length; i++) {
                EngineSoot.service(pod);
                int items = Math.round(6
                        / (float) PodFuelItems.litresOf(new ItemStack(fuels[i])).orElseThrow());
                fill(pod, player, fuels[i], items);
                burn(pod, 6f);
                sootPerFuel[i] = EngineSoot.soot(pod);
                burn(pod, 100f);
                EngineSoot.service(pod);
            }
            LOGGER.info(
                    "[fuel-economy] soot from 6 L burned: charcoal {}, coal {}, biofuel {}",
                    sootPerFuel[0],
                    sootPerFuel[1],
                    sootPerFuel[2]);
            near(helper, "soot from 6 L of charcoal", 6.0, sootPerFuel[0]);
            near(helper, "soot from 6 L of coal", 4.5, sootPerFuel[1]);
            near(helper, "soot from 6 L of biofuel", 0.6, sootPerFuel[2]);
            // Pump fuel is not an item: litres bought leave no soot to come.
            burn(pod, 6f);
            near(helper, "soot from 6 L of pump fuel", 0, EngineSoot.soot(pod));
            helper.succeed();
        } finally {
            player.leave();
            pod.discard();
        }
    }

    @GameTest
    public void aTankAccountIsNeverMoreThanTheLitresReallyInTheTank(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        MockPlayer player = MockPlayers.join(helper, "drifter");
        try {
            player.player().setGameMode(GameType.SURVIVAL);
            fill(pod, player, Items.CHARCOAL, 10);
            // The tank drops to a litre behind the account's back: the account says 10 L of charcoal.
            pod.setFuel(10f);
            burn(pod, 0.5f);
            // Then the pump fills it, and the pod burns a whole tank of pump fuel: none of that is solid.
            pod.setFuel(100f);
            burn(pod, 10f);
            LOGGER.info(
                    "[fuel-economy] a tank account left over from 10 L of charcoal, 1 L in the tank: soot {}",
                    EngineSoot.soot(pod));
            expect(
                    helper,
                    EngineSoot.soot(pod) <= 1.5f + 0.001f,
                    "only the litre that was in the tank (and the 0.5 L burned) is solid: soot should be at most 1.5,"
                            + " got %s",
                    EngineSoot.soot(pod));
            helper.succeed();
        } finally {
            player.leave();
            pod.discard();
        }
    }

    @GameTest(maxTicks = 360)
    public void aRealTickingPodWritesItsSootAQuantumAtATimeAndSaysSoTheFirstTime(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        MockPlayer pilot = MockPlayers.join(helper, "soot-reader");
        expect(helper, pilot.player().startRiding(pod), "the pilot should mount the pod");
        pod.setFuel(50f);
        EngineSoot.refuelled(pod, 100f, 1f);
        // One more quantum of soot takes the engine over the grace.
        burn(pod, FuelTuning.DEFAULT.soot().graceLitres() - 0.05f);
        Object written = pod.getAttached(EngineSoot.STATE);
        float before = EngineSoot.soot(pod);
        helper.runAfterDelay(100, () -> {
            try {
                // Five seconds idle burns 0.045 L: under a quantum, so nothing is written or synced.
                expect(
                        helper,
                        pod.getAttached(EngineSoot.STATE) == written,
                        "5 s of idle burn (under %s L) should write nothing",
                        EngineSoot.QUANTUM);
            } catch (RuntimeException e) {
                pilot.leave();
                pod.discard();
                throw e;
            }
        });
        helper.runAfterDelay(300, () -> {
            try {
                float grown = EngineSoot.soot(pod) - before;
                float quantum = EngineSoot.QUANTUM;
                LOGGER.info("[fuel-economy] a real pod idling 15 s on charcoal wrote {} L of soot in one write", grown);
                expect(
                        helper,
                        grown >= quantum && grown < quantum + 0.05f,
                        "15 s of idle burn should have written one quantum of about %s L of soot, got %s",
                        quantum,
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
    }

    @GameTest
    public void aPodInLimpModeCannotLaunchFromTheColonyButIsFreeBelow(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        try {
            float limp = FuelTuning.DEFAULT.soot().limpLitres();
            pod.setAttached(EngineSoot.STATE, Versioned.of(new EngineSoot.State(limp, 0f, 0f)));
            // The helper's level is the test overworld, the surface: the colony, its repair station working.
            WorldData.with(helper.getLevel().getServer(), RepairState.TYPE, stationOnline(), () -> {
                expect(helper, EngineSoot.launchRefused(pod), "a pod in limp mode at the colony should not launch");
                pod.setAttached(EngineSoot.STATE, Versioned.of(new EngineSoot.State(limp - 0.5f, 0f, 0f)));
                expect(helper, !EngineSoot.launchRefused(pod), "a pod under the limit launches");
            });
            helper.succeed();
        } finally {
            pod.discard();
        }
    }

    /** A fresh repair state with the chain repaired up to the repair station, so it services and scrapes. */
    private static RepairState stationOnline() {
        return HandbookChaptersOneToFiveTest.repairedFor(
                TerminalTypes.FUEL_PUMP,
                TerminalTypes.ORE_PROCESSOR,
                TerminalTypes.UPGRADE_TERMINAL,
                TerminalTypes.REPAIR_STATION);
    }

    /** A stone floor under the pod's footprint, its middle block returned. */
    private static BlockPos stoneFloor(GameTestHelper helper) {
        return stoneFloor(helper, 1);
    }

    /** A stone floor under a pod's footprint from {@code minX}, its middle block returned. */
    private static BlockPos stoneFloor(GameTestHelper helper, int minX) {
        for (int x = minX; x <= minX + 2; x++) {
            for (int z = 1; z <= 3; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        return new BlockPos(minX + 1, 1, 2);
    }

    /**
     * Ticks {@code pod} {@code ticks} times within this server tick, its pilot sprinting (drilling down), with {@code
     * repairs} as the world's repair state, then puts the world's back. The repair state is world-global and swapped
     * for one tick only ({@code WorldData}), so the ticks are run here, as {@code ServerLevel.tickNonPassenger} runs
     * them; the input is released after, so the pod's own ticks drill nothing.
     */
    private static void sprintWith(
            GameTestHelper helper, RepairState repairs, PodEntity pod, MockPlayer pilot, int ticks) {
        WorldData.with(helper.getLevel().getServer(), RepairState.TYPE, repairs, () -> sprint(pod, pilot, ticks));
    }

    /** Ticks {@code pod} {@code ticks} times within this server tick, its pilot sprinting, as {@link #sprintWith}. */
    private static void sprint(PodEntity pod, MockPlayer pilot, int ticks) {
        pilot.setInput(Inputs.SPRINT);
        try {
            for (int tick = 0; tick < ticks; tick++) {
                pod.tickCount++;
                pod.tick();
            }
        } finally {
            pilot.releaseInput();
        }
    }

    /**
     * The launch gate on the drill's tick, against saved data this build cannot read: the repair state, then the
     * charters with the repair station online. Either way the station refuses every action, so the gate must neither
     * throw nor hold the pod.
     */
    @GameTest
    public void aLimpPodAtTheColonyDrillsWhenTheRepairStateOrChartersAreUnreadable(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        drillsWithUnreadable(helper, 1, "unreadable-repairs", Runnable::run, RepairState.TYPE);
        drillsWithUnreadable(
                helper,
                5,
                "unreadable-charters",
                body -> WorldData.with(server, RepairState.TYPE, stationOnline(), body),
                CharterData.TYPE);
        helper.succeed();
    }

    /**
     * A limp pod on a stone floor from {@code minX}, its pilot sprinting with {@code unreadable} swapped for data this
     * build cannot read inside {@code around}: the gate and the sprint must not throw, and the pod must drill.
     */
    private static void drillsWithUnreadable(
            GameTestHelper helper, int minX, String pilotName, Consumer<Runnable> around, SavedDataType<?> unreadable) {
        BlockPos below = stoneFloor(helper, minX);
        PodEntity pod = helper.spawn(PodRegistry.POD, below.getX() + 0.5f, 2f, 2.5f);
        MockPlayer pilot = MockPlayers.join(helper, pilotName);
        try {
            expect(helper, pilot.player().startRiding(pod), "the pilot should mount the pod");
            pod.setAttached(
                    EngineSoot.STATE,
                    Versioned.of(new EngineSoot.State(FuelTuning.DEFAULT.soot().limpLitres() + 1f, 0f, 0f)));
            Map<String, Runnable> paths = new LinkedHashMap<>();
            paths.put(
                    "launch gate",
                    () -> expect(
                            helper,
                            !EngineSoot.launchRefused(pod),
                            "the gate holds a limp pod at a station that cannot act"));
            paths.put("sprint", () -> sprint(pod, pilot, DRILL_TICKS));
            MinecraftServer server = helper.getLevel().getServer();
            around.accept(() ->
                    UnreadableChecks.assertSavedDataNoThrow(helper, "the limp launch gate", server, unreadable, paths));
            expect(helper, EngineSoot.isLimp(pod), "the pod should still be limp");
            expect(
                    helper,
                    pod.drilling() || !helper.getBlockState(below).is(Blocks.STONE),
                    "with %s unreadable a limp pod should drill",
                    unreadable.id());
        } finally {
            pilot.leave();
            pod.discard();
        }
    }

    @GameTest
    public void aLimpPodAtTheColonyDrillsWhileTheRepairStationIsOffline(GameTestHelper helper) {
        BlockPos below = stoneFloor(helper);
        PodEntity pod = helper.spawn(PodRegistry.POD, 2.5f, 2f, 2.5f);
        MockPlayer pilot = MockPlayers.join(helper, "offline-limper");
        try {
            expect(helper, pilot.player().startRiding(pod), "the pilot should mount the pod");
            pod.setAttached(
                    EngineSoot.STATE,
                    Versioned.of(new EngineSoot.State(FuelTuning.DEFAULT.soot().limpLitres() + 1f, 0f, 0f)));
            // A fresh world's repair state: the repair station, which services and scrapes, is not repaired yet.
            sprintWith(helper, new RepairState(), pod, pilot, DRILL_TICKS);
            expect(helper, EngineSoot.isLimp(pod), "the pod should still be limp");
            expect(
                    helper,
                    pod.drilling() || !helper.getBlockState(below).is(Blocks.STONE),
                    "with the repair station offline a limp pod has no way to be serviced, so it should drill");
        } catch (RuntimeException e) {
            pilot.leave();
            pod.discard();
            throw e;
        }
        helper.runAfterDelay(HEARD_LAG, () -> {
            try {
                expect(
                        helper,
                        pilot.actionBarMessages().isEmpty(),
                        "no refusal should send the pilot to an offline station, heard %s",
                        pilot.actionBarMessages());
                helper.succeed();
            } finally {
                pilot.leave();
                pod.discard();
            }
        });
    }

    @GameTest
    public void aPodInLimpModeDoesNotDrillAtTheColonyUntilItIsServiced(GameTestHelper helper) {
        BlockPos below = stoneFloor(helper);
        PodEntity pod = helper.spawn(PodRegistry.POD, 2.5f, 2f, 2.5f);
        MockPlayer pilot = MockPlayers.join(helper, "limp-driller");
        try {
            expect(helper, pilot.player().startRiding(pod), "the pilot should mount the pod");
            pod.setAttached(
                    EngineSoot.STATE,
                    Versioned.of(new EngineSoot.State(FuelTuning.DEFAULT.soot().limpLitres() + 1f, 0f, 0f)));
            sprintWith(helper, stationOnline(), pod, pilot, DRILL_TICKS);
            expect(helper, !pod.drilling(), "a pod in limp mode at the colony should not drill");
            expect(helper, helper.getBlockState(below).is(Blocks.STONE), "the floor should be whole");
        } catch (RuntimeException e) {
            pilot.leave();
            pod.discard();
            throw e;
        }
        helper.runAfterDelay(HEARD_LAG, () -> {
            try {
                expect(
                        helper,
                        pilot.actionBarMessages().stream()
                                .anyMatch(line -> line.getString()
                                        .toLowerCase(Locale.ROOT)
                                        .contains("clogged")),
                        "the pilot should be told why, heard %s",
                        pilot.actionBarMessages());
                EngineSoot.service(pod);
                sprintWith(helper, stationOnline(), pod, pilot, DRILL_TICKS);
                expect(
                        helper,
                        pod.drilling() || !helper.getBlockState(below).is(Blocks.STONE),
                        "once serviced the same pod should drill the floor");
                helper.succeed();
            } finally {
                pilot.leave();
                pod.discard();
            }
        });
    }
}
