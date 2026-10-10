package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;

/**
 * Evidence scenario "m2-towing": the real client watches from the side while a mock pilot drives a pod with another pod on a
 * cable behind it across a stone slab, then drills straight down. The pods are named TOWER and TOWED, the cable is drawn between
 * them, and the cable message stays on the bar. The slab's east face is open beside the bore, so the towed pod can be seen
 * following the tower into the shaft.
 */
public class TowingScenario extends EvidenceScenario {
	private static final int X = 900;
	private static final int Z = 900;
	private static final int FLOOR_Y = 200;
	/** The slab the pods drive and drill in, west of the platform the watcher stands on, with a gap between. */
	private static final int SLAB_WEST = 6;
	private static final int PLATFORM_FROM = 4;
	private static final int PLATFORM_TO = 10;
	/** Chat lines (the join messages) stay on screen for 10 seconds. */
	private static final int CHAT_FADE_TICKS = 220;
	private static final int DRIVE_TICKS = 40;
	private static final int TICKS_PER_FRAME = 4;
	private static final int DRILL_TICKS_PER_FRAME = 16;
	private static final int MAX_DRILL_TICKS = 900;
	private static final double DRILL_DEPTH = 5;
	/** The watcher starts level with the tower and then follows it along the slab, so both pods stay in the frame while they drive. */
	private static final double WATCH_START_Z = Z - 4.5;
	private static final double WATCH_SHAFT_Z = Z + 3.5;
	private static final float WATCH_WEST = 90f;
	private static final float WATCH_PITCH = 15f;
	private static final float SHAFT_PITCH = 48f;
	private static final Input FORWARD = new Input(true, false, false, false, false, false, false);
	private static final Input SPRINT = new Input(false, false, false, false, false, false, true);

