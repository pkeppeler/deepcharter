package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.BACK;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.BACK_SPRINT;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD_JUMP;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD_JUMP_SPRINT;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD_SPRINT;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.SPRINT;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodSprint;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.Arrival;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pod sprint (#1276), measured in the player's units: the real pods of every shipped chassis and engine tier, driven by
 * mock pilots, against a player on foot whose sprint is measured here through vanilla's own movement code. Lines start
 * with {@code [pod-sprint]} and make the PR's table. Columns 19000 to 19999 of layer 1 are this class's.
 */
public class PodSprintTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(PodSprintTest.class);

    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1_500;
    private static final int X = 19000;
    private static final int Z = 19000;
    private static final int FLOOR = TestLayers.worldY(1, 60);
    private static final float EAST = -90f;
    private static final double TICKS_PER_SECOND = 20;

    /** Blocks between the lanes' centres: wider than any chassis, so no pod brushes another or rock beside it. */
    private static final int LANE = 6;

    /** Blocks of open floor ahead of the starts: more than the fastest run covers. */
    private static final int LENGTH = 60;

    private static final int HEADROOM = 30;

    /** Ticks after a reset for a pod or a runner to stand still on the floor. */
    private static final int REST_TICKS = 10;

    /** Ticks of a run before it is timed, so it is timed at its top speed. */
    private static final int SETTLE_TICKS = 8;

    /** Ticks a run is timed over: three and more of a sprint-jump's cycles, so its mean is the mean of whole hops. */
    private static final int WINDOW_TICKS = 40;

    private static final int PHASE_TICKS = REST_TICKS + SETTLE_TICKS + WINDOW_TICKS;

    /**
     * A held forward key as the client hands it to {@code travel}: {@code LocalPlayer.modifyInput} scales the move
     * vector by 0.98 before {@code LivingEntity.aiStep} passes it on.
     */
    private static final Vec3 FORWARD_IMPULSE = new Vec3(0, 0, 0.98);

    /** {@code LivingEntity.aiStep}'s {@code noJumpDelay}: ticks after a jump before a held jump key jumps again. */
    private static final int JUMP_DELAY = 10;

    /** "About 40%": the sprint's top speed over the cruise's, on the ground and in the air. */
    private static final double MIN_SPRINT_RATIO = 1.35;

    private static final double MAX_SPRINT_RATIO = 1.45;

    private static final String FIRST_SPRINT_KEY = "deepcharter.pod.first_sprint";

    /** What the pods do in a phase: a cruise or a sprint, on the ground or in the air. */
    private enum Phase {
        GROUND_CRUISE(FORWARD, false),
        GROUND_SPRINT(FORWARD_SPRINT, false),
        AIR_CRUISE(FORWARD_JUMP, true),
        AIR_SPRINT(FORWARD_JUMP_SPRINT, true);

        private final Input input;
        private final boolean air;

        Phase(Input input, boolean air) {
            this.input = input;
            this.air = air;
        }
    }

    /** A timed run: blocks a tick, litres of fuel a block, and whether the boost was on at its end. */
    private record Run(double speed, double litresPerBlock, boolean boosting) {
        double blocksPerSecond() {
            return speed * TICKS_PER_SECOND;
        }
    }

    /** One pod and its pilot in a lane, and what each phase measured. */
    private static final class Lane {
        private final Chassis chassis;
        private final int tier;
        private final MockPlayer pilot;
        private final Vec3 start;
        private final Arrival<PodEntity> pod = new Arrival<>("pod", "the room's chunks tick");
        private final Map<Phase, Run> runs = new EnumMap<>(Phase.class);
        private double startX;
        private float startFuel;

        Lane(Chassis chassis, int tier, MockPlayer pilot, Vec3 start) {
            this.chassis = chassis;
            this.tier = tier;
            this.pilot = pilot;
            this.start = start;
        }

        String name() {
            return chassis.id() + " engine " + tier;
        }

        Run run(Phase phase) {
            return Objects.requireNonNull(runs.get(phase), () -> name() + " has no " + phase + " run");
        }
    }

    /** A player on foot, sprinting or sprint-jumping, moved each tick by vanilla's {@code travel}. */
    private static final class Runner {
        private final MockPlayer player;
        private final boolean jumps;
        private final Vec3 start;
        private int noJumpDelay;
        private double startX;
        private double speed = Double.NaN;

        Runner(MockPlayer player, boolean jumps, Vec3 start) {
            this.player = player;
            this.jumps = jumps;
            this.start = start;
        }

        /** One tick of a player holding sprint and forward (and jump), as {@code LivingEntity.aiStep} runs it. */
        void step() {
            ServerPlayer body = player.player();
            body.setSprinting(true);
            if (noJumpDelay > 0) {
                noJumpDelay--;
            }
            if (jumps && body.onGround() && noJumpDelay == 0) {
                body.jumpFromGround();
                noJumpDelay = JUMP_DELAY;
            }
            body.travel(FORWARD_IMPULSE);
        }
    }

    private static Vec3 laneStart(int lane) {
        return new Vec3(X + 4.5, FLOOR, Z + LANE * lane + 0.5);
    }

    /** A column in each chunk of the box from ({@code x1}, {@code z1}) to ({@code x2}, {@code z2}). */
    private static List<BlockPos> columns(int x1, int x2, int z1, int z2) {
        List<BlockPos> columns = new ArrayList<>();
        for (int chunkX = x1 >> 4; chunkX <= x2 >> 4; chunkX++) {
            for (int chunkZ = z1 >> 4; chunkZ <= z2 >> 4; chunkZ++) {
                columns.add(new BlockPos(chunkX << 4, FLOOR, chunkZ << 4));
            }
        }
        return columns;
    }

    /**
     * The outrun rule and the boost, every shipped chassis at every engine tier it takes, on the ground and in the
     * air: a clean engine's cruise beats a player's sprint and sprint-jump, the sprint is about 40% faster, and it
     * burns more fuel a block. Sprint with W never drills on open ground, and the pilot is told once.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void cruiseOutrunsAPlayerOnFootAndSprintIsFasterForMoreFuelABlock(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, 1);
        List<Lane> lanes = new ArrayList<>();
        int index = 0;
        for (Chassis chassis : Chassis.all()) {
            for (int tier = 0; tier <= chassis.tierCap(); tier++) {
                Vec3 start = laneStart(index++);
                MockPlayer pilot = MockPlayers.join(helper, "sprint-" + chassis.id() + "-" + tier);
                pilot.teleportTo(level, start, EAST, 0f);
                lanes.add(new Lane(chassis, tier, pilot, start));
            }
        }
        List<Runner> runners = new ArrayList<>();
        for (boolean jumps : new boolean[] {false, true}) {
            Vec3 start = laneStart(index++);
            MockPlayer runner = MockPlayers.join(helper, jumps ? "sprint-jumper" : "sprinter");
            runner.teleportTo(level, start, EAST, 0f);
            runners.add(new Runner(runner, jumps, start));
        }
        int lanesUsed = index;
        RoomCarver.carve(level, X, X + LENGTH, FLOOR - 2, FLOOR - 1, Z - LANE, Z + LANE * lanesUsed, Blocks.STONE);
        RoomCarver.carve(level, X, X + LENGTH, FLOOR, FLOOR + HEADROOM, Z - LANE, Z + LANE * lanesUsed, Blocks.AIR);
        boolean[] ready = {false};
        FarChunks.awaitAllEntityTicking(helper, level, columns(X, X + LENGTH, Z - LANE, Z + LANE * lanesUsed), () -> {
            for (Lane lane : lanes) {
                PodEntity pod = TestEntities.create(PodRegistry.typeOf(lane.chassis), level);
                pod.setPos(lane.start);
                pod.setYRot(EAST);
                level.addFreshEntity(pod);
                if (lane.tier > 0) {
                    ScannerPods.fit(level.getServer(), lane.pilot.player(), pod, ComponentTrack.ENGINE, lane.tier);
                }
                if (!lane.pilot.player().startRiding(pod)) {
                    throw failure(helper, "the pilot of %s could not mount the pod", lane.name());
                }
                lane.pod.set(pod);
            }
            ready[0] = true;
        });
        int[] tick = {-1};
        TestTicks.each(helper, () -> {
            if (!ready[0]) {
                return;
            }
            tick[0]++;
            int phaseIndex = tick[0] / PHASE_TICKS - 1;
            int inPhase = tick[0] % PHASE_TICKS;
            if (phaseIndex < 0) {
                runOnFoot(helper, level, runners, inPhase);
                return;
            }
            if (phaseIndex >= Phase.values().length) {
                if (phaseIndex == Phase.values().length && inPhase == 0) {
                    judge(helper, lanes, runners);
                }
                return;
            }
            Phase phase = Phase.values()[phaseIndex];
            for (Lane lane : lanes) {
                drive(helper, lane, phase, inPhase);
            }
        });
    }

    /**
     * Rests the runners on the floor, then runs each in one go: the server only scales a player's motion between ticks
     * (a client moves its own player), so every tick of {@code travel} runs back to back, as the client would run them.
     */
    private static void runOnFoot(GameTestHelper helper, ServerLevel level, List<Runner> runners, int inPhase) {
        for (Runner runner : runners) {
            ServerPlayer body = runner.player.player();
            if (inPhase == 0) {
                runner.player.teleportTo(level, runner.start, EAST, 0f);
                body.setDeltaMovement(Vec3.ZERO);
            }
            if (inPhase != REST_TICKS) {
                continue;
            }
            for (int step = 0; step < SETTLE_TICKS + WINDOW_TICKS; step++) {
                if (step == SETTLE_TICKS) {
                    runner.startX = body.getX();
                }
                runner.step();
            }
            runner.speed = (body.getX() - runner.startX) / WINDOW_TICKS;
            LOGGER.info(
                    "[pod-sprint] {}: speed attribute {}, on the ground {}",
                    body.getName().getString(),
                    body.getSpeed(),
                    body.onGround());
            expect(
                    helper,
                    runner.speed > 0,
                    "the %s should run east",
                    body.getName().getString());
        }
    }

    private static void drive(GameTestHelper helper, Lane lane, Phase phase, int inPhase) {
        PodEntity pod = lane.pod.get();
        if (inPhase == 0) {
            lane.pilot.releaseInput();
            pod.setPos(lane.start);
            pod.setDeltaMovement(Vec3.ZERO);
            pod.setYRot(EAST);
            return;
        }
        if (inPhase == REST_TICKS) {
            lane.pilot.setInput(phase.input);
        }
        if (inPhase < REST_TICKS) {
            return;
        }
        if (!phase.air) {
            // Sprint with W on open ground drives, and never drills: not down, and not into the floor ahead.
            expect(
                    helper,
                    !pod.drilling(),
                    "%s on %s drilled %s on open ground",
                    lane.name(),
                    phase,
                    pod.drillDirection());
            expect(
                    helper,
                    Math.abs(pod.getY() - lane.start.y) < 1e-6,
                    "%s on %s left the floor's height: y %s",
                    lane.name(),
                    phase,
                    pod.getY());
        }
        if (inPhase == REST_TICKS + SETTLE_TICKS) {
            lane.startX = pod.getX();
            lane.startFuel = pod.fuel();
        }
        if (inPhase == PHASE_TICKS - 1) {
            double blocks = pod.getX() - lane.startX;
            // Each tick the pod moves, then burns: the window's moves are its ticks after the first.
            double speed = blocks / (WINDOW_TICKS - 1);
            double litres = (lane.startFuel - pod.fuel())
                    / PodTuning.DEFAULT.shell().fullFuel()
                    * PodStats.of(pod).tankLitres();
            lane.runs.put(phase, new Run(speed, litres / blocks, PodSprint.boosting(pod)));
            expect(
                    helper,
                    phase.air != pod.onGround(),
                    "%s on %s should be %s",
                    lane.name(),
                    phase,
                    phase.air ? "flying" : "on the ground");
        }
    }

    private static void judge(GameTestHelper helper, List<Lane> lanes, List<Runner> runners) {
        double sprint = runners.get(0).speed;
        double sprintJump = runners.get(1).speed;
        LOGGER.info(
                "[pod-sprint] a player on foot: sprint {} b/s, sprint-jump {} b/s",
                sprint * TICKS_PER_SECOND,
                sprintJump * TICKS_PER_SECOND);
        expect(
                helper,
                Math.abs(sprint * TICKS_PER_SECOND - 5.612) < 0.05,
                "a vanilla sprint is 5.612 b/s, measured %.3f: the runner is not moved as a player is",
                sprint * TICKS_PER_SECOND);
        double onFoot = Math.max(sprint, sprintJump);
        List<String> wrong = new ArrayList<>();
        for (Lane lane : lanes) {
            for (Phase phase : Phase.values()) {
                Run run = lane.run(phase);
                LOGGER.info(
                        "[pod-sprint] {} {}: {} b/s, {} L a block",
                        lane.name(),
                        phase,
                        run.blocksPerSecond(),
                        run.litresPerBlock());
            }
            for (boolean air : new boolean[] {false, true}) {
                Run cruise = lane.run(air ? Phase.AIR_CRUISE : Phase.GROUND_CRUISE);
                Run boost = lane.run(air ? Phase.AIR_SPRINT : Phase.GROUND_SPRINT);
                String where = air ? "in the air" : "on the ground";
                check(
                        wrong,
                        cruise.speed() > onFoot,
                        "%s %s cruises at %.2f b/s, not faster than a player on foot (sprint %.2f, sprint-jump %.2f)",
                        lane.name(),
                        where,
                        cruise.blocksPerSecond(),
                        sprint * TICKS_PER_SECOND,
                        sprintJump * TICKS_PER_SECOND);
                double ratio = boost.speed() / cruise.speed();
                check(
                        wrong,
                        ratio >= MIN_SPRINT_RATIO && ratio <= MAX_SPRINT_RATIO,
                        "%s %s sprints at %.2f times its cruise (%.2f against %.2f b/s), not about 1.4",
                        lane.name(),
                        where,
                        ratio,
                        boost.blocksPerSecond(),
                        cruise.blocksPerSecond());
                check(
                        wrong,
                        boost.litresPerBlock() > cruise.litresPerBlock(),
                        "%s %s burns %.5f L a block sprinting, not more than the cruise's %.5f",
                        lane.name(),
                        where,
                        boost.litresPerBlock(),
                        cruise.litresPerBlock());
                check(
                        wrong,
                        !cruise.boosting() && boost.boosting(),
                        "%s %s: the boost should be off in the cruise and on in the sprint, it was %s and %s",
                        lane.name(),
                        where,
                        cruise.boosting(),
                        boost.boosting());
            }
            long told = lane.pilot.chatMessages().stream()
                    .filter(line -> keyOf(line).equals(FIRST_SPRINT_KEY))
                    .count();
            check(
                    wrong,
                    told == 1,
                    "the pilot of %s should be told about sprint once, was told %d times",
                    lane.name(),
                    told);
        }
        for (Lane lane : lanes) {
            lane.pilot.leave();
            lane.pod.get().discard();
        }
        runners.forEach(runner -> runner.player.leave());
        expect(helper, wrong.isEmpty(), "%s", String.join("; ", wrong));
        helper.succeed();
    }

    @FormatMethod
    private static void check(List<String> wrong, boolean condition, @FormatString String format, Object... args) {
        if (!condition) {
            wrong.add(String.format(format, args));
        }
    }

    private static String keyOf(Component line) {
        return line.getContents() instanceof TranslatableContents contents ? contents.getKey() : "";
    }

    /**
     * Sprint does not reach the drill: sprint with no direction key still bores down, and sprint with W into rock bores
     * sideways at the drill's own rate, slab for slab with a pod that holds only W.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void sprintStillDrillsDownAndBoresSidewaysNoFaster(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, 1);
        int x = X + 200;
        Vec3 downAt = new Vec3(x + 0.5, FLOOR, Z + 0.5);
        int wallX = x + 4;
        Vec3 cruiseAt = new Vec3(wallX - Chassis.MOLE.width() / 2 - 0.01, FLOOR, Z + LANE + 1.0);
        Vec3 sprintAt = new Vec3(cruiseAt.x, FLOOR, Z + 2 * LANE + 1.0);
        RoomCarver.carve(level, x - 4, wallX + 24, FLOOR - 6, FLOOR - 1, Z - LANE, Z + 3 * LANE, Blocks.STONE);
        RoomCarver.carve(level, x - 4, wallX + 24, FLOOR, FLOOR + 8, Z - LANE, Z + 3 * LANE, Blocks.AIR);
        box(level, wallX, wallX + 24, FLOOR, FLOOR + 7, Z + LANE - 2, Z + 3 * LANE, Blocks.STONE);
        List<Input> inputs = List.of(SPRINT, FORWARD, FORWARD_SPRINT);
        List<Vec3> starts = List.of(downAt, cruiseAt, sprintAt);
        List<MockPlayer> pilots = new ArrayList<>();
        List<Arrival<PodEntity>> pods = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            MockPlayer pilot = MockPlayers.join(helper, "sprint-drill-" + i);
            pilot.teleportTo(level, starts.get(i), EAST, 0f);
            pilots.add(pilot);
            pods.add(new Arrival<>("pod " + i, "the room's chunks tick"));
        }
        List<BlockPos> columns = columns(x - 4, wallX + 24, Z - LANE, Z + 3 * LANE);
        FarChunks.awaitAllEntityTicking(helper, level, columns, () -> {
            for (int i = 0; i < inputs.size(); i++) {
                PodEntity pod = TestEntities.create(PodRegistry.POD, level);
                pod.setPos(starts.get(i));
                pod.setYRot(EAST);
                level.addFreshEntity(pod);
                if (!pilots.get(i).player().startRiding(pod)) {
                    throw failure(helper, "pilot %d could not mount the pod", i);
                }
                pilots.get(i).setInput(inputs.get(i));
                pods.get(i).set(pod);
            }
        });
        int[] ticks = {0};
        boolean[] drilledDown = {false};
        int boreTicks = 600;
        TestTicks.each(helper, () -> {
            if (!pods.get(0).isPresent()) {
                return;
            }
            ticks[0]++;
            PodEntity down = pods.get(0).get();
            drilledDown[0] |= down.drilling() && down.drillDirection() == Direction.DOWN;
            if (down.getY() < FLOOR - 1.5) {
                // Two slabs prove it; the bed is only six deep, and the layer's own rock below it may hold lava.
                pilots.get(0).releaseInput();
            }
            PodEntity sprinter = pods.get(2).get();
            expect(
                    helper,
                    !(sprinter.drilling() && PodSprint.boosting(sprinter)),
                    "the boost is on while the drill bores");
            if (ticks[0] < boreTicks) {
                return;
            }
            expect(helper, drilledDown[0], "sprint with no direction key should drill down");
            expect(
                    helper,
                    down.getY() < FLOOR - 1,
                    "the sprinting pod should have bored down, it is at y %s",
                    down.getY());
            int cruiseBored = bored(level, wallX, Z + LANE);
            int sprintBored = bored(level, wallX, Z + 2 * LANE);
            LOGGER.info(
                    "[pod-sprint] sideways in {} ticks: W bored {} columns, W with sprint {}",
                    boreTicks,
                    cruiseBored,
                    sprintBored);
            expect(helper, cruiseBored >= 3, "W into rock should bore sideways, it bored %d columns", cruiseBored);
            expect(
                    helper,
                    sprintBored == cruiseBored,
                    "sprint with W should bore at the drill's rate: %d columns against W's %d",
                    sprintBored,
                    cruiseBored);
            pilots.forEach(MockPlayer::leave);
            pods.forEach(pod -> pod.get().discard());
            helper.succeed();
        });
    }

    /** Columns of the wall bored through at the pod's feet along the lane at {@code laneZ}. */
    private static int bored(ServerLevel level, int wallX, int laneZ) {
        int columns = 0;
        while (level.getBlockState(new BlockPos(wallX + columns, FLOOR, laneZ)).isAir()) {
            columns++;
        }
        return columns;
    }

    /**
     * The boost is the pilot's, on W: S with sprint backs up at the reverse speed, and a navigator who holds sprint
     * and W in a Prospector whose pilot holds W does not boost it.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void reverseAndANavigatorsSprintGetNoBoost(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, 1);
        int x = X + 400;
        RoomCarver.carve(level, x - 30, x + 30, FLOOR - 2, FLOOR - 1, Z - LANE, Z + 3 * LANE, Blocks.STONE);
        RoomCarver.carve(level, x - 30, x + 30, FLOOR, FLOOR + 8, Z - LANE, Z + 3 * LANE, Blocks.AIR);
        List<Input> inputs = List.of(BACK, BACK_SPRINT, FORWARD);
        List<EntityType<PodEntity>> types = List.of(PodRegistry.POD, PodRegistry.POD, PodRegistry.PROSPECTOR);
        List<MockPlayer> pilots = new ArrayList<>();
        List<Vec3> starts = new ArrayList<>();
        List<Arrival<PodEntity>> pods = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            Vec3 start = new Vec3(x + 0.5, FLOOR, Z + LANE * i + 0.5);
            MockPlayer pilot = MockPlayers.join(helper, "sprint-reverse-" + i);
            pilot.teleportTo(level, start, EAST, 0f);
            pilots.add(pilot);
            starts.add(start);
            pods.add(new Arrival<>("pod " + i, "the room's chunks tick"));
        }
        MockPlayer navigator = MockPlayers.join(helper, "sprint-navigator");
        navigator.teleportTo(level, starts.get(2), EAST, 0f);
        List<BlockPos> columns = columns(x - 30, x + 30, Z - LANE, Z + 3 * LANE);
        FarChunks.awaitAllEntityTicking(helper, level, columns, () -> {
            for (int i = 0; i < inputs.size(); i++) {
                PodEntity pod = TestEntities.create(types.get(i), level);
                pod.setPos(starts.get(i));
                pod.setYRot(EAST);
                level.addFreshEntity(pod);
                if (!pilots.get(i).player().startRiding(pod)) {
                    throw failure(helper, "pilot %d could not mount the pod", i);
                }
                pods.get(i).set(pod);
            }
            if (!navigator.player().startRiding(pods.get(2).get())) {
                throw failure(helper, "the navigator could not board the Prospector");
            }
            for (int i = 0; i < inputs.size(); i++) {
                pilots.get(i).setInput(inputs.get(i));
            }
            navigator.setInput(FORWARD_SPRINT);
        });
        int[] ticks = {0};
        double[] from = new double[inputs.size()];
        TestTicks.each(helper, () -> {
            if (!pods.get(0).isPresent()) {
                return;
            }
            ticks[0]++;
            for (Arrival<PodEntity> pod : pods) {
                expect(helper, !PodSprint.boosting(pod.get()), "a pod boosted without its pilot's sprint and W");
            }
            if (ticks[0] == SETTLE_TICKS) {
                for (int i = 0; i < inputs.size(); i++) {
                    from[i] = pods.get(i).get().getX();
                }
            }
            if (ticks[0] < SETTLE_TICKS + WINDOW_TICKS) {
                return;
            }
            double back = from[0] - pods.get(0).get().getX();
            double backSprint = from[1] - pods.get(1).get().getX();
            expect(helper, back > 1, "S should back the pod up, it moved %s", back);
            expect(
                    helper,
                    Math.abs(backSprint - back) < 1e-6,
                    "S with sprint should back up as S does: %s blocks against %s",
                    backSprint,
                    back);
            PodEntity prospector = pods.get(2).get();
            double expected = PodStats.of(prospector).horizontalSpeed() * WINDOW_TICKS;
            double moved = prospector.getX() - from[2];
            expect(
                    helper,
                    Math.abs(moved - expected) < 1e-3,
                    "a navigator's sprint should not boost the Prospector: it moved %s, its cruise covers %s",
                    moved,
                    expected);
            navigator.leave();
            pilots.forEach(MockPlayer::leave);
            pods.forEach(pod -> pod.get().discard());
            helper.succeed();
        });
    }

    private static void box(ServerLevel level, int x1, int x2, int y1, int y2, int z1, int z2, Block block) {
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 2);
                }
            }
        }
    }
}
