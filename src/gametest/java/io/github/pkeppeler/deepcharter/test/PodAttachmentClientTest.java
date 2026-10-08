package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.test.support.TestAttachments;
import io.github.pkeppeler.deepcharter.test.support.TestAttachments.Example;

/** Client GameTest: a versioned pod attachment synced with {@code AttachmentSyncPredicate.all()} reaches the client. */
public class PodAttachmentClientTest implements FabricClientGameTest {
	private static final int COUNTER = 99;

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			PodShellClientTest.mountFirstPlayer(singleplayer.getServer());
			context.waitFor(client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
			singleplayer.getServer().runOnServer(server -> Versioned.modify(
					server.getPlayerList().getPlayers().getFirst().getVehicle(), TestAttachments.EXAMPLE, example -> new Example(COUNTER)));

			context.waitFor(client -> client.player.getVehicle() != null
					&& Versioned.of(new Example(COUNTER)).equals(client.player.getVehicle().getAttached(TestAttachments.EXAMPLE)));
		}
	}
}
