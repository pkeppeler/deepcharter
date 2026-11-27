package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.EntityTickingGate;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Server GameTests for {@link EntityTickingGate} (#1089): a pod driven east out of an awaited chunk into the next,
 * which is loaded but not entity-ticking. Each case has a chunk column no other test forces. A case that must fail is a
 * {@code required = false} subject the gate fails, and a {@code ...Fails...} test beside it reads the outcome; the
 * environment keeps them in one batch.
 */
public class EntityTickingGateTest {
    private static final String ENVIRONMENT = "deepcharter-test:entity_ticking_gate";
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 300;
    /** The chunk row of every case; the cases sit {@link #COLUMN_GAP} chunks apart along x. */
    private static final int CHUNK_Z = 6000;

    private static final int COLUMN_GAP = 8;
    private static final int CROSSING_COLUMN = 6000;
    private static final int EXACT_COLUMN = CROSSING_COLUMN + COLUMN_GAP;
    private static final int SHORT_COLUMN = EXACT_COLUMN + COLUMN_GAP;
    private static final int OPT_OUT_COLUMN = SHORT_COLUMN + COLUMN_GAP;
    private static final int SET_POS_COLUMN = OPT_OUT_COLUMN + COLUMN_GAP;
    private static final int SHARED_COLUMN = SET_POS_COLUMN + COLUMN_GAP;
    private static final float EAST = -90f;
    private static final int DRIVE_TICKS = 10;
    private static final int SETTLE_TICKS = 10;
    /** How far past the border the crossing pod stops, were it not stalled there. */
    private static final double OVERSHOOT = 1.0;
    /** How far short of the border the pod that stops short stops. */
    private static final double SHORT_BY = 0.05;

    private static final AtomicReference<@Nullable AABB> CROSSING = new AtomicReference<>();
    private static final AtomicReference<@Nullable AABB> EXACT = new AtomicReference<>();
    private static final AtomicReference<@Nullable AABB> OPTED_OUT = new AtomicReference<>();
    private static final AtomicReference<Boolean> SHARED_DRIVEN = new AtomicReference<>(false);
    private static final AtomicReference<Boolean> OPTED_OUT_WHILE_RUNNING = new AtomicReference<>(false);

    private static <T> T got(AtomicReference<@Nullable T> ref) {
        return Objects.requireNonNull(ref.get(), "<set by an earlier step of the test>");
    }

    private static double border(int column) {
        return (column + 1) * 16;
    }

    /**
     * Drives a pod east to {@code border + stopAt}, with its pilot, out of the awaited chunk {@code column}, into the
     * chunk beyond it, and runs {@code after} once the pod has stopped or the game has stopped it, a few ticks later.
     */
    private static void driveEast(GameTestHelper helper, int column, double stopAt, Consumer<PodEntity> after) {
        ServerLevel level = helper.getLevel();
        double speed = PodTuning.DEFAULT.movement().horizontalSpeed();
        double travel = DRIVE_TICKS * speed;
        double startX = border(column) + stopAt - travel;
        Vec3 start = new Vec3(startX, helper.absolutePos(BlockPos.ZERO).getY() + 2, CHUNK_Z * 16 + 8);
        boolean[] ready = {false};
        AtomicReference<@Nullable PodEntity> pod = new AtomicReference<>();
        AtomicReference<@Nullable MockPlayer> pilot = new AtomicReference<>();
        int[] stoppedFor = {0};
        FarChunks.awaitAllEntityTicking(helper, level, List.of(BlockPos.containing(start)), () -> ready[0] = true);
        TestTicks.each(helper, () -> {
            PodEntity driven = pod.get();
            if (driven == null) {
                return;
            }
            boolean arrived = driven.getX() - startX >= travel - 1e-9;
            boolean stalled = driven.getBlockX() >> 4 > column;
            if (arrived || stalled) {
                MockPlayer driver = pilot.get();
                if (driver != null) {
                    driver.releaseInput();
                }
                stoppedFor[0]++;
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!ready[0] || level.getChunkSource().getChunkNow(column + 1, CHUNK_Z) == null) {
                        throw helper.assertionException("chunk %s,%s is not loaded yet", column + 1, CHUNK_Z);
                    }
                })
                .thenExecute(() -> {
                    if (level.isPositionEntityTicking(BlockPos.containing(border(column) + 1, start.y, start.z))) {
                        throw failure(helper, "chunk %s,%s ticks, so the case proves nothing", column + 1, CHUNK_Z);
                    }
                    MockPlayer driver = MockPlayers.join(helper, "gate-pilot-" + column);
                    driver.teleportTo(level, start, EAST, 0f);
                    PodEntity driven = TestEntities.create(PodRegistry.POD, level);
                    driven.setPos(start);
                    // A pod drives along its own facing, not the pilot's look (#1307).
                    driven.setYRot(EAST);
                    level.addFreshEntity(driven);
                    if (!driver.player().startRiding(driven)) {
                        throw failure(helper, "the pilot could not mount the pod");
                    }
                    driver.setInput(FORWARD);
                    pilot.set(driver);
                    pod.set(driven);
                })
                .thenWaitUntil(() -> {
                    if (stoppedFor[0] < SETTLE_TICKS) {
                        throw helper.assertionException("the pod is still driving");
                    }
                })
                .thenExecute(() -> after.accept(TestEntities.require(level, got(pod).getId(), PodEntity.class)));
    }

    /**
     * Reads the outcome of the test the gate must have failed: waits for it, then checks its message names the chunk
     * and the fix.
     */
    private static void expectGateFailure(
            GameTestHelper helper, AtomicReference<@Nullable AABB> subject, int column, String what) {
        FarChunks.Deadline deadline = FarChunks.deadline();
        TestTicks.each(helper, () -> {
            AABB bounds = subject.get();
            String failed = bounds == null ? null : EntityTickingGate.failureOf(bounds);
            deadline.await(helper, helper.getLevel(), failed != null, () -> "the gate did not fail " + what);
            if (failed == null) {
                return;
            }
            String message = failed;
            String chunk = "[" + (column + 1) + ", " + CHUNK_Z + "]";
            for (String part : List.of(chunk, "not entity-ticking", "awaitAllEntityTicking", "allowUnticked")) {
                if (!message.contains(part)) {
                    throw failure(helper, "the failure of %s should contain %s: %s", what, part, message);
                }
            }
            helper.succeed();
        });
    }

    /**
     * Fails by design: a pod drives into a chunk the game does not tick. {@link
     * #theGateFailsAPodDrivenIntoAChunkThatDoesNotTick} reads it.
     */
    @GameTest(maxTicks = MAX_TICKS, environment = ENVIRONMENT, required = false)
    public void subjectAPodDrivenAcrossTheBorder(GameTestHelper helper) {
        CROSSING.set(helper.getBounds());
        EntityTickingGate.expectFailure(helper.getBounds());
        driveEast(helper, CROSSING_COLUMN, OVERSHOOT, pod -> {
            throw failure(helper, "the gate let the pod drive into a chunk that does not tick");
        });
    }

    @GameTest(maxTicks = MAX_TICKS, environment = ENVIRONMENT)
    public void theGateFailsAPodDrivenIntoAChunkThatDoesNotTick(GameTestHelper helper) {
        expectGateFailure(helper, CROSSING, CROSSING_COLUMN, "a pod driven over the border");
    }

    /** Fails by design: the pod stops exactly on the border, which is the next chunk. */
    @GameTest(maxTicks = MAX_TICKS, environment = ENVIRONMENT, required = false)
    public void subjectAPodStoppingExactlyOnTheBorder(GameTestHelper helper) {
        EXACT.set(helper.getBounds());
        EntityTickingGate.expectFailure(helper.getBounds());
        driveEast(helper, EXACT_COLUMN, 0, pod -> {
            throw failure(helper, "the gate let the pod stop on the border of a chunk that does not tick");
        });
    }

    @GameTest(maxTicks = MAX_TICKS, environment = ENVIRONMENT)
    public void theGateFailsAPodThatStopsExactlyOnTheBorder(GameTestHelper helper) {
        expectGateFailure(helper, EXACT, EXACT_COLUMN, "a pod stopping exactly on the border");
    }

    /** A pod that stops short of the border never leaves the chunk it was awaited in, so its test is left alone. */
    @GameTest(maxTicks = MAX_TICKS, environment = ENVIRONMENT)
    public void aPodThatStopsJustShortOfTheBorderPasses(GameTestHelper helper) {
        driveEast(helper, SHORT_COLUMN, -SHORT_BY, pod -> {
            if (pod.getX() >= border(SHORT_COLUMN) || pod.getX() < border(SHORT_COLUMN) - 2 * SHORT_BY) {
                throw failure(helper, "the pod should stop just short of the border, at x %s", pod.getX());
            }
            helper.succeed();
        });
    }

    /** A test about a chunk that does not tick opts out, and its pod is left to stall at the border. */
    @GameTest(maxTicks = MAX_TICKS, environment = ENVIRONMENT)
    public void aTestThatOptsOutMayDriveIntoAChunkThatDoesNotTick(GameTestHelper helper) {
        OPTED_OUT.set(helper.getBounds());
        FarChunks.allowUnticked(
                helper, "the test is about the stall of a pod at the border of a chunk that does not tick");
        driveEast(helper, OPT_OUT_COLUMN, OVERSHOOT, pod -> {
            if (pod.getX() < border(OPT_OUT_COLUMN)) {
                throw failure(helper, "the pod never entered the chunk that does not tick, at x %s", pod.getX());
            }
            OPTED_OUT_WHILE_RUNNING.set(EntityTickingGate.isAllowed(helper.getBounds()));
            helper.succeed();
        });
    }

    /**
     * The opt-out is the test's own: once it finished, the gate has forgotten it, or the next test at its bounds would
     * inherit it.
     */
    @GameTest(maxTicks = MAX_TICKS, environment = ENVIRONMENT)
    public void theOptOutIsForgottenWhenItsTestFinishes(GameTestHelper helper) {
        FarChunks.Deadline deadline = FarChunks.deadline();
        TestTicks.each(helper, () -> {
            AABB bounds = OPTED_OUT.get();
            boolean finished = bounds != null && OPTED_OUT_WHILE_RUNNING.get() && !EntityTickingGate.isAllowed(bounds);
            deadline.await(
                    helper, helper.getLevel(), finished, () -> "the opted-out test did not finish and be forgotten");
            if (finished) {
                helper.succeed();
            }
        });
    }

    /**
     * A {@code setPos} across the border is a reposition, not a drive: nothing the pod did stalled it, so the gate
     * leaves it alone.
     */
    @GameTest(maxTicks = MAX_TICKS, environment = ENVIRONMENT)
    public void aRepositionAcrossTheBorderIsNotADrive(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 before = new Vec3(
                border(SET_POS_COLUMN) - 0.5, helper.absolutePos(BlockPos.ZERO).getY() + 2, CHUNK_Z * 16 + 8);
        boolean[] ready = {false};
        AtomicReference<@Nullable PodEntity> pod = new AtomicReference<>();
        int[] settled = {0};
        FarChunks.awaitAllEntityTicking(helper, level, List.of(BlockPos.containing(before)), () -> ready[0] = true);
        TestTicks.each(helper, () -> {
            if (pod.get() != null) {
                settled[0]++;
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!ready[0] || level.getChunkSource().getChunkNow(SET_POS_COLUMN + 1, CHUNK_Z) == null) {
                        throw helper.assertionException("the chunks are not loaded yet");
                    }
                })
                .thenExecute(() -> {
                    PodEntity moved = TestEntities.create(PodRegistry.POD, level);
                    moved.setPos(before);
                    level.addFreshEntity(moved);
                    pod.set(moved);
                })
                .thenWaitUntil(() -> {
                    if (settled[0] < 2) {
                        throw helper.assertionException("the pod has not been seen yet");
                    }
                })
                .thenExecute(() -> got(pod).setPos(before.add(1, 0, 0)))
                .thenWaitUntil(() -> {
                    if (settled[0] < 2 + SETTLE_TICKS) {
                        throw helper.assertionException("waiting for the gate to look");
                    }
                })
                .thenExecute(() -> {
                    if (got(pod).getX() < border(SET_POS_COLUMN)) {
                        throw failure(helper, "the pod should be in the next chunk, at x %s", got(pod).getX());
                    }
                    helper.succeed();
                });
    }

    /**
     * Two tests hold the chunk the pod drives out of, so neither owns the pod: the gate blames neither. This one drives
     * and passes.
     */
    @GameTest(maxTicks = MAX_TICKS, environment = ENVIRONMENT)
    public void aPodDrivenOutOfAChunkTwoTestsHoldIsNobodysToBlame(GameTestHelper helper) {
        driveEast(helper, SHARED_COLUMN, OVERSHOOT, pod -> {
            if (pod.getX() < border(SHARED_COLUMN)) {
                throw failure(helper, "the pod never entered the chunk that does not tick, at x %s", pod.getX());
            }
            SHARED_DRIVEN.set(true);
            helper.succeed();
        });
    }

    /**
     * The second holder of that chunk drives nothing and must pass: it stays running until the drive is over, so the
     * gate could blame it.
     */
    @GameTest(maxTicks = MAX_TICKS, environment = ENVIRONMENT)
    public void theOtherHolderOfTheChunkIsNotBlamedForTheDrive(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos inChunk = new BlockPos(
                SHARED_COLUMN * 16 + 8, helper.absolutePos(BlockPos.ZERO).getY() + 2, CHUNK_Z * 16 + 8);
        FarChunks.awaitAllEntityTicking(helper, level, List.of(inChunk), () -> {});
        TestTicks.each(helper, () -> {
            if (SHARED_DRIVEN.get()) {
                helper.succeed();
            }
        });
    }

    /**
     * Who owns a position: the one test whose bounds contain it, else the one that holds its chunk; none, or two of
     * either, is nobody.
     */
    @GameTest(environment = ENVIRONMENT)
    public void aPositionHasAnOwnerOnlyWhenOneTestClaimsIt(GameTestHelper helper) {
        AABB here = new AABB(0, 0, 0, 10, 10, 10);
        AABB elsewhere = new AABB(100, 0, 0, 110, 10, 10);
        Vec3 inHere = new Vec3(5, 5, 5);
        Vec3 nowhere = new Vec3(50, 5, 5);
        int[] got = {
            // Bounds own it, whatever holds the chunk.
            EntityTickingGate.ownerOf(claims(elsewhere, false, here, true), inHere),
            EntityTickingGate.ownerOf(claims(elsewhere, true, here, true), inHere),
            // Two structures whose bounds contain it: nobody.
            EntityTickingGate.ownerOf(claims(here, false, here, false), inHere),
            // One holder owns it; two holders, or none, do not.
            EntityTickingGate.ownerOf(claims(here, false, elsewhere, true), nowhere),
            EntityTickingGate.ownerOf(claims(here, true, elsewhere, true), nowhere),
            EntityTickingGate.ownerOf(claims(here, false, elsewhere, false), nowhere)
        };
        int[] expected = {1, 1, -1, 1, -1, -1};
        for (int i = 0; i < got.length; i++) {
            if (got[i] != expected[i]) {
                throw failure(helper, "case %s: owner %s, expected %s", i, got[i], expected[i]);
            }
        }
        helper.succeed();
    }

    private static List<EntityTickingGate.Claim> claims(
            AABB first, boolean firstHolds, AABB second, boolean secondHolds) {
        return List.of(
                new EntityTickingGate.Claim(first, firstHolds), new EntityTickingGate.Claim(second, secondHolds));
    }
}
