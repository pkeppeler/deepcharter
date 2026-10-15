package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;

/**
 * Evidence scenario "retained-drain" (#449, #485): a Retained comes to a dark pod, grabs it and drains the tank, and only its death frees the pod.
 *
 * <p>The scene is a sealed chamber of layer 2's rock, dark, with the camera in front of a pilot's pod (third person, the HUD on so that the fuel
 * reads) and night vision so that the dark can be seen. A figure walks to the pod and clings to the hull; the
 * tank drops; the pilot drives west, and the figure rides along; at the west wall the pod drills into the rock and the cutter bites the figure
 * until it dissolves and the pod is free. A second figure comes to the pod once it is lit: it does not, and walks on. The segments are logged as
 * {@code [retained-drain-segment] <name> <first>-<last>}, which is how the GIFs are cut.
 */
public class RetainedDrainScenario extends EvidenceScenario {
    private static final int LENGTH = 30;
    private static final int HALF_WIDTH = 4;
    private static final int HEIGHT = 8;
    private static final double POD_X = 18.5;
    /** The pod stands in the middle of the chamber and faces west, down its length, with the camera behind it; the figure comes from the north wall. */
    private static final double POD_Z = 0.5;

    private static final double FIGURE_X = 18.5;
    private static final double FIGURE_Z = -3.5;
    private static final int TICKS_PER_FRAME = 2;
    /** The approach still is taken when the figure is this close to the pod: in view: the camera stands 4 blocks in front of the pod, looking at it. */
    private static final double APPROACH_STILL_BLOCKS = 4;

    private static final int APPROACH_FRAMES_LIMIT = 150;
    private static final int DRAIN_FRAMES = 30;
    private static final int FREED_FRAMES_LIMIT = 150;
    private static final int DISSOLVE_TAIL_FRAMES = 20;
    /** Frames a lit pod is watched while a figure walks the chamber. */
    private static final int LIT_WATCH_FRAMES = 60;
    /** Yaw 90 faces -x, west down the chamber. */
    private static final float DOWN_THE_CHAMBER = 90f;
    /** Frames of the drive before the still of the ride, and the frames after the drill starts before the still of the cutter. */
    private static final int RIDE_STILL_FRAMES = 12;

    private static final int BITE_STILL_FRAMES = 6;

    private int framesTaken;
    private int podId;
    private ClientGameTestContext ctx;
    private RetainedChamber chamber;
    private int figureId;

    @Override
    protected String name() {
        return "retained-drain";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        ClientChecks.withHudShown(context, () -> runShown(context));
    }

