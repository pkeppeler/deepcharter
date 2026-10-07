package io.github.pkeppeler.deepcharter.test;

import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import io.github.pkeppeler.deepcharter.charter.CharterView;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;

/** Client GameTest for #52: the real client is told its charter's name and balance, and is told again when they change. */
public class CharterCoreClientTest implements FabricClientGameTest {
	private static final String NAME = "Client Test Charter";
	private static final long FIRST_BALANCE = 250;
	private static final long SECOND_BALANCE = 175;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getServer().runOnServer(server -> {
				UUID player = server.getPlayerList().getPlayers().getFirst().getUUID();
				if (Charters.found(server, player, NAME).isPresent()) {
					throw new AssertionError("founding should succeed");
				}
				Charters.deposit(server, Charters.charterOf(server, player).orElseThrow().id(), FIRST_BALANCE);
			});
			context.waitFor(client -> ClientCharter.view().filter(view -> view.name().equals(NAME) && view.balance() == FIRST_BALANCE).isPresent());

			singleplayer.getServer().runOnServer(server -> {
				UUID player = server.getPlayerList().getPlayers().getFirst().getUUID();
				Charters.spend(server, Charters.charterOf(server, player).orElseThrow().id(), FIRST_BALANCE - SECOND_BALANCE);
			});
			context.waitFor(client -> ClientCharter.view().map(CharterView::balance).equals(Optional.of(SECOND_BALANCE)));

			singleplayer.getServer().runOnServer(server -> Charters.leave(server, server.getPlayerList().getPlayers().getFirst().getUUID()));
			context.waitFor(client -> ClientCharter.view().isEmpty());
		}
	}
}
