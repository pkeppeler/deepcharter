package io.github.pkeppeler.deepcharter.test.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.Properties;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;

/** Starts the dedicated servers that client GameTests run in the client's JVM. */
public final class DedicatedServers {
    private DedicatedServers() {}

    /**
     * Starts a dedicated server with these properties over the harness defaults. {@code DedicatedServer.initServer} starts a daemon
     * "Server console handler" thread that reads {@code System.in} for as long as the JVM lives, and the thread holds its server: each
     * dedicated server a client run started stayed on the heap with all its levels (70 MB; 4 GB by the 57th class, #803). An empty
     * standard input makes the thread's {@code readLine} return at once, so the thread ends and the server can go. Nothing in a client
     * run reads standard input.
     */
    public static TestDedicatedServerContext start(ClientGameTestContext context, Properties properties) {
        System.setIn(InputStream.nullInputStream());
        return context.worldBuilder().createServer(properties);
    }

    /** A port nothing listens on: the default 25565 would collide between parallel local runs, and port 0 is not read back by {@code connect()}. */
    public static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("No free port for the test server", e);
        }
    }
}
