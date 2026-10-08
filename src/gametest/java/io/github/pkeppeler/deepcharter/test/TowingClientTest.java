package io.github.pkeppeler.deepcharter.test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
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
	/** Slow CI runners run the server well behind the client, so a step may need far more than a local run's few ticks. */
	private static final int WAIT_TICKS = 600;
	private static final double MIN_FOLLOWED_BLOCKS = 5;
	/** What the client's pods may disagree by: each one is interpolated from what the server last sent. */
	private static final double CLIENT_LAG_BLOCKS = 1.5;
	private static final Input FORWARD = new Input(true, false, false, false, false, false, false);

	/** The two pods by UUID: a pod whose chunk is unloaded and loaded again comes back as a new entity with a new id. */
	private record Rig(UUID tower, UUID towed) {
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
			Rig rig = two.server().computeOnServer(server -> setUp(server.overworld(), two));
			awaitClient(context, "the client sees both pods with the cable between them", client -> pod(client, rig.tower()) != null
					&& pod(client, rig.towed()) != null && Optional.of(rig.tower()).equals(PodTowing.towerId(pod(client, rig.towed()))));
			Vec3 towedStart = context.computeOnClient(client -> pod(client, rig.towed()).position());

			two.server().runOnServer(server -> two.mock().setInput(FORWARD));
			context.waitTicks(DRIVE_TICKS);
			two.server().runOnServer(server -> two.mock().releaseInput());
			context.waitTicks(10);

			Vec3 towedEnd = context.computeOnClient(client -> pod(client, rig.towed()).position());
			Vec3 towerEnd = context.computeOnClient(client -> pod(client, rig.tower()).position());
			double followed = towedEnd.subtract(towedStart).horizontalDistance();
			if (followed < MIN_FOLLOWED_BLOCKS) {
				throw new AssertionError("The client should see the towed pod follow its tower, it moved " + followed + " blocks");
			}
			double gap = towedEnd.distanceTo(towerEnd);
			if (gap > TowTuning.DEFAULT.trailDistance() + CLIENT_LAG_BLOCKS) {
				throw new AssertionError("The client should see the towed pod within the cable's trail of its tower, it is " + gap + " blocks away");
			}

			two.server().runOnServer(server -> {
				if (!PodTowing.detach((PodEntity) server.overworld().getEntity(rig.towed()))) {
					throw new AssertionError("the towed pod should have a cable to take off");
				}
			});
			awaitClient(context, "the client sees the cable come off the towed pod", client -> pod(client, rig.towed()) != null
					&& !PodTowing.isTowed(pod(client, rig.towed())));
		}
	}

	/** Waits for {@code condition} on the client, and on a timeout fails naming {@code step}: Fabric's own message does not. */
	private static void awaitClient(ClientGameTestContext context, String step, Predicate<Minecraft> condition) {
		try {
			context.waitFor(condition, WAIT_TICKS);
		} catch (AssertionError e) {
			throw new AssertionError("Timed out after " + WAIT_TICKS + " ticks waiting for: " + step, e);
		}
	}

	/** The client's copy of the pod, or null while it does not see it. */
	private static PodEntity pod(Minecraft client, UUID id) {
		return client.level.getEntity(id) instanceof PodEntity pod ? pod : null;
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
		return new Rig(tower.getUUID(), towed.getUUID());
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
