package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;

/**
 * Evidence scenario "retained-lamp" (#449): the three ways a Retained carries its lamp, each seen in the dark of layer 2 (no night vision, no
 * pod's light: the lamp is the only light) from near and from far, and once from near with night vision to read the shape. The default,
 * the lamp on a pole, is the mod's own look; the lantern in the hand and the lamp in the ribcage are test packs. A lampless figure from far is the control.
 *
 * <p>Stills: {@code lamp-<option>-far}, {@code lamp-<option>-near}, {@code lamp-<option>-near-seen}, and {@code lampless-far}.
 */
public class RetainedLampScenario extends EvidenceScenario {
	private static final int LENGTH = 40;
	private static final int HALF_WIDTH = 4;
	private static final int HEIGHT = 5;
	/** Where the camera stands, at the west end, and how far the figure stands from it in the far and near stills. */
	private static final double CAMERA_X = 0.8;
	private static final double FAR = 20;
	private static final double FARTHEST = 28;
	private static final int WALK_FRAMES_LIMIT = 200;
	private static final int TICKS_PER_FRAME = 3;
	private int framesTaken;
	private static final double NEAR = 4.5;
	/** Yaw 90 faces -x, toward the camera from a figure east of it. */
	private static final float TOWARD_CAMERA = 90f;

	private ClientGameTestContext ctx;
	private TestSingleplayerContext world;
	private RetainedChamber chamber;
	private int figureId;

	@Override
	protected String name() {
		return "retained-lamp";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		ClientChecks.withHudHidden(context, () -> runHidden(context));
	}

