package io.github.pkeppeler.deepcharter.test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.pod.TowTuning;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;

/**
 * Client GameTest: a remote client sees a tow. The mock pilot drives a tower, and the real client, which rides nothing, sees the
 * synced cable on the pod behind it, sees that pod follow, and sees the cable come off.
 */
public class TowingClientTest implements FabricClientGameTest {
	private static final int X = 700;
	private static final int Z = 700;
	private static final int FLOOR_Y = 200;
	private static final int DRIVE_TICKS = 60;
	private static final double MIN_FOLLOWED_BLOCKS = 5;
	/** What the client's pods may disagree by: each one is interpolated from what the server last sent. */
	private static final double CLIENT_LAG_BLOCKS = 1.5;
	private static final Input FORWARD = new Input(true, false, false, false, false, false, false);

	/** The two pods' ids on the server, which are also their ids on the client, and the tower's UUID. */
	private record Rig(int towerId, int towedId, UUID towerUuid) {
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
			Rig rig = two.server().computeOnServer(server -> setUp(server.overworld(), two));
			context.waitFor(client -> client.level.getEntity(rig.towerId()) instanceof PodEntity
					&& client.level.getEntity(rig.towedId()) instanceof PodEntity towed
					&& Optional.of(rig.towerUuid()).equals(PodTowing.towerId(towed)));
			Vec3 towedStart = context.computeOnClient(client -> client.level.getEntity(rig.towedId()).position());

			two.server().runOnServer(server -> two.mock().setInput(FORWARD));
			context.waitTicks(DRIVE_TICKS);
			two.server().runOnServer(server -> two.mock().releaseInput());
			context.waitTicks(10);

			Vec3 towedEnd = context.computeOnClient(client -> client.level.getEntity(rig.towedId()).position());
			Vec3 towerEnd = context.computeOnClient(client -> client.level.getEntity(rig.towerId()).position());
			double followed = towedEnd.subtract(towedStart).horizontalDistance();
			if (followed < MIN_FOLLOWED_BLOCKS) {
				throw new AssertionError("The client should see the towed pod follow its tower, it moved " + followed + " blocks");
			}
			double gap = towedEnd.distanceTo(towerEnd);
			if (gap > TowTuning.DEFAULT.trailDistance() + CLIENT_LAG_BLOCKS) {
				throw new AssertionError("The client should see the towed pod within the cable's trail of its tower, it is " + gap + " blocks away");
			}

			two.server().runOnServer(server -> {
				if (!PodTowing.detach((PodEntity) server.overworld().getEntity(rig.towedId()))) {
					throw new AssertionError("the towed pod should have a cable to take off");
				}
			});
			context.waitFor(client -> client.level.getEntity(rig.towedId()) instanceof PodEntity towed && !PodTowing.isTowed(towed));
		}
	}

	/** A stone slab high above the terrain, the real player on it, the mock in a tower with a pod on a cable behind it. */
	private static Rig setUp(ServerLevel level, TwoPlayerServer two) {
		fill(level, FLOOR_Y - 8, FLOOR_Y - 1, Blocks.STONE);
		fill(level, FLOOR_Y, FLOOR_Y + 10, Blocks.AIR);
		ServerPlayer real = level.getServer().getPlayerList().getPlayers().stream()
				.filter(player -> player != two.mock().player()).findFirst().orElseThrow();
		real.teleportTo(level, X + 4.5, FLOOR_Y, Z + 8.5, Set.of(), 0, 0, true);
		Vec3 at = new Vec3(X + 0.5, FLOOR_Y, Z + 0.5);
		two.mock().teleportTo(level, at, 0, 0);
		PodEntity tower = spawn(level, at);
		PodEntity towed = spawn(level, at.add(0, 0, -2));
		if (!two.mock().player().startRiding(tower)) {
			throw new AssertionError("the mock pilot could not mount the tower");
		}
		PodTowing.attach(tower, towed);
		return new Rig(tower.getId(), towed.getId(), tower.getUUID());
	}

	private static PodEntity spawn(ServerLevel level, Vec3 at) {
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(at);
		level.addFreshEntity(pod);
		return pod;
	}

	private static void fill(ServerLevel level, int yFrom, int yTo, Block block) {
		for (int x = X - 4; x <= X + 5; x++) {
			for (int y = yFrom; y <= yTo; y++) {
				for (int z = Z - 6; z <= Z + 16; z++) {
					level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
				}
			}
		}
	}
}
