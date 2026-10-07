package io.github.pkeppeler.deepcharter.test.support;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;

/**
 * A real {@code DedicatedServer} with two players: the real client, joined with
 * {@code connect()}, and one {@link MockPlayer}. Use it from a client GameTest or an evidence
 * scenario:
 *
 * <pre>{@code
 * try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
 *     two.server().runOnServer(server -> two.mock().setInput(...));
 * }
 * }</pre>
 *
 * <p>This exercises the dedicated-server path and the network between the server and one real
 * client. It does not give the mock player its own client: no decoding, rendering or latency.
 */
public final class TwoPlayerServer implements AutoCloseable {
	private static final String MOCK_NAME = "MockPilot";

	private final TestDedicatedServerContext server;
	private final TestDedicatedServerConnection connection;
	private final MockPlayer mock;

	private TwoPlayerServer(TestDedicatedServerContext server, TestDedicatedServerConnection connection, MockPlayer mock) {
		this.server = server;
		this.connection = connection;
		this.mock = mock;
	}

	/** Start the server, join the real client, join the mock player, and wait until the client sees both. */
	public static TwoPlayerServer start(ClientGameTestContext context) {
		TestDedicatedServerContext server = context.worldBuilder().createServer();
		TestDedicatedServerConnection connection = null;
		try {
			connection = server.connect();
			MockPlayer mock = server.computeOnServer(minecraftServer -> MockPlayers.join(minecraftServer, MOCK_NAME));
			context.waitFor(client -> client.level != null && client.level.players().size() == 2);
			return new TwoPlayerServer(server, connection, mock);
		} catch (RuntimeException | Error e) {
			if (connection != null) {
				connection.close();
			}
			server.close();
			throw e;
		}
	}

	public TestDedicatedServerContext server() {
		return server;
	}

	/** The real client's connection, giving access to its player and level. */
	public TestDedicatedServerConnection connection() {
		return connection;
	}

	public MockPlayer mock() {
		return mock;
	}

	@Override
	public void close() {
		try {
			server.runOnServer(minecraftServer -> {
				if (mock.isOnline()) {
					mock.leave();
				}
			});
		} finally {
			try {
				connection.close();
			} finally {
				server.close();
			}
		}
	}
}
