package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/** Evidence scenario "m2-wrecks": a working pod loses its hull and goes dark, next to a working pod, and the owner charter (the player, who is not riding) is told in chat. */
public class WreckScenario extends EvidenceScenario {
	private static final int FRAMES_BEFORE = 10;
	private static final int FRAMES_AFTER = 14;

	@Override
	protected String name() {
		return "m2-wrecks";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			PodEntity[] doomed = {null};
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				// The player faces +z (south). Two pods stand in front of the player, 5 blocks apart.
				player.teleportTo(player.level(), player.getX(), player.getY(), player.getZ(), Set.of(), 0f, 0f, true);
				clearStage(player);
				Charters.found(server, player.getUUID(), "Evidence Charter").ifPresent(refusal -> {
					throw new AssertionError("could not found a charter: " + refusal);
				});
				CharterId charter = Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow().id();
				// Both pods belong to the player's charter, and nobody rides the doomed one: the owner is still told.
				PodComponents.register(spawn(player, new Vec3(-2.5, 0, 6)), charter);
				doomed[0] = spawn(player, new Vec3(2.5, 0, 6));
				PodComponents.register(doomed[0], charter);
			});
			context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
			context.waitTicks(60);
			for (int i = 0; i < FRAMES_BEFORE; i++) {
				frame(context);
				context.waitTicks(3);
			}
			screenshot(context, "both-pods-working");
			singleplayer.getServer().runOnServer(server -> {
				doomed[0].damageHull(doomed[0].maxHull());
				if (!Wrecks.isWreck(doomed[0])) {
					throw new AssertionError("the pod should be a wreck");
				}
			});
			for (int i = 0; i < FRAMES_AFTER; i++) {
				context.waitTicks(3);
				frame(context);
			}
			screenshot(context, "working-pod-and-dark-wreck");
		}
	}

	/** Opens a room of air in front of the player, so the pods are in view even when the spawn is inside the colony's buildings. */
	private static void clearStage(ServerPlayer player) {
		BlockPos origin = player.blockPosition();
		for (int dx = -6; dx <= 6; dx++) {
			for (int dz = 1; dz <= 10; dz++) {
				player.level().setBlockAndUpdate(origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
				for (int dy = 0; dy <= 5; dy++) {
					player.level().setBlockAndUpdate(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
				}
			}
		}
	}

	private static PodEntity spawn(ServerPlayer player, Vec3 offset) {
		PodEntity pod = PodRegistry.POD.create(player.level(), EntitySpawnReason.COMMAND);
		pod.setPos(player.position().add(offset));
		player.level().addFreshEntity(pod);
		return pod;
	}
}
