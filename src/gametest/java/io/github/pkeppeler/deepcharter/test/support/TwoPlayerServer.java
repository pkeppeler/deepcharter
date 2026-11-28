package io.github.pkeppeler.deepcharter.test.support;

import java.util.Properties;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

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
    /** The server's max-players. The mock counts against it, so one slot is left for a real login. */
    private static final int PLAYER_LIMIT = 2;

    private final ClientGameTestContext context;
    private final TestDedicatedServerContext server;
    private final TestDedicatedServerConnection connection;
    private final MockPlayer mock;

    private TwoPlayerServer(
            ClientGameTestContext context,
            TestDedicatedServerContext server,
            TestDedicatedServerConnection connection,
            MockPlayer mock) {
        this.context = context;
        this.server = server;
        this.connection = connection;
        this.mock = mock;
    }

    /** Start the server, join the real client, join the mock player, and wait until the client sees both. */
    public static TwoPlayerServer start(ClientGameTestContext context) {
        // The properties are merged over the harness defaults, so online-mode=false and the rest survive; createServer
        // picks the port.
        Properties properties = new Properties();
        properties.setProperty("max-players", Integer.toString(PLAYER_LIMIT));
        TestDedicatedServerContext server = DedicatedServerRelease.createServer(context, properties);
        TestDedicatedServerConnection connection = null;
        String stage =
                "the real client joining the dedicated server (connect, login and world load, waited on by WorldLoadWait for "
                        + WorldLoadWait.WORLD_LOAD_SECONDS + " s)";
        try {
            connection = server.connect();
            stage = "the mock player joining the server";
            MockPlayer mock = server.computeOnServer(TwoPlayerServer::joinBesideReal);
            stage = "the real client seeing both players";
            ClientWait.until(
                    context,
                    "the real client and the mock both in the level",
                    client -> client.level != null && client.level.players().size() == 2,
                    client -> "players "
                            + (client.level == null
                                    ? "no level (disconnected?)"
                                    : client.level.players().size()) + ", " + ClientWait.describe(client));
            return new TwoPlayerServer(context, server, connection, mock);
        } catch (RuntimeException | Error e) {
            if (connection != null) {
                connection.close();
            }
            server.close();
            throw new AssertionError("TwoPlayerServer.start failed during " + stage, e);
        }
    }

    /** Joins the mock where the real player stands. */
    private static MockPlayer joinBesideReal(MinecraftServer server) {
        ServerPlayer real = server.getPlayerList().getPlayers().getFirst();
        return MockPlayers.join(server, MOCK_NAME, real.level(), real.position());
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
