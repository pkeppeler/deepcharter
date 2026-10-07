package io.github.pkeppeler.deepcharter.test.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
	private static final Logger LOGGER = LoggerFactory.getLogger(TwoPlayerServer.class);
	private static final String MOCK_NAME = "MockPilot";
	/** The server's max-players. The mock counts against it, so one slot is left for a real login. */
	private static final int PLAYER_LIMIT = 2;

	private final ClientGameTestContext context;
	private final TestDedicatedServerContext server;
	private final TestDedicatedServerConnection connection;
	private final MockPlayer mock;

	private TwoPlayerServer(ClientGameTestContext context, TestDedicatedServerContext server, TestDedicatedServerConnection connection, MockPlayer mock) {
		this.context = context;
		this.server = server;
		this.connection = connection;
		this.mock = mock;
	}

	/** Start the server, join the real client, join the mock player, and wait until the client sees both. */
	public static TwoPlayerServer start(ClientGameTestContext context) {
		// The default port 25565 would collide between parallel local runs. The properties are merged
		// over the harness defaults, so online-mode=false and the rest survive. Port 0 does not work:
		// connect() reads the configured port, not the bound one.
		int port = freePort();
		Properties properties = new Properties();
		properties.setProperty("server-port", Integer.toString(port));
		properties.setProperty("max-players", Integer.toString(PLAYER_LIMIT));
		LOGGER.info("TwoPlayerServer: dedicated server on port {}", port);
		TestDedicatedServerContext server = context.worldBuilder().createServer(properties);
		TestDedicatedServerConnection connection = null;
		try {
			connection = server.connect();
			MockPlayer mock = server.computeOnServer(minecraftServer -> MockPlayers.join(minecraftServer, MOCK_NAME));
			context.waitFor(client -> client.level != null && client.level.players().size() == 2);
			return new TwoPlayerServer(context, server, connection, mock);
		} catch (RuntimeException | Error e) {
			if (connection != null) {
				connection.close();
			}
			server.close();
			throw e;
		}
	}

	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		} catch (IOException e) {
			throw new UncheckedIOException("No free port for the test server", e);
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
			server.runOnServer(minecraftServer -> mock.leave());
		} finally {
			try {
				// The test may have closed its connection already, to reconnect.
				if (context.computeOnClient(client -> client.level != null)) {
					connection.close();
				}
			} finally {
				server.close();
			}
		}
	}
}
