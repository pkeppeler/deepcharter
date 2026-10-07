package io.github.pkeppeler.deepcharter.test;

import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.CharterView;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;

/**
 * Client GameTest for #52: the real client is told its charter's name and balance, is told again when they change, is told
 * when it joins as crew and when its Director changes, and is told its charter again when it reconnects.
 */
public class CharterCoreClientTest implements FabricClientGameTest {
	private static final String NAME = "Client Test Charter";
	private static final long FIRST_BALANCE = 250;
	private static final long SECOND_BALANCE = 175;
	private static final String TWO_PLAYER_NAME = "Two Player Charter";
	/** A fuse on client ticks for a wait that has no other limit. About 3 minutes at 20 ticks a second. */
	private static final int CLIENT_TICK_FUSE = 3600;
	/** Player count once the client has dropped: the mock stays online. */
	private static final int MOCK_ONLY = 1;

	@Override
	public void runTest(ClientGameTestContext context) {
		nameAndBalanceReachTheClient(context);
		crewAndDirectorChangesReachTheClient(context);
	}

	private static void nameAndBalanceReachTheClient(ClientGameTestContext context) {
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

	/**
	 * The mock player founds the charter and the real client joins it. Then the mock Director leaves and the client is promoted.
	 * Last, the client disconnects and reconnects: the login sync alone must give it the charter back.
	 */
	private static void crewAndDirectorChangesReachTheClient(ClientGameTestContext context) {
		try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
			UUID mock = two.mock().player().getUUID();
			UUID real = two.server().computeOnServer(server -> server.getPlayerList().getPlayers().stream()
					.map(player -> player.getUUID()).filter(uuid -> !uuid.equals(mock)).findFirst().orElseThrow());

			CharterId id = two.server().computeOnServer(server -> {
				if (Charters.found(server, mock, TWO_PLAYER_NAME).isPresent()) {
					throw new AssertionError("founding should succeed");
				}
				return Charters.charterOf(server, mock).orElseThrow().id();
			});
			two.server().runOnServer(server -> {
				if (Charters.apply(server, real, id).isPresent() || Charters.approve(server, mock, real).isPresent()) {
					throw new AssertionError("applying and approving should succeed");
				}
			});
			context.waitFor(client -> ClientCharter.view().filter(view -> view.name().equals(TWO_PLAYER_NAME) && view.people() == 2 && !view.director()).isPresent());

			two.server().runOnServer(server -> {
				if (Charters.leave(server, mock).isPresent()) {
					throw new AssertionError("the Director leaving should succeed");
				}
			});
			context.waitFor(client -> ClientCharter.view().filter(view -> view.people() == 1 && view.director()).isPresent());

			two.connection().close();
			context.waitFor(client -> client.level == null);
			for (int tick = 0; two.server().computeOnServer(server -> server.getPlayerCount()) > MOCK_ONLY; tick++) {
				if (tick > CLIENT_TICK_FUSE) {
					throw new AssertionError("the server never dropped the disconnected client");
				}
				context.waitTick();
			}
			try (var connection = two.server().connect()) {
				context.waitFor(client -> ClientCharter.view().filter(view -> view.name().equals(TWO_PLAYER_NAME) && view.director()).isPresent());
			}
		}
	}
}
