package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;

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

/**
 * Evidence scenario "retained-drain" (#449): a Retained comes to a dark pod, grabs it and drains the tank, and two ways free the pod.
 *
 * <p>The scene is a sealed chamber of layer 2's rock, dark, with the camera in front of a pilot's pod (third person, the HUD on so that the fuel
 * reads) and night vision so that the dark can be seen. A figure walks 10 blocks across the view to the pod and clings to the hull; the
 * tank drops; the pilot drives away and the figure is shaken off and fades. A second figure comes and clings; a lights part is fitted and
 * the figure lets go and fades. The segments are logged as {@code [retained-drain-segment] <name> <first>-<last>}, which is how the GIFs are cut.
 */
public class RetainedDrainScenario extends EvidenceScenario {
	private static final int LENGTH = 30;
	private static final int HALF_WIDTH = 4;
	private static final int HEIGHT = 8;
	private static final double POD_X = 14.5;
	/** The pod stands against the south side of the chamber and faces north across it, so that the camera in front of it has room, and the figure walks across the view. */
	private static final double POD_Z = 3.0;
	private static final double FIGURE_X = 24.5;
	private static final int TICKS_PER_FRAME = 2;
	/** The approach still is taken when the figure is this close to the pod: in view: the camera stands 4 blocks in front of the pod, looking at it. */
	private static final double APPROACH_STILL_BLOCKS = 4;
	private static final int APPROACH_FRAMES_LIMIT = 150;
	private static final int DRAIN_FRAMES = 30;
	private static final int FREED_FRAMES_LIMIT = 150;
	private static final int FADE_TAIL_FRAMES = 20;
	/** Yaw 180 faces -z, north across the chamber. */
	private static final float ACROSS_THE_CHAMBER = 180f;

