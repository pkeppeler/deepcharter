package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.JUMP;

import io.github.pkeppeler.deepcharter.pod.HardLanding;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodFuel;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server GameTests for #319: a pod's landing costs hull by its sink speed, not by the distance it fell. A pod that brakes with its
 * rotor survives the 192-block layer 1 shaft and a free fall of it wrecks the pod; the seated pilot takes no fall damage, the hull does.
 * #1432: the ground cushion lets a careful pilot land soft, and a landing just over the buffer stings.
 */
public class PodHardLandingTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(PodHardLandingTest.class);
    /** The rotor holds the pod while it sinks faster than this, in blocks per tick; under the pod's hard landing speed of 0.7. */
    private static final double BRAKE_ABOVE_SINK = 0.5;

    private static final int SHAFT_X = 2400;
    private static final int SHAFT_Z = 2400;
    /** Layer 1 spans y 0 to 191. The shaft is open from y 2 to the ceiling, over a floor of stone up to y 1. */
    private static final int SHAFT_FLOOR_TOP_Y = TestLayers.worldY(1, 1);

    private static final int SHAFT_TOP_Y = TestLayers.worldY(1, 191);
    private static final int SHAFT_DROP_BLOCKS = 187;
    private static final double SHAFT_SPAWN_Y = SHAFT_FLOOR_TOP_Y + 1 + SHAFT_DROP_BLOCKS;
    /** Ticks of the pod's own fall or braked descent, on top of the wait for the chunk. */
    private static final int SHAFT_TICKS = 700;

    /**
     * Pods whose hull reads their touchdown speed: no buffer and 10 hull per block per tick, so the hull lost times 2
     * is the sink in blocks a second. The cushion reads its own tuning, not these stats, so it brakes a speedometer
     * as it brakes any pod.
     */
    private static final Set<UUID> SPEEDOMETERS = ConcurrentHashMap.newKeySet();

    private static final float SPEEDOMETER_HULL_PER_SPEED = 10f;
    private static final int TICKS_PER_SECOND = 20;

    private static final int PILOTED_TICKS = 200;

    static {
        PodStats.MODIFY.register((pod, stats) -> SPEEDOMETERS.contains(pod.getUUID())
                ? stats.withHardLandingSpeed(0f).withHullDamagePerSpeed(SPEEDOMETER_HULL_PER_SPEED)
                : stats);
    }

    @GameTest
    public void landingDamageIsZeroAtTheThresholdAndScalesWithSpeedAbove(GameTestHelper helper) {
        PodStats stats = PodStats.base();
        // Independent literals: threshold 0.7 blocks per tick, 50 hull per block per tick above it.
        float[] expected = {0f, 0f, 25f, 50f, 100f};
        double[] speeds = {0, 0.7, 1.2, 1.7, 2.7};
        for (int i = 0; i < speeds.length; i++) {
            float damage = HardLanding.hullDamage(stats, speeds[i], 1f);
            if (Math.abs(damage - expected[i]) > 1e-3f) {
                throw failure(
                        helper,
                        "landing at %s blocks per tick should cost %s hull, costs %s",
                        speeds[i],
                        expected[i],
                        damage);
            }
        }
        if (HardLanding.hullDamage(stats, 1.7, 0.5f) != 25f
                || HardLanding.sinkSpeed(0.4) != 0
                || HardLanding.sinkSpeed(-1.5) != 1.5) {
            throw failure(
                    helper,
                    "the damage multiplier should scale the hull cost, and only a downward speed is a sink speed");
        }
        helper.succeed();
    }

    /**
     * A landing 1 block a second over the 14 of the buffer stings; 5 over costs an eighth of the hull; a free fall at
     * terminal speed wrecks.
     */
    @GameTest
    public void aLandingJustOverTheBufferStingsAndTerminalSpeedWrecks(GameTestHelper helper) {
        PodStats stats = PodStats.base();
        double buffer = stats.hardLandingSpeed();
        double perSecond = 1.0 / TICKS_PER_SECOND;
        float oneOver = HardLanding.hullDamage(stats, buffer + perSecond, 1f);
        float fiveOver = HardLanding.hullDamage(stats, buffer + 5 * perSecond, 1f);
        float terminal = HardLanding.hullDamage(stats, 3.92, 1f);
        // Independent literals: 2.5 and 12.5 hull of 100, and a terminal fall past the whole hull.
        if (Math.abs(oneOver - 2.5f) > 1e-3f || Math.abs(fiveOver - 12.5f) > 1e-3f || terminal < stats.maxHull()) {
            throw failure(
                    helper,
                    "1 and 5 blocks a second over the buffer should cost 2.5 and 12.5 hull and terminal speed at least %s, cost %s, %s and %s",
                    stats.maxHull(),
                    oneOver,
                    fiveOver,
                    terminal);
        }
        helper.succeed();
    }

    /** A free fall reads the speed it did under #319, at 50 hull per block per tick: no crew, no cushion. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
    public void aFreeFallOfTenBlocksCostsAFifthOfTheHull(GameTestHelper helper) {
        freeFall(helper, SHAFT_X + 200, 10, false, 19f, 25f);
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
    public void aFreeFallOfTwentyBlocksCostsAboutTwoFifthsOfTheHull(GameTestHelper helper) {
        freeFall(helper, SHAFT_X + 250, 20, false, 36f, 44f);
    }

    /** A pilot seated through a hard but survivable landing keeps full health: the hull took it, less the cushion. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
    public void aCrewedHardLandingCostsTheHullAndNotThePilot(GameTestHelper helper) {
        freeFall(helper, SHAFT_X + 300, 12, true, 6f, 15f);
    }

    /** Before #1432 this touched down at 13 blocks a second: soft, but only just. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + PILOTED_TICKS)
    public void aPodLetSinkFromAHoverThreeBlocksUpLandsUnhurt(GameTestHelper helper) {
        pilotedLanding(helper, SHAFT_X + 400, 3, tick -> Input.EMPTY, "let sink from a hover 3 blocks up", 0f, 0f);
    }

    /** Before #1432 this touched down at 18 blocks a second and cost 14 hull. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + PILOTED_TICKS)
    public void aPodLetSinkFromAHoverSixBlocksUpLandsUnhurt(GameTestHelper helper) {
        pilotedLanding(helper, SHAFT_X + 450, 6, tick -> Input.EMPTY, "let sink from a hover 6 blocks up", 0f, 0f);
    }

    /** Before #1432 this touched down at 23 blocks a second and cost 31 hull; now it is just over the buffer. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + PILOTED_TICKS)
    public void aPodLetSinkFromAHoverTenBlocksUpStings(GameTestHelper helper) {
        pilotedLanding(helper, SHAFT_X + 500, 10, tick -> Input.EMPTY, "let sink from a hover 10 blocks up", 1f, 8f);
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + PILOTED_TICKS)
    public void aPodFeatheredDownFromTenBlocksLandsUnhurt(GameTestHelper helper) {
        pilotedLanding(helper, SHAFT_X + 550, 10, PodHardLandingTest::feather, "feathered down from 10 blocks", 0f, 0f);
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + SHAFT_TICKS)
    public void aPodBrakingWithItsRotorSurvivesTheLayerOneShaftAndBurnsFuel(GameTestHelper helper) {
        ServerLevel one = shaft(helper, SHAFT_X, SHAFT_Z);
        Vec3 start = new Vec3(SHAFT_X + 0.5, SHAFT_SPAWN_Y, SHAFT_Z + 0.5);
        MockPlayer pilot = MockPlayers.join(helper, "shaft-braker");
        pilot.player().setGameMode(GameType.SURVIVAL);
        PodEntity[] pod = {null};
        FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(start), () -> {
            pod[0] = spawn(one, start);
            // The pilot joins once the chunk ticks: a player put in an unloaded column falls out of the layer.
            pilot.teleportTo(one, start, 0f, 0f);
            if (!pilot.player().startRiding(pod[0], true, false)) {
                throw failure(helper, "the pilot could not board the pod");
            }
        });
        TestTicks.each(helper, () -> {
            if (pod[0] != null) {
                pilot.setInput(
                        HardLanding.sinkSpeed(pod[0].getDeltaMovement().y) > BRAKE_ABOVE_SINK ? JUMP : Input.EMPTY);
            }
        });
        helper.succeedWhen(() -> {
            if (pod[0] == null || !pod[0].onGround() || pod[0].tickCount < 5) {
                throw failure(helper, "the pod has not landed");
            }
            PodEntity landed = pod[0];
            float idleBurn = landed.tickCount * PodFuel.drainPercentPerTick(PodStats.base(), PodFuel.Activity.IDLE);
            float burned = PodTuning.DEFAULT.shell().fullFuel() - landed.fuel();
            if (landed.hull() != PodTuning.DEFAULT.shell().fullHull()) {
                throw failure(helper, "a pod braked down 190 blocks should land unhurt, hull %s", landed.hull());
            }
            if (burned < idleBurn * 1.2f) {
                throw failure(
                        helper,
                        "braking against gravity should burn more fuel than idling (%s over %s ticks), burned %s",
                        idleBurn,
                        landed.tickCount,
                        burned);
            }
            if (pilot.player().getHealth() < pilot.player().getMaxHealth()) {
                throw failure(
                        helper,
                        "the pilot should be unhurt, health %s",
                        pilot.player().getHealth());
            }
            pilot.leave();
            landed.discard();
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + SHAFT_TICKS)
    public void aFreeFallDownTheLayerOneShaftWrecksThePod(GameTestHelper helper) {
        freeFall(helper, SHAFT_X + 100, SHAFT_DROP_BLOCKS, false, 100f, 100f);
    }

    /** The crew of a pod wrecked by a fall die by the wreck rule (#67), after a landing that took the hull to 0. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + SHAFT_TICKS)
    public void aCrewedFreeFallDownTheLayerOneShaftWrecksThePodAndTheWreckRuleTakesTheCrew(GameTestHelper helper) {
        freeFall(helper, SHAFT_X + 150, SHAFT_DROP_BLOCKS, true, 100f, 100f);
    }

    @GameTest(maxTicks = 20)
    public void aSeatedPilotTakesNoFallDamageButAPlayerOutsideAPodDoes(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(3.5, 2, 3.5));
        MockPlayer pilot = seat(helper, pod);
        MockPlayer walker = MockPlayers.join(helper, "fall-walker");
        walker.player().setGameMode(GameType.SURVIVAL);
        walker.teleportTo(helper.getLevel(), pod.position().add(5, 0, 0), 0f, 0f);
        ServerLevel level = helper.getLevel();
        boolean seatedHurt =
                pilot.player().hurtServer(level, level.damageSources().fall(), 5f);
        boolean walkerHurt =
                walker.player().hurtServer(level, level.damageSources().fall(), 5f);
        if (seatedHurt || pilot.player().getHealth() < pilot.player().getMaxHealth()) {
            throw failure(
                    helper,
                    "a seated pilot should take no fall damage, health %s",
                    pilot.player().getHealth());
        }
        if (!walkerHurt || walker.player().getHealth() != walker.player().getMaxHealth() - 5f) {
            throw failure(
                    helper,
                    "a player outside a pod should take vanilla fall damage of 5, health %s",
                    walker.player().getHealth());
        }
        pilot.leave();
        walker.leave();
        pod.discard();
        helper.succeed();
    }

    @GameTest(maxTicks = 20)
    public void theCrewOfAWreckTakeVanillaFallDamage(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(3.5, 2, 3.5));
        pod.setHull(0f);
        helper.runAfterDelay(2, () -> {
            MockPlayer crew = seat(helper, pod);
            if (!Wrecks.isWreck(pod)) {
                throw failure(helper, "the pod should be a wreck");
            }
            ServerLevel level = helper.getLevel();
            if (!crew.player().hurtServer(level, level.damageSources().fall(), 5f)
                    || crew.player().getHealth() != crew.player().getMaxHealth() - 5f) {
                throw failure(
                        helper,
                        "the crew of a wreck should take vanilla fall damage of 5, health %s",
                        crew.player().getHealth());
            }
            crew.leave();
            pod.discard();
            helper.succeed();
        });
    }

    /**
     * Drops a pod {@code blocks} down a fresh shaft with the rotor off and checks the hull it lost, between {@code min} and {@code max}.
     * A pilot, when seated, takes no damage from the landing; at 100 hull lost the pod is a wreck and the crew are dead by its rule.
     */
    private static void freeFall(GameTestHelper helper, int x, int blocks, boolean crewed, float min, float max) {
        ServerLevel one = shaft(helper, x, SHAFT_Z);
        Vec3 start = new Vec3(x + 0.5, SHAFT_FLOOR_TOP_Y + 1 + blocks, SHAFT_Z + 0.5);
        MockPlayer pilot = crewed ? MockPlayers.join(helper, "free-faller-" + x) : null;
        PodEntity[] pod = {null};
        FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(start), () -> {
            pod[0] = spawn(one, start);
            if (pilot != null) {
                // The pilot joins once the chunk ticks: a player put in an unloaded column falls out of the layer.
                pilot.player().setGameMode(GameType.SURVIVAL);
                pilot.teleportTo(one, start, 0f, 0f);
                if (!pilot.player().startRiding(pod[0], true, false)) {
                    throw failure(helper, "the pilot could not board the pod");
                }
            }
        });
        helper.succeedWhen(() -> {
            if (pod[0] == null || !pod[0].onGround() || pod[0].tickCount < 5) {
                throw failure(helper, "the pod has not landed");
            }
            float lost = PodTuning.DEFAULT.shell().fullHull() - pod[0].hull();
            if (lost < min || lost > max) {
                throw failure(
                        helper, "a free fall of %s blocks should cost %s to %s hull, cost %s", blocks, min, max, lost);
            }
            boolean wrecked = lost >= PodTuning.DEFAULT.shell().fullHull();
            if (wrecked != Wrecks.isWreck(pod[0])) {
                throw failure(
                        helper,
                        "the pod should be a wreck exactly when its hull is gone, hull lost %s, wreck %s",
                        lost,
                        Wrecks.isWreck(pod[0]));
            }
            if (pilot != null
                    && !wrecked
                    && (pilot.player().getVehicle() != pod[0]
                            || pilot.player().getHealth() < pilot.player().getMaxHealth())) {
                throw failure(
                        helper,
                        "the seated pilot should be unhurt and still aboard, health %s",
                        pilot.player().getHealth());
            }
            if (pilot != null && wrecked && pilot.player().isAlive()) {
                throw failure(helper, "the crew of a wrecked pod should be dead by the wreck rule");
            }
            if (pilot != null) {
                pilot.leave();
            }
            pod[0].discard();
        });
    }

    /** Feathering the rotor from a hover: tap jump for 2 ticks in every 5. */
    private static Input feather(int tick) {
        return tick % 5 < 2 ? JUMP : Input.EMPTY;
    }

    /** One crewed pod in its own shaft, flown by {@code keys} until it lands. */
    private static final class Flight {
        final MockPlayer pilot;
        final IntFunction<Input> keys;

        @Nullable
        PodEntity pod;

        int ticks;
        float lost = Float.NaN;

        Flight(MockPlayer pilot, IntFunction<Input> keys) {
            this.pilot = pilot;
            this.keys = keys;
        }

        /** Gives this tick's keys, and the hull lost at the first tick on the ground. */
        void tick() {
            if (pod == null || !Float.isNaN(lost)) {
                return;
            }
            if (pod.onGround()) {
                lost = PodTuning.DEFAULT.shell().fullHull() - pod.hull();
                pilot.setInput(Input.EMPTY);
                return;
            }
            pilot.setInput(keys.apply(ticks++));
        }

        void finish() {
            pilot.leave();
            if (pod != null) {
                SPEEDOMETERS.remove(pod.getUUID());
                pod.discard();
            }
        }
    }

    /**
     * Flies a crewed pod from rest {@code blocks} over a shaft's floor with {@code keys}, and checks its hull lost is
     * between {@code min} and {@code max}. A speedometer pod flies the same keys in the next shaft and logs the
     * touchdown speed in blocks a second.
     */
    private static void pilotedLanding(
            GameTestHelper helper, int x, int blocks, IntFunction<Input> keys, String how, float min, float max) {
        Flight real = new Flight(MockPlayers.join(helper, "soft-" + x), keys);
        Flight meter = new Flight(MockPlayers.join(helper, "meter-" + x), keys);
        List<Flight> flights = List.of(real, meter);
        int[] columns = {x, x + 8};
        ServerLevel one = TestLayers.level(helper.getLevel().getServer(), 1);
        List<BlockPos> starts = new ArrayList<>();
        for (int column : columns) {
            shaft(helper, column, SHAFT_Z);
            starts.add(BlockPos.containing(column + 0.5, SHAFT_FLOOR_TOP_Y + 1 + blocks, SHAFT_Z + 0.5));
        }
        FarChunks.awaitEntityTicking(helper, one, starts, index -> {
            Flight flight = flights.get(index);
            Vec3 start = new Vec3(columns[index] + 0.5, SHAFT_FLOOR_TOP_Y + 1 + blocks, SHAFT_Z + 0.5);
            PodEntity pod = TestEntities.create(PodRegistry.POD, one);
            pod.setPos(start);
            if (flight == meter) {
                SPEEDOMETERS.add(pod.getUUID());
            }
            one.addFreshEntity(pod);
            flight.pilot.player().setGameMode(GameType.SURVIVAL);
            flight.pilot.teleportTo(one, start, 0f, 0f);
            if (!flight.pilot.player().startRiding(pod, true, false)) {
                throw failure(helper, "the pilot could not board the pod");
            }
            flight.pod = pod;
        });
        TestTicks.each(helper, () -> flights.forEach(Flight::tick));
        int[] logged = {0};
        helper.succeedWhen(() -> {
            if (Float.isNaN(real.lost) || Float.isNaN(meter.lost)) {
                throw failure(helper, "the pods have not landed");
            }
            double touchdown = meter.lost / SPEEDOMETER_HULL_PER_SPEED * TICKS_PER_SECOND;
            double buffer = PodStats.base().hardLandingSpeed() * TICKS_PER_SECOND;
            // succeedWhen retries each tick while this fails, so log the landing once.
            if (logged[0]++ == 0) {
                LOGGER.info(
                        "[soft-landing] {}: touches down at {} blocks a second (buffer {}), hull lost {}",
                        how,
                        String.format("%.1f", touchdown),
                        String.format("%.0f", buffer),
                        real.lost);
            }
            if (real.lost < min || real.lost > max) {
                throw failure(
                        helper,
                        "a pod %s should lose %s to %s hull, lost %s (touchdown %.1f blocks a second)",
                        how,
                        min,
                        max,
                        real.lost,
                        touchdown);
            }
            flights.forEach(Flight::finish);
        });
    }

    private static MockPlayer seat(GameTestHelper helper, PodEntity pod) {
        MockPlayer pilot = MockPlayers.join(helper, "landing-pilot");
        pilot.player().setGameMode(GameType.SURVIVAL);
        pilot.teleportTo(helper.getLevel(), pod.position(), 0f, 0f);
        if (!pilot.player().startRiding(pod, true, false)) {
            throw failure(helper, "the pilot could not mount the pod");
        }
        return pilot;
    }

    private static PodEntity spawn(ServerLevel level, Vec3 at) {
        PodEntity pod = TestEntities.create(PodRegistry.POD, level);
        pod.setPos(at);
        level.addFreshEntity(pod);
        return pod;
    }

    /** A 3 by 3 shaft through the whole of layer 1 over a stone floor, cut through RoomCarver so its shell is sealed. */
    private static ServerLevel shaft(GameTestHelper helper, int x, int z) {
        ServerLevel one = TestLayers.level(helper.getLevel().getServer(), 1);
        RoomCarver.carve(
                one,
                new BlockPos(x - 1, TestLayers.band(one, 1).minY(), z - 1),
                new BlockPos(x + 1, SHAFT_FLOOR_TOP_Y, z + 1),
                Blocks.STONE.defaultBlockState(),
                Block.UPDATE_ALL);
        RoomCarver.carve(
                one,
                new BlockPos(x - 1, SHAFT_FLOOR_TOP_Y + 1, z - 1),
                new BlockPos(x + 1, SHAFT_TOP_Y, z + 1),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
        return one;
    }
}
