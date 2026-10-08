package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Evidence scenario "m2-pod-lights": a pod stands in a sealed stone room, so the room is pitch dark. Fitting a lights part lights
 * the room around the pod, the light follows the pod as it moves, and stranding the pod puts it out.
 */
public class PodLightsScenario extends EvidenceScenario {
	private static final int ROOM_RADIUS = 9;
	private static final int ROOM_HEIGHT = 5;
	private static final int TICKS_PER_FRAME = 3;
	private static final int DARK_FRAMES = 8;
	private static final int LIT_FRAMES = 8;
	private static final int MOVE_FRAMES = 26;
	private static final double MOVE_STEP = 0.2;
	private static final int STRANDED_FRAMES = 10;

	@Override
	protected String name() {
		return "m2-pod-lights";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			PodEntity[] pod = {null};
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				ServerLevel level = player.level();
				BlockPos floor = player.blockPosition().below();
				buildSealedRoom(level, floor);
				Vec3 spot = Vec3.atBottomCenterOf(floor.above());
				// The player faces +z (south) and the pod stands four blocks in front, left of centre, with pillars behind its path.
				for (int pillar = -1; pillar <= 1; pillar++) {
					for (int height = 0; height < 3; height++) {
						level.setBlock(floor.offset(pillar * 4, 1 + height, 7), Blocks.QUARTZ_BLOCK.defaultBlockState(), 3);
					}
				}
				player.teleportTo(level, spot.x, spot.y, spot.z - 1, Set.of(), 0f, 0f, true);
				pod[0] = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
				pod[0].setPos(spot.x - 2.6, spot.y, spot.z + 3);
				level.addFreshEntity(pod[0]);
				Charters.found(server, player.getUUID(), "Demo Charter");
				PodComponents.register(pod[0], Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow().id());
			});
			context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
			context.waitTicks(60);
			record(context, DARK_FRAMES);
			screenshot(context, "pod-in-the-dark");

			singleplayer.getServer().runOnServer(server -> {
				CharterId charter = PodComponents.registration(pod[0]).orElseThrow().owner();
				PodComponents.install(pod[0], ComponentItems.mint(server, ComponentTrack.LIGHTS, 2, charter));
			});
			record(context, LIT_FRAMES);
			screenshot(context, "pod-lit");

			for (int i = 0; i < MOVE_FRAMES; i++) {
				singleplayer.getServer().runOnServer(server -> pod[0].setPos(pod[0].getX() + MOVE_STEP, pod[0].getY(), pod[0].getZ()));
				record(context, 1);
			}
			screenshot(context, "light-followed-the-pod");

			singleplayer.getServer().runOnServer(server -> pod[0].setStranded(true));
			record(context, STRANDED_FRAMES);
			screenshot(context, "stranded-pod-dark");
		}
	}

	private void record(ClientGameTestContext context, int frames) {
		for (int i = 0; i < frames; i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
	}

	/** A stone box with its floor at {@code floor}: the sky cannot reach inside, whatever the time of day. */
	private static void buildSealedRoom(ServerLevel level, BlockPos floor) {
		for (int dx = -ROOM_RADIUS - 1; dx <= ROOM_RADIUS + 1; dx++) {
			for (int dz = -ROOM_RADIUS - 1; dz <= ROOM_RADIUS + 1; dz++) {
				for (int dy = 0; dy <= ROOM_HEIGHT + 1; dy++) {
					boolean shell = dy == 0 || dy == ROOM_HEIGHT + 1 || Math.abs(dx) == ROOM_RADIUS + 1 || Math.abs(dz) == ROOM_RADIUS + 1;
					level.setBlock(floor.offset(dx, dy, dz), (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
				}
			}
		}
	}
}
