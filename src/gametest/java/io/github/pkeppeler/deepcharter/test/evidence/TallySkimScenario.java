package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.Tally;
import io.github.pkeppeler.deepcharter.creature.TallyTuning;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Evidence scenario "tally-skim" (#917): a Tally trails a pod that holds ore, clamps to the cargo hatch, and takes one ore as a quality sample;
 * a second one clamps, and the pilot drills, and the cutter breaks it before it can take anything.
 *
 * <p>The scene is a sealed, dark chamber of layer 2's rock, with the camera behind a pilot's pod (third person, the HUD on) and night vision so
 * that the dark can be seen. The trail takes 30 seconds, so the scenario waits most of it out without frames: a segment is the flight to the
 * trail, the clamp and the sample, or the clamp and the cutter. The segments are logged as {@code [tally-skim-segment] <name> <first>-<last>},
 * which is how the GIFs are cut.
 */
public class TallySkimScenario extends EvidenceScenario {
    private static final int LENGTH = 30;
    private static final int HALF_WIDTH = 4;
    private static final int HEIGHT = 8;
    private static final double POD_X = 18.5;
    /** The pod stands in the middle of the chamber and faces west, with the camera behind it, so that the camera sees the hatch. */
    private static final double POD_Z = 0.5;

    /** The pod's place for the cutter: two and a half blocks from the west wall. */
    private static final double NEAR_WALL_X = 3.5;

    /**
     * The Tally starts beside the pod on the camera's side, up and to the left of the view (the camera hangs 4 blocks behind the pilot, looking
     * west), so that it trails in frame with its amber eye toward the lens; coming from the front it trailed out of view behind the pod.
     */
    private static final double TALLY_X = POD_X + 2.5;

    private static final double TALLY_Z = -2.5;
    private static final double TALLY_HEIGHT = 3;
    private static final int ORES = 3;
    private static final int TICKS_PER_FRAME = 2;
    /** Frames of the Tally flying to its trail, before the wait. */
    private static final int TRAIL_FRAMES = 40;
    /** Ticks waited without frames, leaving the last stretch of the trail and the clamp for the frames. */
    private static final int SKIPPED_TICKS = 440;

    private static final int WAIT_STEP_TICKS = 20;
    private static final int CLAMP_FRAMES_LIMIT = 200;
    private static final int AFTER_CLAMP_FRAMES = 10;
    private static final int GONE_FRAMES_LIMIT = 150;
    private static final int TAIL_FRAMES = 15;
    /** Yaw 90 faces -x, west down the chamber. */
    private static final float DOWN_THE_CHAMBER = 90f;

    private int framesTaken;
    private int podId;
    private int tallyId;

    @Override
    protected String name() {
        return "tally-skim";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        ClientChecks.withHudShown(context, () -> runShown(context));
    }

    private void runShown(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            Run run = new Run(context, new RetainedChamber(context, singleplayer));
            CameraType cameraType = context.computeOnClient(client -> client.options.getCameraType());
            EvidencePins.lightFlickerOff = true;
            try {
                run.setUp();
                run.skim();
                run.cut();
            } finally {
                EvidencePins.reset();
                context.runOnClient(client -> client.options.setCameraType(cameraType));
            }
        }
    }

    private final class Run {
        private final ClientGameTestContext ctx;
        private final RetainedChamber chamber;

        Run(ClientGameTestContext ctx, RetainedChamber chamber) {
            this.ctx = ctx;
            this.chamber = chamber;
        }

        private void setUp() {
            chamber.enterLayer(
                    LENGTH,
                    HALF_WIDTH,
                    HEIGHT,
                    RetainedChamber.FLOOR.getX() + POD_X,
                    RetainedChamber.FLOOR.getY() + 3,
                    RetainedChamber.FLOOR.getZ() + POD_Z,
                    DOWN_THE_CHAMBER,
                    12f);
            chamber.serverDo(server -> {
                ServerLevel level = RetainedChamber.layer(server);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                Charters.found(server, player.getUUID(), "Demo Charter");
                PodEntity pod = TestEntities.create(PodRegistry.POD, level);
                pod.setPos(RetainedChamber.at(POD_X, 1, POD_Z));
                pod.setYRot(DOWN_THE_CHAMBER);
                level.addFreshEntity(pod);
                for (int i = 0; i < ORES; i++) {
                    pod.cargo().tryAdd(pod, OreRegistry.stack(i == 0 ? OreType.GOLDIUM : OreType.IRONIUM));
                }
                podId = pod.getId();
                player.teleportTo(level, pod.getX(), pod.getY(), pod.getZ(), Set.of(), DOWN_THE_CHAMBER, 12f, true);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            chamber.nightVision(true);
            ClientWait.until(
                    ctx,
                    "the pilot seated",
                    client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
            ctx.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            chamber.settle();
            ctx.waitTicks(20); // tick-wait: the transmission and the camera settle
        }

        private int ore() {
            return chamber.serverGet(
                    server -> TestEntities.require(RetainedChamber.layer(server), podId, PodEntity.class)
                            .cargo()
                            .entries()
                            .size());
        }

        private boolean clamped() {
            return chamber.serverGet(server -> TestEntities.require(RetainedChamber.layer(server), tallyId, Tally.class)
                    .isClamped());
        }

        private boolean drilling() {
            return chamber.serverGet(
                    server -> TestEntities.require(RetainedChamber.layer(server), podId, PodEntity.class)
                            .drilling());
        }

        private boolean broken() {
            return chamber.serverGet(server ->
                    RetainedChamber.layer(server).getEntity(tallyId) instanceof Tally tally && !tally.isAlive());
        }

        private boolean gone() {
            return chamber.serverGet(server -> RetainedChamber.layer(server).getEntity(tallyId) == null);
        }

        private void sendTally() {
            chamber.awaitEntityTicking(RetainedChamber.at(TALLY_X, TALLY_HEIGHT, TALLY_Z));
            chamber.serverDo(server -> {
                ServerLevel level = RetainedChamber.layer(server);
                Tally tally = TestEntities.mob(CreatureRegistry.TALLY, level);
                tally.setPos(RetainedChamber.at(TALLY_X, TALLY_HEIGHT, TALLY_Z));
                level.addFreshEntity(tally);
                tallyId = tally.getId();
            });
        }

        private void record(int frames) {
            for (int i = 0; i < frames; i++) {
                ctx.waitTicks(TICKS_PER_FRAME); // tick-wait: the clip is cut at fixed frames
                ctx.runOnClient(client -> TransmissionOverlay.reset());
                frame(ctx);
                framesTaken++;
            }
        }

        /** Most of the 30 second trail goes by without frames: the clip is the flight in and the last of the trail. */
        private void waitOutTheTrail() {
            for (int waited = 0; waited < SKIPPED_TICKS; waited += WAIT_STEP_TICKS) {
                ctx.waitTicks(WAIT_STEP_TICKS); // tick-wait: the trail is real time and the frames skip it
            }
        }

        private void recordUntilClamped() {
            int walked = 0;
            while (!clamped()) {
                record(1);
                if (++walked > CLAMP_FRAMES_LIMIT) {
                    throw new AssertionError(
                            "the Tally had not clamped " + CLAMP_FRAMES_LIMIT + " frames after the wait");
                }
            }
        }

        /** The Tally trails, clamps, and takes one ore, and goes. */
        private void skim() {
            int first = framesTaken + 1;
            int before = ore();
            sendTally();
            record(TRAIL_FRAMES);
            screenshot(ctx, "tally-trailing");
            waitOutTheTrail();
            recordUntilClamped();
            record(AFTER_CLAMP_FRAMES);
            screenshot(ctx, "tally-clamped");
            int waited = 0;
            while (!gone()) {
                record(1);
                if (++waited > GONE_FRAMES_LIMIT) {
                    throw new AssertionError(
                            "the Tally was still there " + GONE_FRAMES_LIMIT + " frames after it clamped");
                }
            }
            record(TAIL_FRAMES);
            screenshot(ctx, "tally-sampled");
            System.out.println("[tally-skim] the bay held " + before + " ore, and holds " + ore()
                    + " after the sample (" + TallyTuning.DEFAULT.trailTicks() / 20 + " s trail)");
            System.out.println("[tally-skim-segment] tally-skim " + first + "-" + framesTaken);
        }

        /** A second Tally clamps; the pilot drives into the west wall, so that the pod drills, and the cutter breaks it, and the bay keeps its ore. */
        private void cut() {
            int first = framesTaken + 1;
            int before = ore();
            // Near the west wall, so that the pod drills within the hold of the clamp (3 seconds).
            chamber.serverDo(server -> TestEntities.require(RetainedChamber.layer(server), podId, PodEntity.class)
                    .teleportTo(
                            RetainedChamber.at(NEAR_WALL_X, 1, POD_Z).x,
                            RetainedChamber.at(NEAR_WALL_X, 1, POD_Z).y,
                            RetainedChamber.at(NEAR_WALL_X, 1, POD_Z).z));
            chamber.settle();
            sendTally();
            record(TRAIL_FRAMES);
            waitOutTheTrail();
            recordUntilClamped();
            record(2);
            ctx.getInput().holdKey(options -> options.keyUp);
            int driven = 0;
            while (!drilling()) {
                record(1);
                if (++driven > CLAMP_FRAMES_LIMIT) {
                    throw new AssertionError("the pod did not start to drill");
                }
            }
            int bitten = 0;
            while (!broken() && !gone()) {
                record(1);
                if (++bitten > GONE_FRAMES_LIMIT) {
                    throw new AssertionError(
                            "the Tally still lived " + GONE_FRAMES_LIMIT + " frames after the cutter began");
                }
            }
            ctx.getInput().releaseKey(options -> options.keyUp);
            screenshot(ctx, "tally-cut");
            record(TAIL_FRAMES);
            if (ore() != before) {
                throw new AssertionError(
                        "the bay held " + before + " ore and holds " + ore() + " after the cutter broke it");
            }
            System.out.println("[tally-skim-segment] tally-cutter " + first + "-" + framesTaken);
        }
    }
}