	private int framesTaken;
	private int podId;
	private ClientGameTestContext ctx;
	private TestSingleplayerContext world;
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
			world = singleplayer;
			chamber = new RetainedChamber(context, singleplayer);
			CameraType cameraType = context.computeOnClient(client -> client.options.getCameraType());
			EvidencePins.lightFlickerOff = true;
			try {
				setUp();
				approachAndDrain();
				driveAway();
				secondFigureAndLights();
			} finally {
				EvidencePins.reset();
				context.runOnClient(client -> client.options.setCameraType(cameraType));
			}
		}
	}

	private void setUp() {
		chamber.enterLayer(RetainedChamber.FLOOR.getX() + POD_X, RetainedChamber.FLOOR.getY() + 3, RetainedChamber.FLOOR.getZ() + POD_Z, ACROSS_THE_CHAMBER, 12f);
		chamber.serverDo(server -> {
			ServerLevel level = RetainedChamber.layer(server);
			RetainedChamber.buildChamber(level, LENGTH, HALF_WIDTH, HEIGHT);
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			Charters.found(server, player.getUUID(), "Demo Charter");
			PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
			pod.setPos(RetainedChamber.at(POD_X, 1, POD_Z));
			pod.setYRot(ACROSS_THE_CHAMBER);
			level.addFreshEntity(pod);
			podId = pod.getId();
			player.teleportTo(level, pod.getX(), pod.getY(), pod.getZ(), Set.of(), ACROSS_THE_CHAMBER, 12f, true);
			if (!player.startRiding(pod)) {
				throw new AssertionError("the player could not mount the pod");
			}
		});
		chamber.nightVision(true);
		ClientWait.until(ctx, "the pilot seated", client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
		ctx.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
		chamber.settle();
		ctx.waitTicks(20); // tick-wait: the transmission and the camera settle
	}

	private float fuel() {
		return chamber.serverGet(server -> ((PodEntity) RetainedChamber.layer(server).getEntity(podId)).fuel());
	}

	private boolean clinging() {
		return chamber.serverGet(server -> ((LamplessFigure) RetainedChamber.layer(server).getEntity(figureId)).isClinging());
	}

	private boolean fading() {
		return chamber.serverGet(server -> RetainedChamber.layer(server).getEntity(figureId) instanceof LamplessFigure figure && figure.fadeFraction() > 0);
	}

	private boolean gone() {
		return chamber.serverGet(server -> RetainedChamber.layer(server).getEntity(figureId) == null);
	}

	private void sendFigure() {
		chamber.serverDo(server -> {
			ServerLevel level = RetainedChamber.layer(server);
			LamplessFigure figure = CreatureRegistry.LAMPLESS_FIGURE.create(level, EntitySpawnReason.COMMAND);
			figure.setPos(RetainedChamber.at(FIGURE_X, 1, POD_Z));
			figure.setHeading(Direction.WEST);
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
				throw new AssertionError("the figure had not grabbed the dark pod " + APPROACH_FRAMES_LIMIT + " frames after it set off");
			}
			if (!shotApproach && chamber.serverGet(server -> RetainedChamber.layer(server).getEntity(figureId).distanceTo(RetainedChamber.layer(server).getEntity(podId)) < APPROACH_STILL_BLOCKS)) {
				screenshot(ctx, "retained-approach");
				shotApproach = true;
			}
		}
		float before = fuel();
		record(8);
		screenshot(ctx, "retained-clinging");
		record(DRAIN_FRAMES - 8);
		screenshot(ctx, "retained-drained");
		System.out.println("[retained-drain] fuel " + before + " at the grab, " + fuel() + " after " + DRAIN_FRAMES + " frames of the grip");
		System.out.println("[retained-drain-segment] retained-grab " + first + "-" + framesTaken);
	}

	/** The pilot drives north across the chamber: the figure is shaken off, and fades. */
	private void driveAway() {
		int first = framesTaken + 1;
		ctx.getInput().holdKey(options -> options.keyUp);
		int driven = 0;
		while (clinging()) {
			record(1);
			if (++driven > FREED_FRAMES_LIMIT) {
				throw new AssertionError("the figure still clung " + FREED_FRAMES_LIMIT + " frames after the pilot drove off");
			}
		}
		screenshot(ctx, "retained-shaken-off");
		ctx.getInput().releaseKey(options -> options.keyUp);
		int fading = 0;
		while (!gone()) {
			record(1);
			if (++fading > FREED_FRAMES_LIMIT) {
				throw new AssertionError("the figure had not faded " + FREED_FRAMES_LIMIT + " frames after it let go");
			}
		}
		record(FADE_TAIL_FRAMES);
		screenshot(ctx, "retained-gone");
		System.out.println("[retained-drain-segment] retained-shake " + first + "-" + framesTaken);
	}

	/** A second figure clings; the pod's lights part is fitted and the figure lets go and fades. */
	private void secondFigureAndLights() {
		sendFigure();
		int first = framesTaken + 1;
		int walked = 0;
		while (!clinging()) {
			record(1);
			if (++walked > APPROACH_FRAMES_LIMIT) {
				throw new AssertionError("the second figure had not grabbed the pod " + APPROACH_FRAMES_LIMIT + " frames after it set off");
			}
		}
		record(10);
		chamber.serverDo(server -> {
			PodEntity pod = (PodEntity) RetainedChamber.layer(server).getEntity(podId);
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			CharterId charter = Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow().id();
			PodComponents.register(pod, charter);
			PodComponents.install(pod, ComponentItems.mint(server, ComponentTrack.LIGHTS, 2, charter));
		});
		chamber.nightVision(false);
		int lit = 0;
		while (!fading()) {
			record(1);
			if (++lit > FREED_FRAMES_LIMIT) {
				throw new AssertionError("the figure had not let go " + FREED_FRAMES_LIMIT + " frames after the pod was lit");
			}
		}
		record(8);
		screenshot(ctx, "retained-lit-lets-go");
		int fading = 0;
		while (!gone()) {
			record(1);
			if (++fading > FREED_FRAMES_LIMIT) {
				throw new AssertionError("the second figure had not faded " + FREED_FRAMES_LIMIT + " frames after the pod was lit");
			}
		}
		record(FADE_TAIL_FRAMES);
		screenshot(ctx, "retained-lit-gone");
		System.out.println("[retained-drain-segment] retained-light " + first + "-" + framesTaken);
	}
}
