package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonySite;

/**
 * Evidence scenario "m2-colony" for #64: a fly-over of the colony the world built at its spawn. One lap above the pad, then a
 * low pass over the square: the terminal plinths, the statue, the Continuity Office and the Conduit. Stills show each.
 * The path is set from the anchors, so it follows the colony wherever the world's spawn is.
 */
public class ColonyScenario extends EvidenceScenario {
	/** Frames of the high lap, one per step round the pad. */
	private static final int LAP_FRAMES = 26;
	private static final double LAP_RADIUS = 62;
	private static final double LAP_HEIGHT = 34;
	/** Frames of the low pass over the square, and its height. */
	private static final int PASS_FRAMES = 16;
	private static final double PASS_HEIGHT = 7;
	private static final int TICKS_PER_FRAME = 2;
	private static final int SETTLE_TICKS = 60;

	@Override
	protected String name() {
		return "m2-colony";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitFor(client -> client.player != null && client.level != null);
			ColonySite.Placed colony = singleplayer.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.setPermanentlyInvulnerable(true);
				// The Handbook in the hand would cover the view.
				player.getInventory().clearContent();
				player.getAbilities().mayfly = true;
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
				return Colony.placed(server).orElseThrow(() -> new AssertionError("the colony was not built when the world started"));
			});
			Vec3 centre = Vec3.atCenterOf(colony.center());
			Vec3 office = Vec3.atCenterOf(colony.anchors().get(ColonyAnchor.CONTINUITY_OFFICE));

			// Where a new player stands: in the Continuity Office, at the world spawn.
			fly(singleplayer, office.add(-10, 2.5, 0), office.add(0, 1.5, 0));
			context.waitTicks(SETTLE_TICKS);
			screenshot(context, "world-spawn-continuity-office");

			// One high lap, looking in at the pad.
			for (int i = 0; i < LAP_FRAMES; i++) {
				double angle = 2 * Math.PI * i / LAP_FRAMES + Math.PI / 2;
				Vec3 at = centre.add(Math.cos(angle) * LAP_RADIUS, LAP_HEIGHT, Math.sin(angle) * LAP_RADIUS);
				fly(singleplayer, at, centre);
				context.waitTicks(i == 0 ? SETTLE_TICKS : TICKS_PER_FRAME);
				if (i == 0) {
					screenshot(context, "colony-from-the-air");
				}
				frame(context);
			}

			// A low pass from the west end of the square to the east, past the plinths, over the statue, to the office.
			Vec3 plinths = Vec3.atCenterOf(colony.anchors().get(ColonyAnchor.UPGRADE_TERMINAL));
			Vec3 statue = Vec3.atCenterOf(colony.anchors().get(ColonyAnchor.STATUE)).add(0, 3, 0);
			Vec3 start = centre.add(-14, PASS_HEIGHT, 6);
			Vec3 end = centre.add(22, PASS_HEIGHT, 6);
			for (int i = 0; i < PASS_FRAMES; i++) {
				Vec3 at = start.lerp(end, (double) i / (PASS_FRAMES - 1));
				fly(singleplayer, at, i < PASS_FRAMES / 2 ? plinths : statue);
				context.waitTicks(i == 0 ? SETTLE_TICKS : TICKS_PER_FRAME);
				if (i == PASS_FRAMES / 3) {
					screenshot(context, "terminal-plinths");
				}
				if (i == 2 * PASS_FRAMES / 3) {
					screenshot(context, "statue");
				}
				frame(context);
			}

			// The Conduit, behind the ore processor.
			Vec3 conduit = Vec3.atCenterOf(colony.anchors().get(ColonyAnchor.CONDUIT)).add(0, 4, 0);
			fly(singleplayer, conduit.add(0, 3, 12), conduit);
			context.waitTicks(SETTLE_TICKS);
			screenshot(context, "conduit");
		}
	}

	/** Puts the player at {@code at} in the overworld, looking at {@code target}. */
	private static void fly(TestSingleplayerContext singleplayer, Vec3 at, Vec3 target) {
		Vec3 d = target.subtract(at);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
		singleplayer.getServer().runOnServer(server ->
				server.getPlayerList().getPlayers().getFirst().teleportTo(server.overworld(), at.x, at.y, at.z, Set.of(), yaw, pitch, true));
	}
}