    private void runShown(ClientGameTestContext context) {
        ctx = context;
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            chamber = new RetainedChamber(context, singleplayer);
            CameraType cameraType = context.computeOnClient(client -> client.options.getCameraType());
            EvidencePins.lightFlickerOff = true;
            try {
                setUp();
                approachAndDrain();
                driveAndKill();
                litPodIsLeftAlone();
            } finally {
                EvidencePins.reset();
                context.runOnClient(client -> client.options.setCameraType(cameraType));
            }
        }
    }

    private void setUp() {
        chamber.enterLayer(
                RetainedChamber.FLOOR.getX() + POD_X,
                RetainedChamber.FLOOR.getY() + 3,
                RetainedChamber.FLOOR.getZ() + POD_Z,
                DOWN_THE_CHAMBER,
                12f);
        chamber.serverDo(server -> {
            ServerLevel level = RetainedChamber.layer(server);
            RetainedChamber.buildChamber(level, LENGTH, HALF_WIDTH, HEIGHT);
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            Charters.found(server, player.getUUID(), "Demo Charter");
            PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
            pod.setPos(RetainedChamber.at(POD_X, 1, POD_Z));
            pod.setYRot(DOWN_THE_CHAMBER);
            level.addFreshEntity(pod);
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

    private float fuel() {
        return chamber.serverGet(
                server -> ((PodEntity) RetainedChamber.layer(server).getEntity(podId)).fuel());
    }

    private boolean clinging() {
        return chamber.serverGet(
                server -> ((LamplessFigure) RetainedChamber.layer(server).getEntity(figureId)).isClinging());
    }

    private boolean drilling() {
        return chamber.serverGet(
                server -> ((PodEntity) RetainedChamber.layer(server).getEntity(podId)).drilling());
    }

    private boolean dying() {
        return chamber.serverGet(
                server -> RetainedChamber.layer(server).getEntity(figureId) instanceof LamplessFigure figure
                        && !figure.isAlive());
    }

    private boolean gone() {
        return chamber.serverGet(server -> RetainedChamber.layer(server).getEntity(figureId) == null);
    }

    private void sendFigure() {
        chamber.serverDo(server -> {
            ServerLevel level = RetainedChamber.layer(server);
            LamplessFigure figure = CreatureRegistry.LAMPLESS_FIGURE.create(level, EntitySpawnReason.COMMAND);
            figure.setLamp(true);
            figure.setPos(RetainedChamber.at(FIGURE_X, 1, FIGURE_Z));
            figure.setHeading(Direction.SOUTH);
            level.addFreshEntity(figure);
            figureId = figure.getId();
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

    /** The figure walks to the pod and grabs it; the tank drops while it holds on. */
    private void approachAndDrain() {
        sendFigure();
        int first = framesTaken + 1;
        boolean shotApproach = false;
        int walked = 0;
        while (!clinging()) {
            record(1);
            if (++walked > APPROACH_FRAMES_LIMIT) {
                throw new AssertionError("the figure had not grabbed the dark pod " + APPROACH_FRAMES_LIMIT
                        + " frames after it set off");
            }
            if (!shotApproach
                    && chamber.serverGet(server -> RetainedChamber.layer(server)
                                    .getEntity(figureId)
                                    .distanceTo(RetainedChamber.layer(server).getEntity(podId))
                            < APPROACH_STILL_BLOCKS)) {
                screenshot(ctx, "retained-approach");
                shotApproach = true;
            }
        }
        float before = fuel();
        record(8);
        screenshot(ctx, "retained-clinging");
        record(DRAIN_FRAMES - 8);
        screenshot(ctx, "retained-drained");
        System.out.println("[retained-drain] fuel " + before + " at the grab, " + fuel() + " after " + DRAIN_FRAMES
                + " frames of the grip");
        System.out.println("[retained-drain-segment] retained-grab " + first + "-" + framesTaken);
    }

    /**
     * The pilot drives west with the figure on the hull, and nothing frees the pod: the figure rides along. At the wall the pod drills into the
     * rock, the cutter bites the figure until it dies, and the figure dissolves. Two segments, the ride and the kill.
     */
    private void driveAndKill() {
        int first = framesTaken + 1;
        ctx.getInput().holdKey(options -> options.keyUp);
        int driven = 0;
        boolean shotRide = false;
        while (!drilling()) {
            record(1);
            if (!shotRide && ++driven == RIDE_STILL_FRAMES) {
                if (!clinging()) {
                    throw new AssertionError("the figure let go of a pod that drove off: driving must not free it");
                }
                screenshot(ctx, "retained-rides-along");
                shotRide = true;
            }
            if (driven > FREED_FRAMES_LIMIT) {
                throw new AssertionError(
                        "the pod had not reached the wall " + FREED_FRAMES_LIMIT + " frames after it set off");
            }
        }
        System.out.println("[retained-drain-segment] retained-ride " + first + "-" + framesTaken);
        first = framesTaken + 1;
        record(BITE_STILL_FRAMES);
        screenshot(ctx, "retained-bitten");
        int bitten = 0;
        while (!dying()) {
            record(1);
            if (++bitten > FREED_FRAMES_LIMIT) {
                throw new AssertionError(
                        "the figure still lived " + FREED_FRAMES_LIMIT + " frames after the cutter began to bite");
            }
        }
        ctx.getInput().releaseKey(options -> options.keyUp);
        record(3);
        screenshot(ctx, "retained-dissolving");
        int dissolving = 0;
        while (!gone()) {
            record(1);
            if (++dissolving > FREED_FRAMES_LIMIT) {
                throw new AssertionError(
                        "the figure had not dissolved " + FREED_FRAMES_LIMIT + " frames after it died");
            }
        }
        record(DISSOLVE_TAIL_FRAMES);
        screenshot(ctx, "retained-gone");
        System.out.println("[retained-drain-segment] retained-kill " + first + "-" + framesTaken);
    }

    /** The pod's lights part is fitted, and a second figure walks the chamber and does not come to it. */
    private void litPodIsLeftAlone() {
        chamber.serverDo(server -> {
            PodEntity pod = (PodEntity) RetainedChamber.layer(server).getEntity(podId);
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            CharterId charter = Charters.charterOfOrThrow(server, player.getUUID())
                    .orElseThrow()
                    .id();
            PodComponents.register(pod, charter);
            PodComponents.install(pod, ComponentItems.mint(server, ComponentTrack.LIGHTS, 2, charter));
            // Back to the middle of the chamber, so that the figure walks in view of the camera behind the pod.
            pod.teleportTo(
                    RetainedChamber.at(POD_X, 1, POD_Z).x,
                    RetainedChamber.at(POD_X, 1, POD_Z).y,
                    RetainedChamber.at(POD_X, 1, POD_Z).z);
        });
        chamber.settle();
        int first = framesTaken + 1;
        sendFigure();
        record(LIT_WATCH_FRAMES);
        if (clinging()) {
            throw new AssertionError("a figure clung to a lit pod");
        }
        screenshot(ctx, "retained-lit-left-alone");
        System.out.println("[retained-drain-segment] retained-light " + first + "-" + framesTaken);
    }
}