	@Override
	protected String name() {
		return "m2-towing";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
			UUID[] tower = {null};
			two.server().runOnServer(server -> tower[0] = setUp(server.overworld(), two));
			context.waitTicks(CHAT_FADE_TICKS);
			look(context, two);

			double startY = towerY(two, tower[0]);
			two.server().runOnServer(server -> two.mock().setInput(FORWARD));
			for (int ticks = 0; ticks < DRIVE_TICKS; ticks += TICKS_PER_FRAME) {
				context.waitTicks(TICKS_PER_FRAME);
				two.server().runOnServer(server -> follow(server.overworld(), two, tower[0]));
				look(context, two);
			}
			two.server().runOnServer(server -> two.mock().releaseInput());
			context.waitTicks(10);
			two.server().runOnServer(server -> follow(server.overworld(), two, tower[0]));
			look(context, two);
			still(context, two, "towing-on-the-flat");

			two.server().runOnServer(server -> {
				watch(server.overworld(), two, WATCH_SHAFT_Z, SHAFT_PITCH);
				two.mock().setInput(SPRINT);
			});
			int ticks = 0;
			while (startY - towerY(two, tower[0]) < DRILL_DEPTH && ticks < MAX_DRILL_TICKS) {
				context.waitTicks(DRILL_TICKS_PER_FRAME);
				ticks += DRILL_TICKS_PER_FRAME;
				look(context, two);
			}
			two.server().runOnServer(server -> two.mock().releaseInput());
			if (startY - towerY(two, tower[0]) < DRILL_DEPTH) {
				throw new AssertionError("The tower did not drill " + DRILL_DEPTH + " blocks down within " + MAX_DRILL_TICKS + " ticks");
			}
			for (int i = 0; i < 6; i++) {
				context.waitTicks(TICKS_PER_FRAME);
				look(context, two);
			}
			still(context, two, "towed-pod-in-the-shaft");
		}
	}

	/** One frame with the cable message on the bar and no toast or chat line over the pods. */
	private void look(ClientGameTestContext context, TwoPlayerServer two) {
		clean(context, two);
		frame(context);
	}

	private void still(ClientGameTestContext context, TwoPlayerServer two, String name) {
		clean(context, two);
		screenshot(context, name);
	}

	/** The dedicated server's join toasts would cover the screen; its chat lines are waited out (see {@link #CHAT_FADE_TICKS}). */
	private static void clean(ClientGameTestContext context, TwoPlayerServer two) {
		two.server().runOnServer(server -> watcher(server.overworld(), two).sendOverlayMessage(Component.translatable("message.deepcharter.towing.attached")));
		context.runOnClient(client -> client.gui.toastManager().clear());
	}

	private static ServerPlayer watcher(ServerLevel level, TwoPlayerServer two) {
		return level.getServer().getPlayerList().getPlayers().stream().filter(player -> player != two.mock().player()).findFirst().orElseThrow();
	}

	/** The tower's height. Looked up by UUID: a pod whose chunk is unloaded and loaded again is a new entity. */
	private static double towerY(TwoPlayerServer two, UUID tower) {
		return two.server().computeOnServer(server -> server.overworld().getEntity(tower).getY());
	}

	/** Builds the slab and the platform, seats the mock in a tower with a pod on a cable, and puts the real player on the platform. */
	private static UUID setUp(ServerLevel level, TwoPlayerServer two) {
		fill(level, X - SLAB_WEST, X, FLOOR_Y - 8, FLOOR_Y - 1, Blocks.STONE);
		fill(level, X - SLAB_WEST, X, FLOOR_Y, FLOOR_Y + 10, Blocks.AIR);
		fill(level, X + PLATFORM_FROM, X + PLATFORM_TO, FLOOR_Y - 8, FLOOR_Y - 1, Blocks.STONE);
		fill(level, X + PLATFORM_FROM, X + PLATFORM_TO, FLOOR_Y, FLOOR_Y + 10, Blocks.AIR);
		watch(level, two, WATCH_START_Z, WATCH_PITCH);
		hideNameTags(level, two);
		Vec3 at = new Vec3(X - 0.5, FLOOR_Y, Z - 4.5);
		two.mock().teleportTo(level, at, 0, 0);
		PodEntity tower = spawn(level, at, "TOWER");
		PodEntity towed = spawn(level, at.add(-2, 0, -1), "TOWED");
		if (!two.mock().player().startRiding(tower)) {
			throw new AssertionError("the mock pilot could not mount the tower");
		}
		tower.setFuel(100f);
		PodTowing.attach(tower, towed);
		return tower.getUUID();
	}

	/** Puts the real player on the platform's edge at {@code z}, facing the slab and looking {@code pitch} degrees down. */
	private static void watch(ServerLevel level, TwoPlayerServer two, double z, float pitch) {
		watcher(level, two).teleportTo(level, X + PLATFORM_FROM + 0.5, FLOOR_Y, z, Set.of(), WATCH_WEST, pitch, true);
	}

	/** Slides the watcher along the platform edge to the tower's z, so the pods stay in the frame. */
	private static void follow(ServerLevel level, TwoPlayerServer two, UUID tower) {
		watch(level, two, level.getEntity(tower).getZ(), WATCH_PITCH);
	}

	/** The mock pilot's name would hover over the tower; the pods' own TOWER and TOWED labels are the ones the picture is meant to show. */
	private static void hideNameTags(ServerLevel level, TwoPlayerServer two) {
		PlayerTeam team = level.getScoreboard().addPlayerTeam("no-name-tags");
		team.setNameTagVisibility(Team.Visibility.NEVER);
		level.getScoreboard().addPlayerToTeam(two.mock().player().getScoreboardName(), team);
	}

	private static PodEntity spawn(ServerLevel level, Vec3 at, String name) {
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(at);
		pod.setCustomName(Component.literal(name));
		pod.setCustomNameVisible(true);
		level.addFreshEntity(pod);
		return pod;
	}

	private static void fill(ServerLevel level, int xFrom, int xTo, int yFrom, int yTo, Block block) {
		for (int x = xFrom; x <= xTo; x++) {
			for (int y = yFrom; y <= yTo; y++) {
				for (int z = Z - 6; z <= Z + 12; z++) {
					level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
				}
			}
		}
	}
}
