package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.JUMP;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.SPRINT;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.CreatureTuning;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.creature.LamplessFigures;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.BlockLightOracle;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server GameTests for #449, the Retained as a mob: a dark pod with a pilot draws a figure that clings to the hull and drains the tank,
 * a lit pod does not, the rotor and a light free the pod, the figure never hurts anyone, and the natural spawn rules keep it to the open dark
 * of layers 1 and 2. A cling test builds a closed arena in a far chunk of the test world, with the pod at one end and the figure at the other.
 */
public class RetainedTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(RetainedTest.class);
    /** The arena's half width; it is 13 blocks across with its walls, inside the one chunk that is forced. */
    private static final int HALF = 6;

    private static final int ARENA_HEIGHT = 14;
    /** How deep, below the arena floor, the drilling test lays stone (negative). */
    private static final int BORE_DEPTH = -12;

    private static final int FIRST_SLOT_X = 6408;
    private static final int SLOT_Z_SPACING = 64;
    private static final int FLOOR_Y = TestLayers.worldY(TestLayers.SURFACE, 100);
    /** Ticks for a figure to walk 11 blocks to a pod: the slowest walk is 1 block a second. */
    private static final int APPROACH_BUDGET_TICKS = 11 * 20;
    /** Ticks for a grip on a tank filled to {@link #LOW_TANK_PERCENT} to reach the floor: 15 points of a stock tank is 1.5 litres, 10 seconds at 0.15 a second, with a margin for a loaded runner. */
    private static final int DRAIN_TO_FLOOR_BUDGET_TICKS = 400;
    /** Ticks the grip is watched after the tank reached the floor: it must not let go. */
    private static final int HOLD_AT_FLOOR_TICKS = 100;
    /** Tank level a grip test starts at, so that the drain to the floor takes seconds and not a minute. */
    private static final float LOW_TANK_PERCENT = 20f;
    /** Ticks of drilling in which the figure must be dead: the bites (one in ten ticks), the first at once, and a margin for a loaded runner. */
    private static final int KILL_BUDGET_TICKS = 150;
    /** Ticks a dead figure has to be gone: the dissolve (20 ticks) and a margin. */
    private static final int DISSOLVE_BUDGET_TICKS = 60;
    /** Ticks the two-figure tests run: the walk to the hull, and time for a second grip to show. */
    private static final int TWO_FIGURES_TICKS = 120;
    /** Ticks a pilot's answer that does not kill (the rotor, the treads, a light) is watched: far over the old shake of 20. */
    private static final int NO_ANSWER_TICKS = 120;
    /** Ticks between two blows by hand: a hurt mob ignores a blow that is no stronger than the last for 10 ticks. */
    private static final int BLOW_SPACING_TICKS = 12;

    /** The yaw at which forward is west, along the arena toward the figure's end. */
    private static final float WEST = 90f;

    private static final String GRAB_KEY = "creature.deepcharter.grab";
    private static final String APPROACH_KEY = "creature.deepcharter.approach";
    private static final String BITTEN_KEY = "creature.deepcharter.bitten";
    private static final String DEAD_KEY = "creature.deepcharter.dead";

    private static final AtomicInteger CHARTERS = new AtomicInteger();

    /** (A, #506) The least share of natural-spawn tries that pass the roll: a Retained is a presence, not a rare sight. */
    private static final double LAYER_ONE_MIN_ROLL_SHARE = 0.008;

    private static final double LAYER_TWO_MIN_ROLL_SHARE = 0.04;
    /** The figure stands within this much of facing the pod's centre (the cosine of the angle; 0.97 is 14 degrees). */
    private static final double FACING_COSINE_MIN = 0.97;
    /** Ticks after the grab before the facing is read: the figure closes on its place on the hull at 0.4 blocks a tick. */
    private static final int FACING_SETTLE_TICKS = 20;

    private static final CreatureTuning TUNING = CreatureTuning.DEFAULT;
    private static final CreatureTuning.Cling CLING = TUNING.cling();

    /** A closed arena with a pod at its east end and a pilot aboard, and a figure at its west end. */
    private record Arena(
            ServerLevel level, BlockPos origin, PodEntity pod, MockPlayer pilot, LamplessFigure figure, long builtAt) {
        /** Ticks since the arena was built: the budgets count from there, not from the test's start, which the wait for the chunk takes a share of. */
        long age(GameTestHelper helper) {
            return helper.getTick() - builtAt;
        }

        static Arena build(GameTestHelper helper, ServerLevel level, BlockPos origin, boolean lights) {
            return build(helper, level, origin, lights, origin.west(HALF - 1));
        }

        /** As above, with the figure placed at {@code figureAt} (inside the arena), so that it comes to the hull from any side. */
        static Arena build(
                GameTestHelper helper, ServerLevel level, BlockPos origin, boolean lights, BlockPos figureAt) {
            for (int u = -HALF - 1; u <= HALF + 1; u++) {
                for (int v = -HALF - 1; v <= HALF + 1; v++) {
                    for (int y = -1; y <= ARENA_HEIGHT; y++) {
                        boolean shell =
                                y == -1 || y == ARENA_HEIGHT || Math.abs(u) == HALF + 1 || Math.abs(v) == HALF + 1;
                        // room-carver: a closed arena of its own stone shell in the overworld test structure, not
                        // layer rock
                        level.setBlock(
                                origin.offset(u, y, v), (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
                    }
                }
            }
            PodEntity pod = TestEntities.create(PodRegistry.POD, level);
            pod.setPos(Vec3.atBottomCenterOf(origin.east(HALF - 1)));
            level.addFreshEntity(pod);
            MockPlayer pilot = MockPlayers.join(helper, "retained-pilot");
            if (lights) {
                // The pilot founds the charter that owns the pod, or the pod would not take them aboard.
                CharterId charter = charter(helper, pilot.player().getUUID());
                PodComponents.register(pod, charter);
                PodComponents.install(pod, ComponentItems.mint(level.getServer(), ComponentTrack.LIGHTS, 1, charter));
            }
            pod.setYRot(WEST);
            pilot.teleportTo(level, pod.position(), WEST, 0f);
            if (!pilot.player().startRiding(pod)) {
                throw failure(helper, "the pilot could not mount the pod");
            }
            LamplessFigure figure = TestEntities.create(CreatureRegistry.LAMPLESS_FIGURE, level);
            figure.setPos(Vec3.atBottomCenterOf(figureAt));
            figure.setHeading(Direction.NORTH);
            level.addFreshEntity(figure);
            return new Arena(level, origin, pod, pilot, figure, helper.getTick());
        }

        /** Whether the figure's box touches the hull, give or take a hand's breadth. */
        boolean touchesHull() {
            return pod.getBoundingBox().inflate(0.25).intersects(figure.getBoundingBox());
        }

        void clear() {
            pilot.leave();
            figure.discard();
            pod.discard();
        }
    }

    /**
     * A grip is not let go: nobody answers, the figure drains the tank to the floor and holds on there (ADR 0046, amended for #485). It
     * hurts neither the hull nor the pilot. (The pod's own idle burn can take the tank under the floor; the figure takes nothing there.)
     */
    @GameTest(
            maxTicks = FarChunks.AWAIT_BUDGET_TICKS
                    + APPROACH_BUDGET_TICKS
                    + DRAIN_TO_FLOOR_BUDGET_TICKS
                    + HOLD_AT_FLOOR_TICKS
                    + 60)
    public void aDarkPodDrawsAFigureThatClingsAndDrainsItToTheFloorAndNeverLetsGo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(0);
        Arena[] made = {null};
        int[] phase = {0};
        long[] since = {0};
        float[] hullAtGrab = {0};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            long waited = arena.age(helper) - since[0];
            switch (phase[0]) {
                case 0 -> {
                    if (arena.figure.isClinging()) {
                        LOGGER.info(
                                "[retained] the figure walked 10 blocks to a dark pod and grabbed it in {} ticks",
                                waited);
                        hullAtGrab[0] = arena.pod.hull();
                        phase[0] = 1;
                        since[0] = arena.age(helper);
                    } else if (waited > APPROACH_BUDGET_TICKS) {
                        throw failure(
                                helper,
                                "the figure had not grabbed the dark pod %d ticks after it was placed 10 blocks away (it stands at %s, the pod at %s)",
                                waited,
                                arena.figure.position(),
                                arena.pod.position());
                    }
                }
                case 1 -> {
                    expectHolding(helper, arena, waited);
                    if (arena.pod.fuel() <= CLING.floorPercent() + 0.01f) {
                        LOGGER.info("[retained] a grip left alone drained the tank to the floor in {} ticks", waited);
                        phase[0] = 2;
                        since[0] = arena.age(helper);
                    } else if (waited > DRAIN_TO_FLOOR_BUDGET_TICKS) {
                        throw failure(
                                helper,
                                "the tank was at %s of 100 %d ticks after the grab; the floor is %s",
                                arena.pod.fuel(),
                                waited,
                                CLING.floorPercent());
                    }
                }
                default -> {
                    expectHolding(helper, arena, waited);
                    if (waited >= HOLD_AT_FLOOR_TICKS) {
                        expectUnharmed(helper, arena, hullAtGrab[0]);
                        arena.clear();
                        helper.succeed();
                    }
                }
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Arena.build(helper, level, origin, false);
            made[0].pod.setFuel(LOW_TANK_PERCENT);
            since[0] = 0;
            if (!LamplessFigure.draws(made[0].pod)) {
                throw failure(
                        helper,
                        "a powered pod with a pilot, no lights and a tank over the floor does not draw a figure");
            }
        });
    }

    /**
     * (#506) A figure that comes to the pod from the north, and one from the south and a little west, each turns to face the pod's centre.
     * The first test comes from the west (no z offset), which a wrong sign on z would not show.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + 100)
    public void aClingingFigureFacesThePodWhenItComesFromTheNorth(GameTestHelper helper) {
        expectFacingFrom(helper, slot(15), 0, -(HALF - 1));
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + 100)
    public void aClingingFigureFacesThePodWhenItComesFromTheSouthAndWest(GameTestHelper helper) {
        expectFacingFrom(helper, slot(16), 2, HALF - 1);
    }

    /** Builds the arena with the figure at {@code (u, v)} from its centre, waits for the grip and for the turn to settle, then checks the facing. */
    private static void expectFacingFrom(GameTestHelper helper, BlockPos origin, int u, int v) {
        ServerLevel level = helper.getLevel();
        Arena[] made = {null};
        long[] clungAt = {-1};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            if (clungAt[0] < 0) {
                if (arena.figure.isClinging()) {
                    clungAt[0] = arena.age(helper);
                } else if (arena.age(helper) > APPROACH_BUDGET_TICKS + 20) {
                    throw failure(helper, "the figure from (%d, %d) had not grabbed the pod", u, v);
                }
                return;
            }
            if (arena.age(helper) - clungAt[0] >= FACING_SETTLE_TICKS) {
                expectFacesPod(helper, arena);
                arena.clear();
                helper.succeed();
            }
        });
        FarChunks.awaitEntityTicking(
                helper,
                level,
                origin,
                () -> made[0] = Arena.build(helper, level, origin, false, origin.offset(u, 0, v)));
    }

    /** The figure is on the hull and alive after {@code ticks} of a grip nobody ended. */
    private static void expectHolding(GameTestHelper helper, Arena arena, long ticks) {
        if (!arena.figure.isClinging() || !arena.figure.isAlive()) {
            throw failure(
                    helper,
                    "the figure let go %d ticks after the grab, with no one to answer it (clinging %s, alive %s, fuel %s)",
                    ticks,
                    arena.figure.isClinging(),
                    arena.figure.isAlive(),
                    arena.pod.fuel());
        }
        if (ticks > 10 && !arena.touchesHull()) {
            throw failure(
                    helper,
                    "the clinging figure at %s is off the hull %s",
                    arena.figure.position(),
                    arena.pod.getBoundingBox());
        }
        if (ticks > 10) {
            expectFacesPod(helper, arena);
        }
    }

    /** (#506) The body, the head and the heading of a clinging figure all point at the pod's centre: it holds the pod, it does not turn its back on it. */
    private static void expectFacesPod(GameTestHelper helper, Arena arena) {
        Vec3 toPod = arena.pod
                .position()
                .subtract(arena.figure.position())
                .multiply(1, 0, 1)
                .normalize();
        for (var look : Map.of(
                        "body",
                        arena.figure.yBodyRot,
                        "head",
                        arena.figure.getYHeadRot(),
                        "heading",
                        arena.figure.getYRot())
                .entrySet()) {
            double cosine = Vec3.directionFromRotation(0, look.getValue()).dot(toPod);
            if (cosine < FACING_COSINE_MIN) {
                throw failure(
                        helper,
                        "the clinging figure's %s faces %s (yaw %s), not the pod: cosine %s toward %s",
                        look.getKey(),
                        Vec3.directionFromRotation(0, look.getValue()),
                        look.getValue(),
                        cosine,
                        toPod);
            }
        }
    }

    /** Neither the hull nor the pilot were hurt, and the pilot was told once that the figure came and once that it grabbed. */
    private static void expectUnharmed(GameTestHelper helper, Arena arena, float hullAtGrab) {
        if (arena.pod.hull() != hullAtGrab) {
            throw failure(
                    helper, "the pod's hull went from %s to %s while the figure held it", hullAtGrab, arena.pod.hull());
        }
        if (arena.pilot.player().getHealth() != arena.pilot.player().getMaxHealth()) {
            throw failure(
                    helper,
                    "the pilot's health went to %s while the figure held the pod",
                    arena.pilot.player().getHealth());
        }
        long grabbed = told(arena, GRAB_KEY);
        long warned = told(arena, APPROACH_KEY);
        if (grabbed != 1 || warned != 1) {
            throw failure(
                    helper,
                    "the pilot should be told once that the figure came and once that it grabbed (came: %d times, grabbed: %d times): %s",
                    warned,
                    grabbed,
                    arena.pilot.actionBarMessages());
        }
    }

    /** How many times the pilot has been sent the line {@code key}. */
    private static long told(Arena arena, String key) {
        return arena.pilot.actionBarMessages().stream()
                .filter(message -> message.getContents() instanceof TranslatableContents contents
                        && contents.getKey().equals(key))
                .count();
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 400)
    public void aLitPodIsIgnored(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(1);
        Arena[] made = {null};
        int[] ticks = {0};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            if (arena.figure.isClinging()) {
                throw failure(helper, "a figure clung to a pod with its lights on, after %d ticks", ticks[0]);
            }
            if (LamplessFigure.draws(arena.pod)) {
                throw failure(helper, "a pod with a lights part and power draws a figure");
            }
            if (++ticks[0] < 300) {
                return;
            }
            if (arena.pod.fuel() < 90f) {
                throw failure(
                        helper, "the lit pod lost fuel to something: %s of 100 after 300 ticks", arena.pod.fuel());
            }
            arena.clear();
            helper.succeed();
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> made[0] = Arena.build(helper, level, origin, true));
    }

    @GameTest
    public void onlyAPoweredPodWithAPilotAndFuelAboveTheFloorAndNoLightsDrawsAFigure(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PodEntity pod = TestEntities.create(PodRegistry.POD, level);
        pod.setPos(Vec3.atBottomCenterOf(slot(2).above(40)));
        level.addFreshEntity(pod);
        MockPlayer pilot = MockPlayers.join(helper, "retained-draw");
        try {
            expect(helper, !LamplessFigure.draws(pod), "an empty pod draws a figure");
            pod.setYRot(WEST);
            pilot.teleportTo(level, pod.position(), WEST, 0f);
            expect(helper, pilot.player().startRiding(pod), "the pilot could not mount the pod");
            expect(helper, LamplessFigure.draws(pod), "a dark pod with a pilot does not draw a figure");
            pod.setFuel(TUNING.cling().floorPercent());
            expect(
                    helper,
                    !LamplessFigure.draws(pod),
                    "a pod with its tank at the floor draws a figure that has nothing to take");
            pod.setFuel(TUNING.cling().floorPercent() + 1f);
            expect(helper, LamplessFigure.draws(pod), "a pod just over the floor draws no figure");
            pod.setStranded(true);
            expect(helper, !LamplessFigure.draws(pod), "an unpowered pod draws a figure");
            helper.succeed();
        } finally {
            pilot.leave();
            pod.discard();
        }
    }

    /** The rotor lifts the pod: it is not an answer any more (it freed the pod before #485). */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + NO_ANSWER_TICKS + 60)
    public void liftingTheRotorDoesNotFreeThePod(GameTestHelper helper) {
        expectStillHeld(helper, slot(3), JUMP, "the rotor");
    }

    /** The pilot drives west: the treads move the pod, and the figure rides along. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + NO_ANSWER_TICKS + 60)
    public void drivingTheTreadsDoesNotFreeThePod(GameTestHelper helper) {
        expectStillHeld(helper, slot(6), FORWARD, "the treads");
    }

    private static void expectStillHeld(GameTestHelper helper, BlockPos origin, Input input, String how) {
        ServerLevel level = helper.getLevel();
        Arena[] made = {null};
        long[] grabbedAt = {-1};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            if (grabbedAt[0] < 0) {
                if (arena.figure.isClinging()) {
                    grabbedAt[0] = arena.age(helper);
                    arena.pilot.setInput(input);
                } else if (arena.age(helper) > APPROACH_BUDGET_TICKS + 20) {
                    throw failure(helper, "the figure had not grabbed the dark pod");
                }
                return;
            }
            long held = arena.age(helper) - grabbedAt[0];
            if (!arena.figure.isClinging() || !arena.figure.isAlive()) {
                throw failure(
                        helper,
                        "the figure let go %d ticks after the pilot used %s (the pod is at %s)",
                        held,
                        how,
                        arena.pod.position());
            }
            if (held >= NO_ANSWER_TICKS) {
                arena.clear();
                helper.succeed();
            }
        });
        // The treads drill through the arena's west wall and on, into the chunks beyond it (#1089): a pod drives up to
        // 0.4 blocks a tick, about 48 in the 120 ticks it is watched.
        FarChunks.awaitAllEntityTicking(
                helper,
                level,
                List.of(origin, origin.west(16), origin.west(32), origin.west(48)),
                () -> made[0] = Arena.build(helper, level, origin, false));
    }

    /**
     * The pilot drills down, and the cutter kills the figure: the answer from the seat. It dies in a handful of bites, lets go, the pod
     * keeps almost all its fuel, and the pilot is told the cutter bites and that it is gone. Nothing on the pod, the hull or the pilot is hurt.
     */
    @GameTest(
            maxTicks = FarChunks.AWAIT_BUDGET_TICKS
                    + APPROACH_BUDGET_TICKS
                    + KILL_BUDGET_TICKS
                    + DISSOLVE_BUDGET_TICKS
                    + 60)
    public void drillingKillsTheFigureAndFreesThePod(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(7);
        Arena[] made = {null};
        long[] grabbedAt = {-1};
        long[] diedAt = {-1};
        float[] fuelAtGrab = {0};
        float[] hullAtGrab = {0};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            if (grabbedAt[0] < 0) {
                if (arena.figure.isClinging()) {
                    grabbedAt[0] = arena.age(helper);
                    fuelAtGrab[0] = arena.pod.fuel();
                    hullAtGrab[0] = arena.pod.hull();
                    arena.pilot.setInput(SPRINT);
                } else if (arena.age(helper) > APPROACH_BUDGET_TICKS + 20) {
                    throw failure(helper, "the figure had not grabbed the dark pod");
                }
                return;
            }
            long held = arena.age(helper) - grabbedAt[0];
            if (diedAt[0] < 0) {
                if (!arena.figure.isAlive()) {
                    diedAt[0] = arena.age(helper);
                    float lost = fuelAtGrab[0] - arena.pod.fuel();
                    LOGGER.info(
                            "[retained] a figure killed by the drill: {} ticks after the grab, the tank lost {} points",
                            held,
                            lost);
                    expect(helper, !arena.figure.isClinging(), "the figure still clings to the hull as it dies");
                    expect(helper, lost < 15f, "the tank lost %s points before the drill killed the figure", lost);
                    expect(helper, arena.pod.hull() == hullAtGrab[0], "the hull was hurt");
                    expect(
                            helper,
                            arena.pilot.player().getHealth()
                                    == arena.pilot.player().getMaxHealth(),
                            "the pilot was hurt");
                } else if (held > KILL_BUDGET_TICKS) {
                    throw failure(
                            helper,
                            "the figure still lived %d ticks after the pilot drilled (health %s)",
                            held,
                            arena.figure.getHealth());
                }
                return;
            }
            long dead = arena.age(helper) - diedAt[0];
            if (arena.figure.isClinging()) {
                throw failure(helper, "the dead figure clings to the hull again");
            }
            // The mock pilot's lines reach it a tick after the server sends them.
            if (dead == 2) {
                expect(
                        helper,
                        told(arena, BITTEN_KEY) == 1,
                        "the pilot was not told once that the cutter bites: %s",
                        arena.pilot.actionBarMessages());
                expect(
                        helper,
                        told(arena, DEAD_KEY) == 1,
                        "the pilot was not told once that it is gone: %s",
                        arena.pilot.actionBarMessages());
            }
            if (arena.figure.isRemoved()) {
                arena.clear();
                helper.succeed();
            } else if (dead > DISSOLVE_BUDGET_TICKS) {
                throw failure(helper, "the dead figure was still there %d ticks after it died", dead);
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Arena.build(helper, level, origin, false);
            // A bore of stone under the pod, so that it drills for as long as the test needs and does not fall into
            // the void.
            for (int u = -HALF; u <= HALF; u++) {
                for (int v = -HALF; v <= HALF; v++) {
                    for (int y = BORE_DEPTH; y < 0; y++) {
                        // room-carver: the stone under this test's own arena, in the overworld test structure, not
                        // layer rock
                        level.setBlock(origin.offset(u, y, v), Blocks.STONE.defaultBlockState(), 3);
                    }
                }
            }
        });
    }

    /** A light keeps a figure away from a pod, but no longer frees a pod it already holds, and nothing fades. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + NO_ANSWER_TICKS + 60)
    public void aLightDoesNotFreeAClingingFigureAndItDoesNotFade(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(4);
        Arena[] made = {null};
        long[] litAt = {-1};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            if (litAt[0] < 0) {
                if (arena.figure.isClinging() && arena.age(helper) > 0) {
                    litAt[0] = arena.age(helper);
                    CharterId charter = charter(helper, arena.pilot.player().getUUID());
                    PodComponents.register(arena.pod, charter);
                    PodComponents.install(
                            arena.pod, ComponentItems.mint(level.getServer(), ComponentTrack.LIGHTS, 1, charter));
                } else if (arena.age(helper) > APPROACH_BUDGET_TICKS + 20) {
                    throw failure(helper, "the figure had not grabbed the dark pod");
                }
                return;
            }
            long since = arena.age(helper) - litAt[0];
            if (!arena.figure.isClinging() || arena.figure.fadeFraction() != 0f || arena.figure.isRemoved()) {
                throw failure(
                        helper, "the figure let go or faded %d ticks after the pod's lights part was fitted", since);
            }
            if (since >= NO_ANSWER_TICKS) {
                arena.clear();
                helper.succeed();
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> made[0] = Arena.build(helper, level, origin, false));
    }

    /**
     * A person on foot kills it by hand, and a clinging figure killed so lets go and takes no more. A sword hits for 6, so two blows kill
     * a figure of 12; the blows are made as the game makes a player's, so that the player's attack is what hurts it.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + DISSOLVE_BUDGET_TICKS + 60)
    public void aPersonOnFootKillsAClingingFigureByHand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(8);
        Arena[] made = {null};
        MockPlayer[] walker = {null};
        long[] diedAt = {-1};
        long[] blowAt = {0};
        int[] blows = {0};
        float[] fuelAtDeath = {0};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            if (walker[0] == null) {
                if (arena.figure.isClinging()) {
                    arena.pilot.leave();
                    walker[0] = MockPlayers.join(helper, "retained-walker");
                    walker[0].teleportTo(level, arena.figure.position().add(0, 0, 1), 0f, 0f);
                    blowAt[0] = arena.age(helper);
                    strike(level, arena, walker[0], blows);
                } else if (arena.age(helper) > APPROACH_BUDGET_TICKS + 20) {
                    throw failure(helper, "the figure had not grabbed the dark pod");
                }
                return;
            }
            if (diedAt[0] < 0) {
                // A figure hurt is proof against the next blow for a short while; the second comes after it.
                if (arena.age(helper) - blowAt[0] >= BLOW_SPACING_TICKS) {
                    blowAt[0] = arena.age(helper);
                    strike(level, arena, walker[0], blows);
                }
                if (!arena.figure.isAlive()) {
                    diedAt[0] = arena.age(helper);
                    fuelAtDeath[0] = arena.pod.fuel();
                    expect(
                            helper,
                            blows[0] == 2,
                            "a figure of %s health took %s blows of 6 to die",
                            arena.figure.getMaxHealth(),
                            blows[0]);
                }
                return;
            }
            expect(helper, !arena.figure.isClinging(), "the dead figure clings to the hull");
            expect(
                    helper,
                    arena.pod.fuel() >= fuelAtDeath[0] - 0.5f,
                    "the tank went on draining after the figure died");
            if (arena.figure.isRemoved()) {
                walker[0].leave();
                arena.clear();
                helper.succeed();
            } else if (arena.age(helper) - diedAt[0] > DISSOLVE_BUDGET_TICKS) {
                throw failure(
                        helper,
                        "the dead figure was still there %d ticks after it died",
                        arena.age(helper) - diedAt[0]);
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> made[0] = Arena.build(helper, level, origin, false));
    }

    /** One blow of 6, as a sword makes it, from {@code walker}. */
    private static void strike(ServerLevel level, Arena arena, MockPlayer walker, int[] blows) {
        arena.figure.hurtServer(level, level.damageSources().playerAttack(walker.player()), 6f);
        blows[0]++;
    }

    /**
     * Two figures that pick one pod a few ticks apart, both just out of reach: the first grips it, and the second, whose target was set before
     * that, must not grip it too. Only one holds a pod at a time, or the drain doubles and two kills are needed (a grip is permanent now).
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + TWO_FIGURES_TICKS)
    public void twoFiguresThatPickOnePodDoNotBothGripIt(GameTestHelper helper) {
        expectOneGrip(helper, slot(10), 3, 1.35, 0.9);
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + TWO_FIGURES_TICKS)
    public void twoFiguresThatPickOnePodAHandCloserDoNotBothGripIt(GameTestHelper helper) {
        expectOneGrip(helper, slot(13), 3, 1.5, 1.0);
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + TWO_FIGURES_TICKS)
    public void twoFiguresThatPickOnePodAHandFurtherDoNotBothGripIt(GameTestHelper helper) {
        expectOneGrip(helper, slot(14), 3, 1.2, 0.75);
    }

    private static void expectOneGrip(
            GameTestHelper helper, BlockPos origin, int ticksApart, double firstGap, double secondGap) {
        ServerLevel level = helper.getLevel();
        Arena[] made = {null};
        LamplessFigure[] first = {null};
        LamplessFigure[] second = {null};
        long[] since = {0};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            long waited = arena.age(helper) - since[0];
            if (waited == ticksApart) {
                second[0] = placeNextToPod(level, arena, 1.0, secondGap);
            }
            if (second[0] == null) {
                return;
            }
            if (first[0].isClinging() && second[0].isClinging()) {
                throw failure(helper, "both figures clung to one pod, %d ticks after they were placed", waited);
            }
            if (waited >= TWO_FIGURES_TICKS - 20) {
                expect(helper, first[0].isClinging() || second[0].isClinging(), "neither figure gripped the pod");
                first[0].discard();
                second[0].discard();
                arena.clear();
                helper.succeed();
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Arena.build(helper, level, origin, false);
            // The arena's own figure is far away at the west end; the two that matter stand a little out of reach of
            // the hull, the second nearer, so that the second sets its target before the first grips and arrives before
            // its next look.
            made[0].figure.discard();
            first[0] = placeNextToPod(level, made[0], -1.0, firstGap);
            since[0] = 0;
        });
    }

    /** A figure on the west side of the pod, {@code gap} blocks outside the reach at which it grips, {@code dz} blocks to the side. */
    private static LamplessFigure placeNextToPod(ServerLevel level, Arena arena, double dz, double gap) {
        LamplessFigure figure = TestEntities.create(CreatureRegistry.LAMPLESS_FIGURE, level);
        double x = arena.pod.getX() - arena.pod.getBbWidth() / 2 - CLING.reachBlocks() - gap;
        figure.setPos(x, arena.pod.getY(), arena.pod.getZ() + dz);
        figure.setHeading(Direction.NORTH);
        level.addFreshEntity(figure);
        return figure;
    }

    /** A grip outlasts the pilot: the pod left empty stays held and keeps draining, and a person on foot is who kills it. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + NO_ANSWER_TICKS + 60)
    public void aPodLeftEmptyStaysGripped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(11);
        Arena[] made = {null};
        long[] leftAt = {-1};
        float[] fuelAtLeaving = {0};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            if (leftAt[0] < 0) {
                if (arena.figure.isClinging()) {
                    leftAt[0] = arena.age(helper);
                    fuelAtLeaving[0] = arena.pod.fuel();
                    arena.pilot.player().stopRiding();
                } else if (arena.age(helper) > APPROACH_BUDGET_TICKS + 20) {
                    throw failure(helper, "the figure had not grabbed the dark pod");
                }
                return;
            }
            expect(
                    helper,
                    arena.figure.isClinging() && arena.figure.isAlive(),
                    "the figure let go of a pod its pilot left, %d ticks after (clinging %s, alive %s, removed %s, pod removed %s, fuel %s)",
                    arena.age(helper) - leftAt[0],
                    arena.figure.isClinging(),
                    arena.figure.isAlive(),
                    arena.figure.isRemoved(),
                    arena.pod.isRemoved(),
                    arena.pod.fuel());
            if (arena.age(helper) - leftAt[0] >= NO_ANSWER_TICKS) {
                expect(helper, arena.pod.fuel() < fuelAtLeaving[0], "the tank did not drain while the pod stood empty");
                arena.clear();
                helper.succeed();
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> made[0] = Arena.build(helper, level, origin, false));
    }

    /** A pod that is destroyed (or taken away) ends the grip; the figure is not hurt by it and goes on. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + 100)
    public void aFigureLetsGoWhenThePodIsGone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(9);
        Arena[] made = {null};
        long[] goneAt = {-1};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            if (goneAt[0] < 0) {
                if (arena.figure.isClinging()) {
                    goneAt[0] = arena.age(helper);
                    arena.pilot.leave();
                    arena.pod.discard();
                } else if (arena.age(helper) > APPROACH_BUDGET_TICKS + 20) {
                    throw failure(helper, "the figure had not grabbed the dark pod");
                }
                return;
            }
            if (!arena.figure.isClinging() && arena.age(helper) - goneAt[0] > 40) {
                expect(
                        helper,
                        arena.figure.isAlive() && arena.figure.fadeFraction() == 0f,
                        "the figure died or faded with the pod");
                arena.figure.discard();
                helper.succeed();
            } else if (arena.figure.isClinging() && arena.age(helper) - goneAt[0] > 20) {
                throw failure(helper, "the figure still clings to a pod that is gone");
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> made[0] = Arena.build(helper, level, origin, false));
    }

    /**
     * The rail's figure of Prospector's Run is fixed lore: a level full of the game's own figures, or one that just lost one, still takes it.
     * Run in the Nether, where no other figure test counts and the spawner does not tick.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 40)
    public void theRailsFigureIsNotHeldBackByTheFiguresOfTheCaverns(GameTestHelper helper) {
        // cap-shared: the figures are made, counted and discarded in one callback, so no other test sees the full band.
        ServerLevel nether = ClientLight.serverLevel(helper.getLevel().getServer(), Level.NETHER);
        BlockPos spot = slot(12);
        FarChunks.awaitEntityTicking(helper, nether, spot, () -> {
            List<LamplessFigure> naturals = new ArrayList<>();
            for (int i = 0; i < TUNING.maxPerBand(); i++) {
                // Counted and discarded in this callback, before the figure's first tick (#1237).
                LamplessFigure figure = TestEntities.create(CreatureRegistry.LAMPLESS_FIGURE, nether);
                figure.markNatural();
                figure.setNoAi(true);
                figure.setPos(Vec3.atBottomCenterOf(spot.east(i * 2)));
                nether.addFreshEntity(figure);
                naturals.add(figure);
            }
            boolean naturalRoom = LamplessFigures.bandHasRoom(nether, true, spot.getY());
            boolean railRoom = LamplessFigures.bandHasRoom(nether, false, spot.getY());
            naturals.forEach(LamplessFigure::discard);
            expect(helper, !naturalRoom, "a level that holds %s natural figures takes another", TUNING.maxPerBand());
            expect(helper, railRoom, "the rail's figure is held back by natural figures");
            helper.succeed();
        });
    }

    /**
     * (A, #506) The roll rises with depth and is not rare once the pilot is deep: layer 2 passes at least 4 tries in 100 (it was 2), layer 1
     * at least 8 in 1,000 (it was 5), and layer 2 passes at least three times as many as layer 1. The bounds are the knobs of
     * docs/design/mechanics.md; the fairness of the numbers is {@code RetainedEconomyTest}.
     */
    @GameTest
    public void rollsRiseWithDepthAndTheDeepIsNotRare(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel one = TestLayers.level(server, 1);
        ServerLevel two = TestLayers.level(server, 2);
        int tries = 40000;
        int inOne = 0;
        int inTwo = 0;
        for (int i = 0; i < tries; i++) {
            inOne += LamplessFigures.rolls(1, one.getRandom()) ? 1 : 0;
            inTwo += LamplessFigures.rolls(2, two.getRandom()) ? 1 : 0;
        }
        LOGGER.info(
                "[retained] rolls in {} tries: layer 1 {} ({}), layer 2 {} ({})",
                tries,
                inOne,
                (double) inOne / tries,
                inTwo,
                (double) inTwo / tries);
        expect(
                helper,
                inOne >= tries * LAYER_ONE_MIN_ROLL_SHARE,
                "layer 1 rolled %d of %d: under %s, rare for a pilot who is still shallow",
                inOne,
                tries,
                LAYER_ONE_MIN_ROLL_SHARE);
        expect(
                helper,
                inTwo >= tries * LAYER_TWO_MIN_ROLL_SHARE,
                "layer 2 rolled %d of %d: under %s, rare for a pilot who is deep",
                inTwo,
                tries,
                LAYER_TWO_MIN_ROLL_SHARE);
        expect(
                helper,
                inTwo >= inOne * 3,
                "layer 1 rolled %d of %d and layer 2 %d: the roll does not rise with depth",
                inOne,
                tries,
                inTwo);
        helper.succeed();
    }

    /** The grip test above holds that a clinging figure hurts neither the pilot nor the hull; a figure that cannot attack is why. */
    @GameTest
    public void aFigureHasNoAttackDamage(GameTestHelper helper) {
        LamplessFigure figure = TestEntities.create(CreatureRegistry.LAMPLESS_FIGURE, helper.getLevel());
        expect(
                helper,
                figure.getAttribute(Attributes.ATTACK_DAMAGE) == null,
                "the figure has an attack damage attribute");
        helper.succeed();
    }

    /** A sealed dark room in {@code layer}'s band, with its column. */
    private record DarkRoom(int layer, ServerLevel level, BlockPos column) {}

    /**
     * In a dark room of layer 1 and of layer 2 a figure may appear; beside a light and over air it may not;
     * and in the overworld (where the colony and the hangar stand), the Nether and the End it may not appear at all. The light engine works
     * off the server thread, so the test waits on a wall clock for the light of each phase: dark (the cut's own light work done), lit, dark
     * again. Each room is shut in stone, so no light from outside it, a neighbour's or the rock's own lava, reaches the column (#1104).
     */
    @GameTest(environment = CampaignEnvironments.D, maxTicks = 4 * FarChunks.AWAIT_BUDGET_TICKS + 400)
    public void aFigureMayAppearInTheDarkOfLayersOneAndTwoAndNowhereElse(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        // The two layers are bands of one level: each has its own room, at the same height over its floor.
        List<DarkRoom> rooms = List.of(
                new DarkRoom(1, TestLayers.level(server, 1), TestLayers.pos(1, 7208, 128, 7208)),
                new DarkRoom(2, TestLayers.level(server, 2), TestLayers.pos(2, 7208, 128, 7208)));
        ServerLevel overworld = ClientLight.serverLevel(server, Level.OVERWORLD);
        BlockPos column = rooms.getLast().column();
        BlockPos overworldPos = slot(5).above(2);
        int[] ready = {0};
        int[] phase = {0};
        FarChunks.Deadline[] lightDeadline = {null};
        List<BlockLightOracle.Field> darkness = new ArrayList<>();
        Runnable built = () -> {
            if (++ready[0] == 3) {
                rooms.forEach(room -> darkness.add(BlockLightOracle.expected(room.level(), airOf(room.column()))));
                lightDeadline[0] = FarChunks.deadline();
            }
        };
        TestTicks.each(helper, () -> {
            if (ready[0] < 3) {
                return;
            }
            switch (phase[0]) {
                case 0 -> {
                    List<String> unsettled = new ArrayList<>();
                    for (int i = 0; i < rooms.size(); i++) {
                        DarkRoom room = rooms.get(i);
                        List<BlockLightOracle.Mismatch> cells =
                                BlockLightOracle.mismatches(room.level(), darkness.get(i));
                        if (!cells.isEmpty()) {
                            unsettled.add(String.format(
                                    "layer %s: %s cells, first %s", room.layer(), cells.size(), cells.getFirst()));
                        }
                    }
                    if (!unsettled.isEmpty()) {
                        lightDeadline[0].await(
                                helper,
                                rooms.getFirst().level(),
                                false,
                                () -> "the light of the dark rooms did not settle in " + FarChunks.WAIT_SECONDS + " s: "
                                        + unsettled);
                        return;
                    }
                    LamplessFigures.clearRespawnDelays();
                    for (DarkRoom room : rooms) {
                        expect(
                                helper,
                                LamplessFigures.canAppearAt(room.level(), room.column()),
                                "a figure may not appear in the dark room of layer %s, %s",
                                room.layer(),
                                inputsOf(room.level(), room.column()));
                        room.level().setBlock(room.column().above(), light(15), 3);
                    }
                    expectNowhereElse(helper, server, overworld, overworldPos, column);
                    phase[0] = 1;
                    lightDeadline[0] = FarChunks.deadline();
                }
                case 1 -> {
                    if (rooms.stream()
                            .allMatch(room -> room.level().getBrightness(LightLayer.BLOCK, room.column())
                                    >= TUNING.litBlockLight())) {
                        for (DarkRoom room : rooms) {
                            expect(
                                    helper,
                                    !LamplessFigures.canAppearAt(room.level(), room.column()),
                                    "a figure may appear beside a light of level 15 in layer %s",
                                    room.layer());
                            // room-carver: removes the light block this test placed itself in its own sealed room
                            room.level().setBlock(room.column().above(), Blocks.AIR.defaultBlockState(), 3);
                        }
                        phase[0] = 2;
                        lightDeadline[0] = FarChunks.deadline();
                    } else {
                        // Wall-clock, not ticks: the light engine works off the server thread, and a loaded runner
                        // ticks fast.
                        lightDeadline[0].await(
                                helper,
                                rooms.getFirst().level(),
                                false,
                                () -> "the light of level 15 did not reach the room in " + FarChunks.WAIT_SECONDS
                                        + " s");
                    }
                }
                default -> {
                    if (rooms.stream()
                            .allMatch(room -> room.level().getBrightness(LightLayer.BLOCK, room.column())
                                    < TUNING.litBlockLight())) {
                        for (DarkRoom room : rooms) {
                            expect(
                                    helper,
                                    LamplessFigures.canAppearAt(room.level(), room.column()),
                                    "the room is dark again and a figure may not appear in layer %s, %s",
                                    room.layer(),
                                    inputsOf(room.level(), room.column()));
                            expectNotOverAir(helper, room.level(), room.column());
                        }
                        helper.succeed();
                    } else {
                        lightDeadline[0].await(
                                helper,
                                rooms.getFirst().level(),
                                false,
                                () -> "the light did not go out of the room in " + FarChunks.WAIT_SECONDS + " s");
                    }
                }
            }
        });
        for (DarkRoom room : rooms) {
            FarChunks.awaitEntityTicking(helper, room.level(), room.column(), () -> {
                buildDarkRoom(room.level(), room.column());
                built.run();
            });
        }
        FarChunks.awaitEntityTicking(helper, overworld, overworldPos, () -> {
            buildDarkRoom(overworld, overworldPos);
            built.run();
        });
    }

    private static void expectNowhereElse(
            GameTestHelper helper,
            MinecraftServer server,
            ServerLevel overworld,
            BlockPos overworldPos,
            BlockPos column) {
        expect(
                helper,
                !LamplessFigures.canAppearAt(overworld, overworldPos),
                "a figure may appear in a dark room of the surface");
        for (ColonyAnchor anchor : List.of(ColonyAnchor.HANGAR, ColonyAnchor.CONDUIT)) {
            BlockPos at = Colony.anchor(server, anchor).orElseThrow(() -> failure(helper, "the colony was not built"));
            expect(
                    helper,
                    !LamplessFigures.canAppearAt(overworld, at.above()),
                    "a figure may appear at the colony's %s",
                    anchor);
        }
        for (ServerLevel other :
                List.of(ClientLight.serverLevel(server, Level.NETHER), ClientLight.serverLevel(server, Level.END))) {
            expect(
                    helper,
                    !LamplessFigures.canAppearAt(other, column),
                    "a figure may appear in %s",
                    other.dimension().identifier());
        }
    }

    /**
     * Each input {@link LamplessFigures#canAppearAt} reads at {@code column}, for a failure message: the band, whether the place ticks,
     * the stored block light beside the light the blocks give ({@link BlockLightOracle}), the floor, and the cells a figure stands in.
     */
    private static String inputsOf(ServerLevel level, BlockPos column) {
        BlockPos floor = column.below();
        StringBuilder cells = new StringBuilder();
        for (int up = 0; up < TUNING.natural().roomBlocks(); up++) {
            cells.append(up == 0 ? "" : ", ").append(level.getBlockState(column.above(up)));
        }
        return String.format(
                "at %s: band %s, entity ticking %s, block light stored %s and from the blocks %s (lit at %s), floor %s"
                        + " (sturdy %s), cells [%s]",
                column.toShortString(),
                LayerChain.bandAt(level, column.getY()),
                level.isPositionEntityTicking(column),
                level.getBrightness(LightLayer.BLOCK, column),
                BlockLightOracle.expected(level, new BoundingBox(column)).at(column),
                TUNING.litBlockLight(),
                level.getBlockState(floor),
                level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP),
                cells);
    }

    private static void expectNotOverAir(GameTestHelper helper, ServerLevel layer, BlockPos column) {
        // room-carver: the floor of this test's own sealed room, put back below
        layer.setBlock(column.below(), Blocks.AIR.defaultBlockState(), 3);
        boolean over = LamplessFigures.canAppearAt(layer, column);
        layer.setBlock(column.below(), Blocks.STONE.defaultBlockState(), 3);
        expect(
                helper,
                !over,
                "a figure may appear over air in %s, with nothing to stand on",
                layer.dimension().identifier());
    }

    /**
     * What the game's spawner asks of a figure it tries to place: the roll passes about {@code chancePerTry} of the tries, no try passes beside a
     * light, and a figure made by a command is never refused.
     */
    @GameTest(maxTicks = 2 * FarChunks.AWAIT_BUDGET_TICKS + 400)
    public void theGamesOwnSpawnerRollsRareAndOnlyWhereTheRulesAllow(GameTestHelper helper) {
        ServerLevel two = TestLayers.level(helper.getLevel().getServer(), 2);
        BlockPos column = TestLayers.pos(2, 7272, 128, 7208);
        LamplessFigure[] figure = {null};
        int[] phase = {0};
        FarChunks.Deadline[] lit = {null};
        int tries = 4000;
        TestTicks.each(helper, () -> {
            if (figure[0] == null) {
                return;
            }
            if (phase[0] == 0) {
                LamplessFigures.clearRespawnDelays();
                int rolled = 0;
                for (int i = 0; i < tries; i++) {
                    rolled += LamplessFigures.rolls(2, two.getRandom()) ? 1 : 0;
                }
                double expected = tries * TUNING.natural().chancePerTry();
                expect(
                        helper,
                        rolled > expected * 0.4 && rolled < expected * 1.8,
                        "%d of %d tries passed; about %.0f expected at %s",
                        rolled,
                        tries,
                        expected,
                        TUNING.natural().chancePerTry());
                expect(
                        helper,
                        figure[0].checkSpawnRules(two, EntitySpawnReason.COMMAND),
                        "a figure made by a command was refused");
                two.setBlock(column.above(), light(15), 3);
                phase[0] = 1;
                lit[0] = FarChunks.deadline();
            } else if (two.getBrightness(LightLayer.BLOCK, column) >= TUNING.litBlockLight()) {
                for (int i = 0; i < tries; i++) {
                    expect(
                            helper,
                            !figure[0].checkSpawnRules(two, EntitySpawnReason.NATURAL),
                            "a natural spawn was allowed beside a light of level 15");
                }
                // room-carver: removes the light block this test placed itself in its own sealed room
                two.setBlock(column.above(), Blocks.AIR.defaultBlockState(), 3);
                helper.succeed();
            } else {
                lit[0].await(helper, two, false, () -> "the light of level 15 did not reach the room");
            }
        });
        FarChunks.awaitEntityTicking(helper, two, column, () -> {
            buildDarkRoom(two, column);
            figure[0] = TestEntities.create(CreatureRegistry.LAMPLESS_FIGURE, two, EntitySpawnReason.NATURAL);
            figure[0].setPos(Vec3.atBottomCenterOf(column));
        });
    }

    /** The biomes of layers 1 and 2 list the figure as a rare monster, and no other biome lists it. */
    @GameTest
    public void onlyTheBiomesOfLayersOneAndTwoListTheFigure(GameTestHelper helper) {
        Set<String> inLayers = new HashSet<>();
        for (int layer : new int[] {1, 2}) {
            JsonArray biomes = json("/data/deepcharter/dimension/layer_" + layer + ".json")
                    .getAsJsonObject("generator")
                    .getAsJsonObject("biome_source")
                    .getAsJsonArray("biomes");
            for (JsonElement biome : biomes) {
                inLayers.add(biome.getAsString().substring("deepcharter:".length()));
            }
        }
        expect(
                helper,
                inLayers.size() == 6,
                "layers 1 and 2 have %s biomes, not the 6 the test expects: %s",
                inLayers.size(),
                inLayers);
        List<String> all = List.of(
                "topsoil_claims",
                "stone_benches",
                "deep_claim",
                "upper_levels",
                "shift_change",
                "prospectors_run",
                "regolith_plains",
                "basalt_field",
                "mesa_country");
        for (String biome : all) {
            // The surface biomes list nothing, so they carry no spawn attribute at all (#915).
            JsonObject attributes =
                    json("/data/deepcharter/worldgen/biome/" + biome + ".json").getAsJsonObject("attributes");
            JsonObject spawns = attributes.has("minecraft:gameplay/natural_mob_spawns")
                    ? attributes
                            .getAsJsonObject("minecraft:gameplay/natural_mob_spawns")
                            .getAsJsonObject("argument")
                            .getAsJsonObject("spawns_by_category")
                    : new JsonObject();
            boolean lists = spawns.has("monster")
                    && spawns.getAsJsonArray("monster").asList().stream()
                            .anyMatch(entry -> entry.getAsJsonObject()
                                    .get("type")
                                    .getAsString()
                                    .equals("deepcharter:lampless_figure"));
            expect(
                    helper,
                    lists == inLayers.contains(biome),
                    "%s %s the figure",
                    biome,
                    lists ? "lists" : "does not list");
            if (lists) {
                JsonObject entry = spawns.getAsJsonArray("monster").get(0).getAsJsonObject();
                expect(
                        helper,
                        entry.get("weight").getAsInt() == 1
                                && entry.get("count").getAsInt() == 1
                                && spawns.getAsJsonArray("monster").size() == 1,
                        "%s lists the figure as more than one at a time, or with weight over 1, or beside another monster",
                        biome);
            }
        }
        helper.succeed();
    }

    private static JsonObject json(String resource) {
        InputStream stream = RetainedTest.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("no resource " + resource);
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(resource, e);
        }
    }

    /** The air of a dark room: 5 by 5 and 5 high, whose floor-level cell at {@code feet} is where a figure would stand. */
    private static BoundingBox airOf(BlockPos feet) {
        return BoundingBox.fromCorners(feet.offset(-2, 0, -2), feet.offset(2, 4, 2));
    }

    /** A room of {@link #airOf} air shut in a one-block shell of stone. */
    private static void buildDarkRoom(ServerLevel level, BlockPos feet) {
        BoundingBox air = airOf(feet);
        BoundingBox shell = air.inflatedBy(1);
        BlockPos low = new BlockPos(shell.minX(), shell.minY(), shell.minZ());
        BlockPos high = new BlockPos(shell.maxX(), shell.maxY(), shell.maxZ());
        if (level.dimension().equals(Level.OVERWORLD)) {
            for (BlockPos pos : BlockPos.betweenClosed(low, high)) {
                // room-carver: a closed room of its own stone shell in the overworld test structure, not layer rock
                level.setBlock(pos, (air.isInside(pos) ? Blocks.AIR : Blocks.STONE).defaultBlockState(), 3);
            }
            return;
        }
        // Stone first, so the walls are not whatever the rock was.
        RoomCarver.carve(level, low, high, Blocks.STONE.defaultBlockState());
        RoomCarver.carve(
                level,
                new BlockPos(air.minX(), air.minY(), air.minZ()),
                new BlockPos(air.maxX(), air.maxY(), air.maxZ()),
                Blocks.AIR.defaultBlockState());
    }

    private static BlockPos slot(int index) {
        return new BlockPos(FIRST_SLOT_X, FLOOR_Y, FIRST_SLOT_X + index * SLOT_Z_SPACING);
    }

    private static BlockState light(int level) {
        return Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, level);
    }

    private static CharterId charter(GameTestHelper helper, UUID founder) {
        MinecraftServer server = helper.getLevel().getServer();
        Optional<?> refusal = Charters.found(server, founder, "Retained " + CHARTERS.incrementAndGet());
        if (refusal.isPresent()) {
            throw failure(helper, "a charter step was refused: %s", refusal.get());
        }
        return Charters.charterOfOrThrow(server, founder).orElseThrow().id();
    }
}
