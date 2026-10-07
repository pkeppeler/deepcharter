package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import io.github.pkeppeler.deepcharter.pod.PodAttachments;
import io.github.pkeppeler.deepcharter.pod.PodAttachments.Example;
import io.github.pkeppeler.deepcharter.pod.PodEntity;

/** Client GameTest: a pod attachment synced with {@code AttachmentSyncPredicate.all()} reaches the client. */
public class PodAttachmentClientTest implements FabricClientGameTest {
	private static final int COUNTER = 99;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			PodShellClientTest.mountFirstPlayer(singleplayer.getServer());
			context.waitFor(client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
			singleplayer.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().getVehicle()
					.setAttached(PodAttachments.EXAMPLE, new Example(Example.CURRENT_VERSION, COUNTER)));

			context.waitFor(client -> client.player.getVehicle() != null
					&& new Example(Example.CURRENT_VERSION, COUNTER).equals(client.player.getVehicle().getAttached(PodAttachments.EXAMPLE)));
		}
	}
}
