package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.UUID;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;

/**
 * Evidence scenario "tow-line-shaft" (#425): a Mole tows a pod along a tunnel and up a shaft, both 2 blocks wide, cut open on the west
 * side behind an invisible wall. The Mole drives to the foot of the shaft and climbs it and out of its top. The line is longer than the tunnel's
 * last stretch, so the pod is carried round the corner along the path the Mole took and never through the rock.
 */
public class TowLineShaftScenario extends EvidenceScenario {
	private static final int X = 1900;
	private static final int Z = 1900;
	private static final int FLOOR_Y = 200;
	/** The rock's top. The shaft is open to the sky above it. */
	private static final int ROCK_HEIGHT = 16;
	private static final int TUNNEL_LENGTH = 10;
	private static final int STANDING_FRAMES = 8;
	private static final int MAX_DRIVE_TICKS = 300;
	private static final int ABOVE_THE_ROCK = 8;
	private static final int MAX_CLIMB_TICKS = 600;
	private static final int HOVER_FRAMES = 10;
	/** The camera: this far west of the shaft, looking east at the cut, level with the tower less this much and never lower than the tunnel's. */
	private static final double CAMERA_WEST = 11;
	private static final double AIM_BELOW = 2.5;
	private static final double LOWEST_AIM = FLOOR_Y + 4.5;

	@Override
	protected String name() {
		return "tow-line-shaft";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
			UUID[][] pods = {null};
			two.server().runOnServer(server -> {
				ServerLevel level = server.overworld();
				int top = FLOOR_Y + ROCK_HEIGHT;
				TowStage.fill(level, X - 14, X + 14, FLOOR_Y - 4, FLOOR_Y - 1, Z - 14, Z + 24, Blocks.STONE);
				TowStage.fill(level, X - 14, X + 14, FLOOR_Y, FLOOR_Y + 40, Z - 14, Z + 24, Blocks.AIR);
				TowStage.fill(level, X, X + 4, FLOOR_Y, top, Z - 1, Z + TUNNEL_LENGTH, Blocks.STONE);
				// The west wall of the cut is barrier, solid and invisible, so the camera, west of it and looking east away from the sun, sees into the tunnel and the shaft.
				TowStage.fill(level, X - 1, X - 1, FLOOR_Y, top, Z - 1, Z + TUNNEL_LENGTH, Blocks.BARRIER);
				TowStage.fill(level, X, X + 1, FLOOR_Y, FLOOR_Y + 2, Z, Z + TUNNEL_LENGTH - 1, Blocks.AIR);
				TowStage.fill(level, X, X + 1, FLOOR_Y, top + 1, Z + TUNNEL_LENGTH - 2, Z + TUNNEL_LENGTH - 1, Blocks.AIR);
				TowStage.prepare(level, two, new Vec3(X - 15, FLOOR_Y + 3, Z));
				Vec3 at = new Vec3(X + 1.0, FLOOR_Y, Z + 3.5);
				pods[0] = TowStage.pair(level, two, at, new Vec3(X + 1.0, FLOOR_Y, Z + 1.2), false);
			});
			UUID tower = pods[0][0];
			context.waitTicks(TowStage.CHAT_FADE_TICKS);
			for (int i = 0; i < STANDING_FRAMES; i++) {
				look(context, two, tower);
				context.waitTicks(TowStage.TICKS_PER_FRAME);
			}
			screenshot(context, "pods-in-the-tunnel");

			// South along the tunnel until the Mole is at the foot of the shaft, against the tunnel's end.
			two.server().runOnServer(server -> two.mock().setInput(TowStage.FORWARD));
			for (int ticks = 0; TowStage.where(two, tower).z < Z + TUNNEL_LENGTH - 1.1 && ticks < MAX_DRIVE_TICKS; ticks += TowStage.TICKS_PER_FRAME) {
				context.waitTicks(TowStage.TICKS_PER_FRAME);
				look(context, two, tower);
			}
			screenshot(context, "line-taut-at-the-foot-of-the-shaft");
			// Up the shaft and out of its top.
			two.server().runOnServer(server -> two.mock().setInput(TowStage.JUMP));
			boolean shot = false;
			for (int ticks = 0; TowStage.where(two, tower).y < FLOOR_Y + ROCK_HEIGHT + ABOVE_THE_ROCK && ticks < MAX_CLIMB_TICKS; ticks += TowStage.TICKS_PER_FRAME) {
				context.waitTicks(TowStage.TICKS_PER_FRAME);
				look(context, two, tower);
				if (!shot && TowStage.where(two, tower).y > FLOOR_Y + ROCK_HEIGHT / 2.0) {
					screenshot(context, "pod-following-up-the-shaft");
					shot = true;
				}
			}
			if (TowStage.where(two, tower).y < FLOOR_Y + ROCK_HEIGHT + ABOVE_THE_ROCK) {
				throw new AssertionError("The tower did not climb out of the shaft within " + MAX_CLIMB_TICKS + " ticks");
			}
			two.server().runOnServer(server -> two.mock().releaseInput());
			for (int i = 0; i < HOVER_FRAMES; i++) {
				context.waitTicks(TowStage.TICKS_PER_FRAME);
				look(context, two, tower);
			}
			screenshot(context, "pod-hanging-in-the-shaft-below-the-mole");
		}
	}

	private void look(ClientGameTestContext context, TwoPlayerServer two, UUID tower) {
		two.server().runOnServer(server -> {
			double aim = Math.max(LOWEST_AIM, server.overworld().getEntity(tower).getY() - AIM_BELOW);
			Vec3 focus = new Vec3(X + 1, aim, Z + 5);
			TowStage.film(server.overworld(), two, focus.add(-CAMERA_WEST, 0, 0), focus);
		});
		context.runOnClient(client -> client.gui.toastManager().clear());
		frame(context);
	}
}