	private void runHidden(ClientGameTestContext context) {
		ctx = context;
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			world = singleplayer;
			chamber = new RetainedChamber(context, singleplayer);
			CameraType cameraType = context.computeOnClient(client -> client.options.getCameraType());
			EvidencePins.lightFlickerOff = true;
			EvidencePins.figureAnimationAge = 0f;
			try {
				setUp();
				lampless();
				option("pole");
				walk();
				ClientPacks.enable(context, TestPacks.RETAINED_LAMP_HAND);
				try {
					option("hand");
				} finally {
					ClientPacks.disable(context, TestPacks.RETAINED_LAMP_HAND);
				}
				ClientPacks.enable(context, TestPacks.RETAINED_LAMP_RIBS);
				try {
					option("ribs");
				} finally {
					ClientPacks.disable(context, TestPacks.RETAINED_LAMP_RIBS);
				}
			} finally {
				EvidencePins.reset();
				context.runOnClient(client -> client.options.setCameraType(cameraType));
			}
		}
	}

	private void setUp() {
		chamber.enterLayer(RetainedChamber.FLOOR.getX() + CAMERA_X, RetainedChamber.FLOOR.getY() + 3, RetainedChamber.FLOOR.getZ() + 0.5, 270f, 0f);
		chamber.serverDo(server -> {
			ServerLevel level = RetainedChamber.layer(server);
			RetainedChamber.buildChamber(level, LENGTH, HALF_WIDTH, HEIGHT);
			ServerPlayer camera = server.getPlayerList().getPlayers().getFirst();
			camera.setGameMode(GameType.CREATIVE);
			camera.getAbilities().mayfly = true;
			camera.getAbilities().flying = true;
			camera.onUpdateAbilities();
			camera.setNoGravity(true);
			camera.setPermanentlyInvulnerable(true);
			LamplessFigure figure = CreatureRegistry.LAMPLESS_FIGURE.create(level, EntitySpawnReason.COMMAND);
			figure.setNoAi(true);
			figure.setPos(RetainedChamber.at(CAMERA_X + FAR, 1, 0.5));
			level.addFreshEntity(figure);
			figureId = figure.getId();
		});
		ClientWait.until(ctx, "the figure on the client", client -> client.level != null && client.level.getEntity(figureId) instanceof LamplessFigure);
		ctx.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
	}

	/** Puts the figure {@code distance} east of the camera, facing it, with or without a lamp, and the camera looking at it. */
	private void stand(double distance, boolean lamp) {
		chamber.serverDo(server -> {
			LamplessFigure figure = (LamplessFigure) RetainedChamber.layer(server).getEntity(figureId);
			figure.setLamp(lamp);
			figure.setPos(RetainedChamber.at(CAMERA_X + distance, 1, 0.5));
			figure.setYRot(TOWARD_CAMERA);
			figure.setYBodyRot(TOWARD_CAMERA);
			figure.setYHeadRot(TOWARD_CAMERA);
		});
		ClientWait.until(ctx, "the figure's lamp on the client", client -> client.level.getEntity(figureId) instanceof LamplessFigure f && f.hasLamp() == lamp);
		chamber.look(RetainedChamber.at(CAMERA_X, 1.9, 0.5), RetainedChamber.at(CAMERA_X + distance, 1.2, 0.5));
		ctx.waitTicks(10); // tick-wait: the move plays the walk on the client until the figure has been still a moment
		chamber.settle();
	}

	private void lampless() {
		chamber.nightVision(false);
		stand(FAR, false);
		screenshot(ctx, "lampless-far");
	}

	/**
	 * The lamp-carrier of the default look walks toward the camera from the far end of the chamber, in the dark, with no night vision: a lamp bobbing
	 * in the black. It fades when it comes within 8 blocks of the camera, as it does of anyone on foot. The clip is logged as
	 * {@code [retained-lamp-segment] retained-lamp-walk <first>-<last>}.
	 */
	private void walk() {
		chamber.nightVision(false);
		stand(FARTHEST, true);
		chamber.serverDo(server -> {
			LamplessFigure figure = (LamplessFigure) RetainedChamber.layer(server).getEntity(figureId);
			figure.setHeading(Direction.WEST);
			figure.setNoAi(false);
		});
		int first = framesTaken + 1;
		for (int i = 0; i < WALK_FRAMES_LIMIT; i++) {
			ctx.waitTicks(TICKS_PER_FRAME); // tick-wait: the clip is cut at fixed frames
			frame(ctx);
			framesTaken++;
			boolean gone = chamber.serverGet(server -> RetainedChamber.layer(server).getEntity(figureId) == null);
			if (gone) {
				System.out.println("[retained-lamp-segment] retained-lamp-walk " + first + "-" + framesTaken);
				resetFigure();
				return;
			}
		}
		throw new AssertionError("the figure had not faded " + WALK_FRAMES_LIMIT + " frames after it set off");
	}

	/** The walker faded out; a new figure stands in for the stills that follow. */
	private void resetFigure() {
		chamber.serverDo(server -> {
			ServerLevel level = RetainedChamber.layer(server);
			LamplessFigure figure = CreatureRegistry.LAMPLESS_FIGURE.create(level, EntitySpawnReason.COMMAND);
			figure.setNoAi(true);
			figure.setPos(RetainedChamber.at(CAMERA_X + FAR, 1, 0.5));
			level.addFreshEntity(figure);
			figureId = figure.getId();
		});
		ClientWait.until(ctx, "the new figure on the client", client -> client.level != null && client.level.getEntity(figureId) instanceof LamplessFigure);
	}

	private void option(String option) {
		chamber.nightVision(false);
		stand(FAR, true);
		screenshot(ctx, "lamp-" + option + "-far");
		stand(NEAR, true);
		screenshot(ctx, "lamp-" + option + "-near");
		chamber.nightVision(true);
		ctx.waitTicks(10); // tick-wait: the effect reaches the client
		Vec3 eye = RetainedChamber.at(CAMERA_X, 1.9, 0.5);
		chamber.look(eye, RetainedChamber.at(CAMERA_X + NEAR, 1.3, 0.5));
		chamber.settle();
		screenshot(ctx, "lamp-" + option + "-near-seen");
	}
}
