package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.SPRINT;

import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.DrawSources;
import io.github.pkeppeler.deepcharter.creature.Drifter;
import io.github.pkeppeler.deepcharter.creature.DrifterTuning;
import io.github.pkeppeler.deepcharter.creature.Drifters;
import io.github.pkeppeler.deepcharter.creature.SporeClouds;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.ThrownKitTuning;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.DrifterArenas;
import io.github.pkeppeler.deepcharter.test.support.DrifterArenas.Arena;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server GameTests for the Drifters of #973 (CREATURES.md section 5): they drift toward a drill, a sprint and a footstep and toward nothing
 * quiet; they burst on touch or when a pod drills beside them, never before three seconds of tell; stopping the drill lets one drift past; the
 * gas eats a pod's hull while a person sits in it and poisons and blinds a person on foot; a pop at range is safe; a pod, however many sit in it,
 * is one source. The room is {@link DrifterArenas}; the zone, the biome, the cadence and the alarm are {@link DrifterSpawnTest}'s.
 */
public class DrifterTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(DrifterTest.class);
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final DrifterTuning TUNING = DrifterTuning.DEFAULT;
    /** Ticks a test waits for the noise to be heard, a look every {@link DrifterTuning.Noise#lookTicks}, and a margin for a loaded runner. */
    private static final int HEAR_TICKS = 30;
    /** Ticks a drifter's drift is watched, from its second after the first tell: long enough to ease to its speed. */
    private static final int EASE_TICKS = 20;

    private static final int MEASURE_TICKS = 20;
    /** Ticks the tests that must see nothing happen are watched. */
    private static final int QUIET_TICKS = 200;

    /** Ticks of a case of a person near a drifter: the drifter is put in after {@link #DRIFTER_AT_TICK}, and the noise is read at the end. */
    private static final int WALK_PHASE_TICKS = 55;

    /** Ticks after a person is moved that the drifter is put in: two looks pass, so that the jump is not heard by it. */
    private static final int DRIFTER_AT_TICK = 15;
    /** The radius of the circle a person walks, so that a look five ticks apart sees them move whichever way the circle turns. */
    private static final double CIRCLE_BLOCKS = 1.5;
    /** Blocks a tick a person walks and sprints at, a little under the player's own so that the circle is the same length. */
    private static final double WALK_SPEED = 0.2;

    private static final double SPRINT_SPEED = 0.28;
    private static final double SNEAK_SPEED = 0.065;

    /** The drifter drifts toward a pod that is drilling, at the drift speed, and the pod is not hurt on the way. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + HEAR_TICKS + EASE_TICKS + MEASURE_TICKS + 60)
    public void aDrifterDriftsTowardADrillingPod(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = DrifterArenas.slot(0);
        Arena[] made = {null};
        Drifter[] drifter = {null};
        long[] drawnAt = {-1};
        Vec3[] from = {null};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            if (drawnAt[0] < 0) {
                if (drifter[0].isDrawn()) {
                    drawnAt[0] = helper.getTick();
                } else if (helper.getTick() > FarChunks.AWAIT_BUDGET_TICKS + HEAR_TICKS) {
                    throw failure(helper, "the drifter did not hear a pod drilling 10 blocks away");
                }
                return;
            }
            long since = helper.getTick() - drawnAt[0];
            if (since == EASE_TICKS) {
                from[0] = drifter[0].position();
            } else if (since == EASE_TICKS + MEASURE_TICKS) {
                Vec3 moved = drifter[0].position().subtract(from[0]);
                double speed = moved.length() / MEASURE_TICKS;
                Vec3 toPod =
                        arena.pod().position().add(0, 1, 0).subtract(from[0]).normalize();
                double cosine = moved.normalize().dot(toPod);
                LOGGER.info(
                        "[drifter] drifted {} blocks a second toward a drilling pod ({} ticks after the first tell, aimed {})",
                        String.format("%.2f", speed * 20),
                        EASE_TICKS,
                        String.format("%.2f", cosine));
                expect(
                        helper,
                        speed > TUNING.driftSpeed() * 0.85 && speed <= TUNING.driftSpeed() * 1.01,
                        "the drifter drifts %s blocks a tick, the drift speed is %s",
                        speed,
                        TUNING.driftSpeed());
                expect(helper, cosine > 0.8, "the drifter does not aim at the pod: cosine %s", cosine);
                expect(helper, arena.pod().hull() == arena.pod().maxHull(), "the pod was hurt on the way");
                drifter[0].discard();
                arena.clear();
                helper.succeed();
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Arena.build(helper, level, origin);
            made[0].pilot().setInput(SPRINT);
            drifter[0] = made[0].drifter(made[0].at(-5, 2, 0));
        });
    }

    /**
     * A decoy that sounds within its pull of a drifter drawn to a drilling pod turns it: the drifter heads for the decoy, 5 blocks to its
     * side, and not for the pod 10 blocks the other way. The decoy has the pull and the duration of a thrown one
     * ({@link ThrownKitTuning.Decoy#DEFAULT}; {@link ThrownKitTest} drives the throw that registers it); this pins what a drifter does with it.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + HEAR_TICKS + EASE_TICKS + MEASURE_TICKS + 60)
    public void aDecoyInReachTurnsADrifterAwayFromTheDrill(GameTestHelper helper) {
        decoyCase(helper, 8, Decoys.IN_REACH);
    }

    /** A decoy beyond its pull does not turn it: the drifter still heads for the pod. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + HEAR_TICKS + EASE_TICKS + MEASURE_TICKS + 60)
    public void aDecoyBeyondItsPullLeavesADrifterOnTheDrill(GameTestHelper helper) {
        decoyCase(helper, 9, Decoys.BEYOND_PULL);
    }

    /** A decoy that goes silent mid-approach sends the drifter back to the drill. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + HEAR_TICKS + 2 * EASE_TICKS + MEASURE_TICKS + 60)
    public void aDecoyThatGoesSilentSendsADrifterBackToTheDrill(GameTestHelper helper) {
        decoyCase(helper, 10, Decoys.SILENCED);
    }

    private enum Decoys {
        IN_REACH,
        BEYOND_PULL,
        SILENCED
    }

    private static void decoyCase(GameTestHelper helper, int slot, Decoys what) {
        ServerLevel level = helper.getLevel();
        ThrownKitTuning.Decoy tuning = ThrownKitTuning.Decoy.DEFAULT;
        BlockPos origin = DrifterArenas.slot(slot);
        Arena[] made = {null};
        Drifter[] drifter = {null};
        Vec3[] decoy = {null};
        long[] drawnAt = {-1};
        Vec3[] from = {null};
        Runnable gone = () -> {
            if (decoy[0] != null) {
                DrawSources.removeDecoy(level, decoy[0]);
            }
        };
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            try {
                if (drawnAt[0] < 0) {
                    if (drifter[0].isDrawn()) {
                        drawnAt[0] = helper.getTick();
                    } else if (helper.getTick() > FarChunks.AWAIT_BUDGET_TICKS + HEAR_TICKS) {
                        throw failure(helper, "the drifter did not hear a pod drilling 10 blocks away");
                    }
                    return;
                }
                long since = helper.getTick() - drawnAt[0];
                // A silenced decoy has turned it for one ease, and goes silent; the measure then starts one ease later.
                long start = what == Decoys.SILENCED ? 2 * EASE_TICKS : EASE_TICKS;
                if (what == Decoys.SILENCED && since == EASE_TICKS) {
                    gone.run();
                }
                if (since == start) {
                    from[0] = drifter[0].position();
                } else if (since == start + MEASURE_TICKS) {
                    Vec3 moved = drifter[0].position().subtract(from[0]).normalize();
                    double cosinePod = moved.dot(arena.pod()
                            .position()
                            .add(0, 1, 0)
                            .subtract(from[0])
                            .normalize());
                    double cosineDecoy = moved.dot(decoy[0].subtract(from[0]).normalize());
                    if (what == Decoys.IN_REACH) {
                        expect(
                                helper,
                                cosineDecoy > 0.8,
                                "the drifter does not aim at the decoy: cosine %s",
                                cosineDecoy);
                        expect(helper, cosinePod < 0.3, "the drifter still aims at the drill: cosine %s", cosinePod);
                    } else {
                        expect(helper, cosinePod > 0.8, "the drifter does not aim at the drill: cosine %s", cosinePod);
                    }
                    gone.run();
                    drifter[0].discard();
                    arena.clear();
                    helper.succeed();
                }
            } catch (RuntimeException | Error failed) {
                gone.run();
                throw failed;
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Arena.build(helper, level, origin);
            made[0].pilot().setInput(SPRINT);
            drifter[0] = made[0].drifter(made[0].at(-5, 2, 0));
            Vec3 centre = drifter[0].position().add(0, drifter[0].getBbHeight() / 2, 0);
            // Beyond its pull the decoy is due west, away from the pod and from every other room, which differ only in
            // z.
            decoy[0] =
                    what == Decoys.BEYOND_PULL ? centre.add(-(tuning.pullBlocks() + 10), 0, 0) : centre.add(0, 0, -5);
            DrawSources.addDecoy(level, decoy[0], tuning.pullBlocks(), level.getGameTime() + tuning.noiseTicks());
        });
    }

    /**
     * A person on foot draws one by how loud they are: a walk 8 blocks away, a sprint 14 away, and not a walk 14 away, a person standing
     * still, or one who sneaks.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 5 * WALK_PHASE_TICKS + 60)
    public void aDrifterDriftsTowardFootstepsAndSprintingButNotAQuietPerson(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = DrifterArenas.slot(1);
        // The walker's circle, the drifter's place, how the person moves, and whether the drifter hears.
        List<Phase> phases = List.of(
                new Phase("a walk 14 blocks away", -5, -5, 5, 5, WALK_SPEED, false, false),
                new Phase("a sprint 14 blocks away", -5, -5, 5, 5, SPRINT_SPEED, true, true),
                new Phase("a walk 6 blocks away", -1, -1, 4, 3, WALK_SPEED, false, true),
                new Phase("a person standing 6 blocks away", -1, -1, 4, 3, 0, false, false),
                new Phase("a person sneaking 6 blocks away", -1, -1, 4, 3, SNEAK_SPEED, false, false));
        Arena[] made = {null};
        MockPlayer[] walker = {null};
        Drifter[] drifter = {null};
        int[] phase = {0};
        long[] since = {0};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            Phase now = phases.get(phase[0]);
            long tick = helper.getTick() - since[0];
            walk(walker[0], arena, now, tick);
            if (tick == DRIFTER_AT_TICK) {
                // The walker was moved at the start of the phase: the looks that saw it jump have passed.
                drifter[0] = arena.drifter(arena.at(now.dx, 2, now.dz));
            }
            if (tick > DRIFTER_AT_TICK && !now.drawn && drifter[0].isDrawn()) {
                throw failure(helper, "the drifter heard %s", now.what);
            }
            if (tick == WALK_PHASE_TICKS) {
                expect(helper, drifter[0].isDrawn() == now.drawn, "the drifter did not hear %s", now.what);
                LOGGER.info("[drifter] {}: {}", now.what, now.drawn ? "heard" : "not heard");
                drifter[0].discard();
                phase[0]++;
                since[0] = helper.getTick();
                if (phase[0] == phases.size()) {
                    walker[0].leave();
                    arena.clear();
                    helper.succeed();
                } else {
                    walk(walker[0], arena, phases.get(phase[0]), 0);
                }
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Arena.build(helper, level, origin);
            walker[0] = MockPlayers.join(helper, "drifter-walker");
            walker[0].player().setGameMode(GameType.SURVIVAL);
            walk(walker[0], made[0], phases.get(0), 0);
            since[0] = helper.getTick();
        });
    }

    /** One case of a person near a drifter: the circle the person walks (its middle, east and south of the room's), where the drifter floats. */
    private record Phase(
            String what, double wx, double wz, double dx, double dz, double speed, boolean sprint, boolean drawn) {}

    /** Puts the walker on its circle at {@code tick}: a person who walks is moved each tick, one who stands is not. */
    private static void walk(MockPlayer walker, Arena arena, Phase phase, long tick) {
        double angle = phase.speed / CIRCLE_BLOCKS * tick;
        Vec3 at = arena.at(phase.wx + Math.cos(angle) * CIRCLE_BLOCKS, 0, phase.wz + Math.sin(angle) * CIRCLE_BLOCKS);
        walker.teleportTo(arena.level(), at, 0f, 0f);
        walker.player().setSprinting(phase.sprint);
        walker.player().setShiftKeyDown(phase.speed == SNEAK_SPEED);
    }

    /** Nothing is loud: the drifter hears nothing, drifts no faster than the wander speed, and leaves a parked pod and its pilot alone. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + QUIET_TICKS + 60)
    public void aQuietWorldDrawsNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = DrifterArenas.slot(2);
        Arena[] made = {null};
        Drifter[] drifter = {null};
        Vec3[] start = {null};
        long[] since = {0};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            expect(helper, !drifter[0].isRemoved(), "a drifter burst in a quiet room");
            expect(helper, !drifter[0].isDrawn(), "a drifter heard something in a quiet room");
            if (helper.getTick() - since[0] >= QUIET_TICKS) {
                double drifted = drifter[0].position().distanceTo(start[0]);
                LOGGER.info(
                        "[drifter] a quiet drifter wandered {} blocks in {} ticks",
                        String.format("%.1f", drifted),
                        QUIET_TICKS);
                expect(
                        helper,
                        drifted <= TUNING.wanderSpeed() * QUIET_TICKS * 1.05,
                        "a quiet drifter drifted %s blocks, more than the wander speed allows",
                        drifted);
                expect(helper, arena.pod().hull() == arena.pod().maxHull(), "the parked pod was hurt");
                drifter[0].discard();
                arena.clear();
                helper.succeed();
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Arena.build(helper, level, origin);
            // Right beside the parked pod: nothing makes a noise, so nothing happens.
            drifter[0] = made[0].drifter(made[0].at(2.5, 0.5, 0));
            start[0] = drifter[0].position();
            since[0] = helper.getTick();
        });
    }

    /**
     * The counterplay: the pilot stops drilling, and the drifter, heading for the pod, loses the noise, stops being drawn and drifts on past,
     * and a drifter that is on top of the pod bursts at nothing.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + HEAR_TICKS + QUIET_TICKS + 120)
    public void stoppingTheDrillLetsADrifterDriftPast(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = DrifterArenas.slot(3);
        Arena[] made = {null};
        Drifter[] drifter = {null};
        long[] stoppedAt = {-1};
        long[] drawnAt = {-1};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            if (drawnAt[0] < 0) {
                if (drifter[0].isDrawn()) {
                    drawnAt[0] = helper.getTick();
                }
                return;
            }
            if (stoppedAt[0] < 0) {
                if (helper.getTick() - drawnAt[0] >= EASE_TICKS) {
                    arena.pilot().releaseInput();
                    stoppedAt[0] = helper.getTick();
                }
                return;
            }
            expect(helper, !drifter[0].isRemoved(), "the drifter burst after the pod stopped drilling");
            long quiet = helper.getTick() - stoppedAt[0];
            if (quiet == TUNING.calmTicks() + 10) {
                expect(
                        helper,
                        !drifter[0].isDrawn(),
                        "the drifter was still drawn %d ticks after the drill stopped",
                        quiet);
            }
            if (quiet >= QUIET_TICKS) {
                expect(helper, arena.pod().hull() == arena.pod().maxHull(), "the pod was hurt");
                LOGGER.info("[drifter] the drill stopped, and the drifter drifted past");
                drifter[0].discard();
                arena.clear();
                helper.succeed();
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Arena.build(helper, level, origin);
            made[0].pilot().setInput(SPRINT);
            drifter[0] = made[0].drifter(made[0].at(-5, 2, 0));
        });
    }

    /**
     * A pod drilling beside a drifter bursts it, and not before the tell: the drifter is already beside the hull as the pilot starts to
     * drill, and for {@code armTicks} it only drifts. Then the gas reaches the hull.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + HEAR_TICKS + 240)
    public void aDrifterBurstsWhenAPodDrillsBesideItButNotBeforeTheTell(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = DrifterArenas.slot(4);
        Arena[] made = {null};
        Drifter[] drifter = {null};
        long[] drawnAt = {-1};
        long[] burstAt = {-1};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            if (drawnAt[0] < 0) {
                if (drifter[0].isDrawn()) {
                    drawnAt[0] = helper.getTick();
                }
                return;
            }
            long since = helper.getTick() - drawnAt[0];
            if (burstAt[0] < 0) {
                if (drifter[0].isRemoved()) {
                    burstAt[0] = helper.getTick();
                    LOGGER.info(
                            "[drifter] a pod drilling beside one bursts it {} ticks after the first tell ({} seconds)",
                            since,
                            String.format("%.1f", since / 20.0));
                    expect(
                            helper,
                            since >= TUNING.armTicks(),
                            "the drifter burst %d ticks after the first tell, before the %d ticks of tell",
                            since,
                            TUNING.armTicks());
                } else {
                    expect(
                            helper,
                            arena.pod().hull() == arena.pod().maxHull(),
                            "the pod lost hull %d ticks into the tell",
                            since);
                    expect(helper, since < TUNING.armTicks() + 40, "the drifter had not burst %d ticks in", since);
                }
                return;
            }
            if (arena.pod().hull() < arena.pod().maxHull()) {
                LOGGER.info(
                        "[drifter] the gas had cost the hull {} {} ticks after the burst",
                        String.format(
                                "%.2f", arena.pod().maxHull() - arena.pod().hull()),
                        helper.getTick() - burstAt[0]);
                arena.clear();
                helper.succeed();
            } else if (helper.getTick() - burstAt[0] > 40) {
                throw failure(helper, "the gas did not reach a hull 1.9 blocks from the burst");
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Arena.build(helper, level, origin);
            made[0].pilot().setInput(SPRINT);
            // Its box 1.9 blocks from the hull: touching it needs a drift, beside it does not.
            drifter[0] = made[0].drifter(made[0].at(1.2, 0.5, 0));
        });
    }

    /**
     * The gas eats the hull only while a person sits in the pod and the hull is in it: not from a cloud 8 blocks off, not from an empty pod,
     * and a pilot who sits in it for the cloud's whole life loses the cloud's hull, and no health: the hull takes it.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 3 * 200 + 60)
    public void theGasEatsTheHullOfAPodWithAPersonAboardForAsLongAsItHangs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = DrifterArenas.slot(5);
        Arena[] made = {null};
        int[] phase = {0};
        long[] since = {0};
        boolean[] released = {false};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            PodEntity pod = arena.pod();
            long tick = helper.getTick() - since[0];
            switch (phase[0]) {
                case 0 -> {
                    if (!released[0]) {
                        released[0] = true;
                        SporeClouds.release(level, arena.at(-6, 1, 0));
                    }
                    if (tick == TUNING.cloud().ticks() + 10) {
                        expect(
                                helper,
                                pod.hull() == pod.maxHull(),
                                "a cloud 11 blocks off ate %s of the hull",
                                pod.maxHull() - pod.hull());
                        // Off the pod and out of the gas of the next phase: a person on foot in it is poisoned, which
                        // is another test.
                        arena.pilot().player().stopRiding();
                        arena.pilot().teleportTo(level, arena.at(-5, 0, 4), 0f, 0f);
                        next(phase, since, released, helper);
                    }
                }
                case 1 -> {
                    if (!released[0]) {
                        released[0] = true;
                        SporeClouds.release(level, pod.position().add(0, 1, 0));
                    }
                    if (tick == TUNING.cloud().ticks() + 10) {
                        expect(
                                helper,
                                pod.hull() == pod.maxHull(),
                                "a cloud ate %s of the hull of a pod with no one aboard",
                                pod.maxHull() - pod.hull());
                        arena.pilot().teleportTo(level, pod.position(), 90f, 0f);
                        expect(helper, arena.pilot().player().startRiding(pod), "the pilot could not mount again");
                        next(phase, since, released, helper);
                    }
                }
                default -> {
                    if (!released[0]) {
                        released[0] = true;
                        SporeClouds.release(level, pod.position().add(0, 1, 0));
                    }
                    if (tick == TUNING.cloud().ticks() + 10) {
                        float lost = pod.maxHull() - pod.hull();
                        float expected =
                                TUNING.cloud().hullPerSecond() * TUNING.cloud().ticks() / 20f;
                        LOGGER.info(
                                "[drifter] a pilot who sits in one cloud for its {} ticks loses {} of {} hull",
                                TUNING.cloud().ticks(),
                                lost,
                                pod.maxHull());
                        expect(
                                helper,
                                Math.abs(lost - expected) <= 0.5f,
                                "the hull lost %s in one cloud, the tuning says %s",
                                lost,
                                expected);
                        expect(
                                helper,
                                arena.pilot().player().getHealth()
                                        == arena.pilot().player().getMaxHealth(),
                                "the pilot was hurt");
                        expect(
                                helper,
                                !arena.pilot().player().hasEffect(MobEffects.POISON)
                                        && !arena.pilot().player().hasEffect(MobEffects.BLINDNESS),
                                "the pod's pilot was poisoned or blinded");
                        arena.clear();
                        helper.succeed();
                    }
                }
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Arena.build(helper, level, origin);
            made[0].pilot().player().setGameMode(GameType.SURVIVAL);
            since[0] = helper.getTick();
        });
    }

    private static void next(int[] phase, long[] since, boolean[] released, GameTestHelper helper) {
        phase[0]++;
        since[0] = helper.getTick();
        released[0] = false;
    }

    /** On foot the gas poisons and blinds a person in it, and a person outside it is left alone. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 60)
    public void onFootTheGasPoisonsAndBlinds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = DrifterArenas.slot(6);
        Arena[] made = {null};
        MockPlayer[] inside = {null};
        MockPlayer[] outside = {null};
        long[] since = {0};
        TestTicks.each(helper, () -> {
            if (made[0] == null) {
                return;
            }
            if (helper.getTick() - since[0] == 2L * TUNING.cloud().lookTicks()) {
                expect(helper, inside[0].player().hasEffect(MobEffects.POISON), "a person in the gas was not poisoned");
                expect(
                        helper,
                        inside[0].player().hasEffect(MobEffects.BLINDNESS),
                        "a person in the gas was not blinded");
                expect(
                        helper,
                        !outside[0].player().hasEffect(MobEffects.POISON)
                                && !outside[0].player().hasEffect(MobEffects.BLINDNESS),
                        "a person 8 blocks from the gas was poisoned or blinded");
                inside[0].leave();
                outside[0].leave();
                made[0].clear();
                helper.succeed();
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Arena.build(helper, level, origin);
            inside[0] = MockPlayers.join(helper, "drifter-inside");
            inside[0].player().setGameMode(GameType.SURVIVAL);
            inside[0].teleportTo(level, made[0].at(-4, 0, 0), 0f, 0f);
            outside[0] = MockPlayers.join(helper, "drifter-outside");
            outside[0].player().setGameMode(GameType.SURVIVAL);
            outside[0].teleportTo(level, made[0].at(-4, 0, 5.5), 0f, 0f);
            SporeClouds.release(level, made[0].at(-4, 1, -3));
            since[0] = helper.getTick();
        });
    }

    /**
     * A pop at range is safe: a person with a blow far from the pod and from themselves pops a drifter, the gas hangs where it floated, and
     * the pod 11 blocks off loses nothing, while a bystander at the spot is poisoned, which shows the gas is there.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 260)
    public void aPopAtRangeIsSafe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = DrifterArenas.slot(7);
        Arena[] made = {null};
        MockPlayer[] striker = {null};
        MockPlayer[] bystander = {null};
        Drifter[] drifter = {null};
        long[] since = {0};
        TestTicks.each(helper, () -> {
            if (made[0] == null) {
                return;
            }
            long tick = helper.getTick() - since[0];
            if (tick == 2L * TUNING.cloud().lookTicks()) {
                expect(
                        helper,
                        bystander[0].player().hasEffect(MobEffects.POISON),
                        "no gas hangs where the drifter was popped");
                expect(
                        helper,
                        !striker[0].player().hasEffect(MobEffects.POISON),
                        "the one who popped it from 10 blocks was poisoned");
            }
            if (tick == TUNING.cloud().ticks() + 10) {
                expect(
                        helper,
                        made[0].pod().hull() == made[0].pod().maxHull(),
                        "the gas of a pop 11 blocks from the pod ate %s of its hull",
                        made[0].pod().maxHull() - made[0].pod().hull());
                LOGGER.info("[drifter] a pop 11 blocks from the pod and 10 from the striker cost neither");
                striker[0].leave();
                bystander[0].leave();
                made[0].clear();
                helper.succeed();
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Arena.build(helper, level, origin);
            drifter[0] = made[0].drifter(made[0].at(-5, 1, -4));
            drifter[0].setNoAi(true);
            bystander[0] = MockPlayers.join(helper, "drifter-bystander");
            bystander[0].player().setGameMode(GameType.SURVIVAL);
            bystander[0].teleportTo(level, made[0].at(-5, 0, -3), 0f, 0f);
            striker[0] = MockPlayers.join(helper, "drifter-striker");
            striker[0].player().setGameMode(GameType.SURVIVAL);
            striker[0].teleportTo(level, made[0].at(2, 0, 3.5), 0f, 0f);
            boolean hurt = drifter[0].hurtServer(
                    level, level.damageSources().playerAttack(striker[0].player()), TUNING.maxHealth());
            expect(helper, hurt, "a blow of %s did not hurt the drifter", TUNING.maxHealth());
            expect(helper, drifter[0].isRemoved(), "a blow that kills a drifter did not burst it");
            since[0] = helper.getTick();
        });
    }

    /**
     * The count is of pods, not of people: a Prospector with two people in it drilling is one source, and the people on foot are one more
     * however many they are. A per-person count would make the two walkers two sources, and the pod's second rider one.
     */
    @GameTest(
            dimension = SAMPLE,
            environment = CampaignEnvironments.D,
            maxTicks = FarChunks.AWAIT_BUDGET_TICKS + HEAR_TICKS + 60)
    public void aSourceIsAPodNotAPersonAndTheCrewOnFootIsOne(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(26_000, CampaignBands.LAYER_1.minY() + 80, 26_000);
        Arena[] made = {null};
        MockPlayer[] second = {null};
        MockPlayer[] first = {null};
        MockPlayer[] other = {null};
        long[] since = {0};
        int[] most = {0};
        boolean[] both = {false};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            long tick = helper.getTick() - since[0];
            // Two people walk their circles in the room's west half, apart.
            walk(first[0], arena, new Phase("", -4, -3, 0, 0, WALK_SPEED, false, false), tick);
            walk(other[0], arena, new Phase("", -4, 3, 0, 0, WALK_SPEED, false, false), tick);
            List<Drifters.Source> here = Drifters.sources(level);
            long podSources = here.stream()
                    .filter(source -> source.pos().distanceTo(arena.pod().position()) < 3)
                    .count();
            // The crew on foot is one source, wherever the level's first footstep was: the rest of the sources are the
            // pod's.
            long onFoot = here.stream().filter(source -> !source.pod()).count();
            most[0] = Math.max(most[0], (int) onFoot);
            both[0] |= podSources == 1 && onFoot == 1;
            expect(helper, podSources <= 1, "two people in one Prospector are %d sources: %s", podSources, here);
            expect(helper, onFoot <= 1, "two people on foot are %d sources, not 1: %s", onFoot, here);
            Drifters.heardBy(level, arena.pod().position(), 1.0)
                    .filter(noise -> noise.pod())
                    .ifPresent(noise -> expect(
                            helper,
                            noise.listener().equals(arena.pilot().player().getUUID()),
                            "the cue of a pod goes to %s, not the pilot",
                            noise.listener()));
            if (tick == HEAR_TICKS) {
                expect(helper, both[0], "the pod and the crew on foot were never both heard: %s", here);
                first[0].leave();
                other[0].leave();
                second[0].leave();
                arena.clear();
                helper.succeed();
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            // The passenger joins first, so that a level's player list meets them before the pilot.
            second[0] = MockPlayers.join(helper, "drifter-second");
            made[0] = Arena.build(helper, level, origin, PodRegistry.PROSPECTOR);
            second[0].teleportTo(level, made[0].pod().position(), 90f, 0f);
            expect(helper, second[0].player().startRiding(made[0].pod()), "the second seat is taken");
            made[0].pilot().setInput(SPRINT);
            first[0] = MockPlayers.join(helper, "drifter-first");
            first[0].player().setGameMode(GameType.SURVIVAL);
            other[0] = MockPlayers.join(helper, "drifter-other");
            other[0].player().setGameMode(GameType.SURVIVAL);
            since[0] = helper.getTick();
        });
    }

    /** The drifter is named in the sonar tag, floats and is not saved. */
    @GameTest
    public void theDrifterIsRegisteredAndNamedInTheSonarTag(GameTestHelper helper) {
        TagKey<EntityType<?>> tag = TagKey.create(
                Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("deepcharter", "sonar_echo/empty_cave"));
        expect(
                helper,
                BuiltInRegistries.ENTITY_TYPE
                        .wrapAsHolder(CreatureRegistry.DRIFTER)
                        .is(tag),
                "the drifter is not in deepcharter:sonar_echo/empty_cave");
        expect(
                helper,
                TestEntities.create(CreatureRegistry.DRIFTER, helper.getLevel()).isNoGravity(),
                "a bladder of gas has weight");
        expect(helper, !CreatureRegistry.DRIFTER.canSerialize(), "a drifter is saved with its chunk");
        helper.succeed();
    }
}
