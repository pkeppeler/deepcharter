package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.FounderStatue;

/**
 * Evidence scenario "statue-hands" for #458: a close orbit of each of the Host's hands, so the thumbs show. The hands sit where
 * tools/colony/sculptures.py {@code host()} puts them, measured from his feet (the bottom of the statue's anchor block).
 */
public class StatueHandsScenario extends EvidenceScenario {
	/** The raised right hand (the Host's right, at -X) and the lowered left hand, in blocks east, up and south of his feet. */
	private static final Vec3 RIGHT_HAND = new Vec3(-1.7, 7.0, 2.0);
	private static final Vec3 LEFT_HAND = new Vec3(2.3, 2.6, 1.3);
	private static final double RADIUS = 4.0;
	/** The orbit starts in front of the Host (south) and swings this far to the east, in degrees. */
	private static final double ARC = 70;
	private static final int FRAMES_PER_HAND = 20;
	private static final int TICKS_PER_FRAME = 2;
	private static final int SETTLE_TICKS = 60;

	@Override
	protected String name() {
		return "statue-hands";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitFor(client -> client.player != null && client.level != null);
			Vec3 feet = singleplayer.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.setGameMode(GameType.SPECTATOR);
				// The colony is built without his hands; a work order puts them back, and so does this.
				FounderStatue.restoreHands(server);
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
				return Vec3.atBottomCenterOf(Colony.placed(server).orElseThrow().anchors().get(ColonyAnchor.STATUE));
			});
			orbit(context, singleplayer, feet.add(RIGHT_HAND), "statue-right-hand");
			orbit(context, singleplayer, feet.add(LEFT_HAND), "statue-left-hand");
		}
	}

	/** Frames of a swing round {@code hand} from the front, and a still of the first view. */
	private void orbit(ClientGameTestContext context, TestSingleplayerContext singleplayer, Vec3 hand, String still) {
		for (int i = 0; i < FRAMES_PER_HAND; i++) {
			double angle = Math.toRadians(ARC * i / (FRAMES_PER_HAND - 1));
			look(singleplayer, hand.add(Math.sin(angle) * RADIUS, 0.2, Math.cos(angle) * RADIUS), hand);
			context.waitTicks(i == 0 ? SETTLE_TICKS : TICKS_PER_FRAME);
			if (i == 0) {
				screenshot(context, still);
			}
			frame(context);
		}
	}

	/** Puts the player at {@code at}, looking at {@code target}. */
	private static void look(TestSingleplayerContext singleplayer, Vec3 at, Vec3 target) {
		Vec3 d = target.subtract(at);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
		singleplayer.getServer().runOnServer(server ->
				server.getPlayerList().getPlayers().getFirst().teleportTo(server.overworld(), at.x, at.y, at.z, Set.of(), yaw, pitch, true));
	}
}
