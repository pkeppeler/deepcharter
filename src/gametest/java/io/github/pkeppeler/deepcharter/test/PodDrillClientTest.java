package io.github.pkeppeler.deepcharter.test;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
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
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;

/** Client GameTest: the client sees the mock pilot's blocks disappear, and only the pod's 2 x 2 of them. */
public class PodDrillClientTest implements FabricClientGameTest {
	private static final int X = 500;
	private static final int Z = 500;
	private static final int FLOOR_Y = 200;
	private static final int BORE_TICKS = 400;
	private static final Input SPRINT = new Input(false, false, false, false, false, false, true);

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
			// A stone slab high above the terrain, with the real player on it beside the mock's pod.
			int podId = two.server().computeOnServer(server -> {
				ServerLevel level = server.overworld();
				fill(level, FLOOR_Y - 8, FLOOR_Y - 1, Blocks.STONE);
				fill(level, FLOOR_Y, FLOOR_Y + 10, Blocks.AIR);
				ServerPlayer real = server.getPlayerList().getPlayers().stream()
						.filter(player -> player != two.mock().player()).findFirst().orElseThrow();
				real.teleportTo(level, X + 3.5, FLOOR_Y, Z + 0.5, Set.of(), 0, 0, true);
				Vec3 at = new Vec3(X, FLOOR_Y, Z);
				two.mock().teleportTo(level, at, 0, 0);
				PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
				pod.setPos(at);
				level.addFreshEntity(pod);
				if (!two.mock().player().startRiding(pod)) {
					throw new AssertionError("the mock pilot could not mount the pod");
				}
				return pod.getId();
			});
			// The mock pod stands on a 2 x 2 of stone the client has loaded.
			context.waitFor(client -> client.level.getEntity(podId) != null && !client.level.getBlockState(cell(X, FLOOR_Y - 1, Z)).isAir());

			two.server().runOnServer(server -> two.mock().setInput(SPRINT));
			context.waitFor(client -> client.level.getBlockState(cell(X, FLOOR_Y - 1, Z)).isAir(), BORE_TICKS);
			two.server().runOnServer(server -> two.mock().releaseInput());

			for (BlockPos bored : new BlockPos[] {cell(X - 1, FLOOR_Y - 1, Z - 1), cell(X, FLOOR_Y - 1, Z - 1),
					cell(X - 1, FLOOR_Y - 1, Z), cell(X, FLOOR_Y - 1, Z)}) {
				if (!context.computeOnClient(client -> client.level.getBlockState(bored).isAir())) {
					throw new AssertionError("The client still sees stone at the bore's cell " + bored);
				}
			}
			if (context.computeOnClient(client -> client.level.getBlockState(cell(X + 1, FLOOR_Y - 1, Z)).isAir())) {
				throw new AssertionError("The client sees the bore wider than the pod's 2 x 2");
			}
			context.waitFor(client -> client.level.getEntity(podId).getY() < FLOOR_Y - 0.5);
		}
	}

	private static BlockPos cell(int x, int y, int z) {
		return new BlockPos(x, y, z);
	}

	private static void fill(ServerLevel level, int yFrom, int yTo, Block block) {
		for (int x = X - 4; x <= X + 5; x++) {
			for (int y = yFrom; y <= yTo; y++) {
				for (int z = Z - 4; z <= Z + 4; z++) {
					level.setBlock(cell(x, y, z), block.defaultBlockState(), 3);
				}
			}
		}
	}
}
