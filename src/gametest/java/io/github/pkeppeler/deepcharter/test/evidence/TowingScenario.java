package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTowing;

/**
 * Evidence scenario "m2-towing": the real player drives a pod with another pod on a cable behind it, then drills straight down
 * through the stone while the towed pod follows into the shaft.
 */
public class TowingScenario extends EvidenceScenario {
	private static final int X = 900;
	private static final int Z = 900;
	private static final int FLOOR_Y = 200;
	private static final int DRIVE_FRAMES = 14;
	private static final int TICKS_PER_FRAME = 5;
	private static final int DRILL_TICKS_PER_FRAME = 16;
	private static final int MAX_DRILL_TICKS = 900;
	private static final double DRILL_DEPTH = 5;
	private static final float LOOK_DOWN = 55f;

	@Override
	protected String name() {
		return "m2-towing";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			PodEntity[] pods = {null, null};
			singleplayer.getServer().runOnServer(server -> {
				ServerLevel level = server.overworld();
				fill(level, FLOOR_Y - 8, FLOOR_Y - 1, Blocks.STONE);
				fill(level, FLOOR_Y, FLOOR_Y + 10, Blocks.AIR);
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				Vec3 at = new Vec3(X + 0.5, FLOOR_Y, Z + 0.5);
				player.teleportTo(level, at.x, at.y, at.z, Set.of(), 0, 0, true);
				pods[0] = spawn(level, at);
				pods[1] = spawn(level, at.add(-2, 0, -1));
				if (!player.startRiding(pods[0])) {
					throw new AssertionError("the player could not mount the tower");
				}
				pods[0].setFuel(100f);
				PodTowing.attach(pods[0], pods[1]);
			});
			context.waitFor(client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
			context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
			context.waitTicks(20);
			frame(context);

			context.getInput().holdKey(options -> options.keyUp);
			for (int i = 0; i < DRIVE_FRAMES; i++) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
			context.getInput().releaseKey(options -> options.keyUp);
			screenshot(context, "towing-on-the-flat");

			context.runOnClient(client -> {
				client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
				client.player.setXRot(LOOK_DOWN);
			});
			double startY = pods[0].getY();
			context.getInput().holdKey(options -> options.keySprint);
			int ticks = 0;
			while (singleplayer.getServer().computeOnServer(server -> startY - pods[0].getY()) < DRILL_DEPTH && ticks < MAX_DRILL_TICKS) {
				context.waitTicks(DRILL_TICKS_PER_FRAME);
				ticks += DRILL_TICKS_PER_FRAME;
				frame(context);
			}
			context.getInput().releaseKey(options -> options.keySprint);
			if (singleplayer.getServer().computeOnServer(server -> startY - pods[0].getY()) < DRILL_DEPTH) {
				throw new AssertionError("The tower did not drill " + DRILL_DEPTH + " blocks down within " + MAX_DRILL_TICKS + " ticks");
			}
			for (int i = 0; i < DRIVE_FRAMES / 2; i++) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
			screenshot(context, "towed-pod-in-the-shaft");
		}
	}

	private static PodEntity spawn(ServerLevel level, Vec3 at) {
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(at);
		level.addFreshEntity(pod);
		return pod;
	}

	private static void fill(ServerLevel level, int yFrom, int yTo, Block block) {
		for (int x = X - 5; x <= X + 5; x++) {
			for (int y = yFrom; y <= yTo; y++) {
				for (int z = Z - 5; z <= Z + 16; z++) {
					level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
				}
			}
		}
	}
}
