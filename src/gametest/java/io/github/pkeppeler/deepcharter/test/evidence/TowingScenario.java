package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;

/**
 * Evidence scenario "m2-towing": the real client watches from the side while a mock pilot drives a pod with another pod on a
 * cable behind it across a stone slab, then drills straight down. The slab's east face is open beside the bore, so the towed
 * pod can be seen following the tower into the shaft.
 */
public class TowingScenario extends EvidenceScenario {
	private static final int X = 900;
	private static final int Z = 900;
	private static final int FLOOR_Y = 200;
	/** The slab the pods drive and drill in, west of the platform the watcher stands on, with a gap between. */
	private static final int SLAB_WEST = 6;
	private static final int PLATFORM_FROM = 4;
	private static final int PLATFORM_TO = 10;
	private static final int DRIVE_TICKS = 40;
	private static final int TICKS_PER_FRAME = 4;
	private static final int DRILL_TICKS_PER_FRAME = 16;
	private static final int MAX_DRILL_TICKS = 900;
	private static final double DRILL_DEPTH = 5;
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
			context.waitTicks(40);
			frame(context);

			double startY = towerY(two, tower[0]);
			two.server().runOnServer(server -> two.mock().setInput(FORWARD));
			for (int ticks = 0; ticks < DRIVE_TICKS; ticks += TICKS_PER_FRAME) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
			two.server().runOnServer(server -> two.mock().releaseInput());
			context.waitTicks(10);
			frame(context);
			screenshot(context, "towing-on-the-flat");

			two.server().runOnServer(server -> {
				watch(server.overworld(), two, SHAFT_PITCH);
				two.mock().setInput(SPRINT);
			});
			int ticks = 0;
			while (startY - towerY(two, tower[0]) < DRILL_DEPTH && ticks < MAX_DRILL_TICKS) {
				context.waitTicks(DRILL_TICKS_PER_FRAME);
				ticks += DRILL_TICKS_PER_FRAME;
				frame(context);
			}
			two.server().runOnServer(server -> two.mock().releaseInput());
			if (startY - towerY(two, tower[0]) < DRILL_DEPTH) {
				throw new AssertionError("The tower did not drill " + DRILL_DEPTH + " blocks down within " + MAX_DRILL_TICKS + " ticks");
			}
			for (int i = 0; i < 6; i++) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
			screenshot(context, "towed-pod-in-the-shaft");
		}
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
		watch(level, two, WATCH_PITCH);
		Vec3 at = new Vec3(X - 0.5, FLOOR_Y, Z - 4.5);
		two.mock().teleportTo(level, at, 0, 0);
		PodEntity tower = spawn(level, at);
		PodEntity towed = spawn(level, at.add(-2, 0, -1));
		if (!two.mock().player().startRiding(tower)) {
			throw new AssertionError("the mock pilot could not mount the tower");
		}
		tower.setFuel(100f);
		PodTowing.attach(tower, towed);
		return tower.getUUID();
	}

	/** Puts the real player on the platform's edge, facing the slab and looking {@code pitch} degrees down. */
	private static void watch(ServerLevel level, TwoPlayerServer two, float pitch) {
		ServerPlayer real = level.getServer().getPlayerList().getPlayers().stream()
				.filter(player -> player != two.mock().player()).findFirst().orElseThrow();
		real.teleportTo(level, X + PLATFORM_FROM + 0.5, FLOOR_Y, Z + 3.5, Set.of(), WATCH_WEST, pitch, true);
	}

	private static PodEntity spawn(ServerLevel level, Vec3 at) {
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(at);
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
