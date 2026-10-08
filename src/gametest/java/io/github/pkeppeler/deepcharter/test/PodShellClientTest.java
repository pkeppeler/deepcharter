package io.github.pkeppeler.deepcharter.test;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;

import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

/** Client GameTest: the client sees itself riding a pod. */
public class PodShellClientTest implements FabricClientGameTest {
	/** Hull value that differs from a new pod's, to prove that synced data reaches the client. */
	public static final float DAMAGED_HULL = 42f;

	/** Spawn a pod under the first player on the server and put the player in its seat. */
	public static void mountFirstPlayer(TestServerContext server) {
		server.runOnServer(minecraftServer -> {
			ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
			PodEntity pod = PodRegistry.POD.create(player.level(), EntitySpawnReason.COMMAND);
			pod.setPos(player.position());
			pod.setHull(DAMAGED_HULL);
			player.level().addFreshEntity(pod);
			if (!player.startRiding(pod)) {
				throw new AssertionError("the player could not mount the new pod");
			}
		});
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			mountFirstPlayer(singleplayer.getServer());
			ClientWait.until(context, "the client riding a pod", client -> client.player != null && client.player.getVehicle() instanceof PodEntity);

			float hull = context.computeOnClient(client -> ((PodEntity) client.player.getVehicle()).hull());
			if (hull != DAMAGED_HULL) {
				throw new AssertionError("The client should see the pod's synced hull " + DAMAGED_HULL + ", saw " + hull);
			}
			List<Component> lines = context.computeOnClient(
					client -> PodStatusHud.lines((PodEntity) client.player.getVehicle()));
			if (!lines.getFirst().getString().contains("42")) {
				throw new AssertionError("The HUD should show hull 42 first, showed " + lines);
			}
			boolean sameEntity = context.computeOnClient(client -> client.player.getVehicle() == client.level.getEntity(
					client.player.getVehicle().getId()));
			if (!sameEntity) {
				throw new AssertionError("The client's vehicle is not the entity in its level");
			}

			singleplayer.getServer().runOnServer(minecraftServer ->
					minecraftServer.getPlayerList().getPlayers().getFirst().stopRiding());
			ClientWait.until(context, "the player out of the pod", client -> client.player.getVehicle() == null);
		}
	}
}
