package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/** Evidence scenario "m2-wrecks": a working pod next to a pod whose hull has run out, which is dark. */
public class WreckScenario extends EvidenceScenario {
	@Override
	protected String name() {
		return "m2-wrecks";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.teleportTo(player.level(), player.getX(), player.getY(), player.getZ(), Set.of(), 0f, 0f, true);
				// The player faces +z (south). Two pods stand in front of the player, 3 blocks apart.
				spawn(player, new Vec3(-2.5, 0, 6), false);
				spawn(player, new Vec3(2.5, 0, 6), true);
			});
			context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
			context.waitTicks(60);
			screenshot(context, "working-pod-and-dark-wreck");
			frame(context);
		}
	}

	private static void spawn(ServerPlayer player, Vec3 offset, boolean wreck) {
		PodEntity pod = PodRegistry.POD.create(player.level(), EntitySpawnReason.COMMAND);
		pod.setPos(player.position().add(offset));
		player.level().addFreshEntity(pod);
		if (wreck) {
			pod.damageHull(pod.maxHull());
			if (!Wrecks.isWreck(pod)) {
				throw new AssertionError("the pod should be a wreck");
			}
		}
	}
}
