package io.github.pkeppeler.deepcharter.test.support;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

/**
 * Lets a stopped dedicated server be collected (#914). Each client test class that starts one left it, with all its levels (about
 * 50 MB each), on the heap until the JVM exited, held by three things this class removes:
 *
 * <ul>
 *   <li>The "Server Shutdown Thread" that {@code net.minecraft.server.Main} registers with the JVM and never removes.
 *       {@code MainShutdownHookMixin} reports each one to {@link #registered} and {@code SERVER_STOPPED} removes it.
 *   <li>The "Server console handler" thread, which blocks in {@code readLine} on {@code System.in} until the process's stdin closes
 *       (CI keeps it open): {@link #onInitialize} replaces {@code System.in} with an empty stream, so the thread ends at once.
 *   <li>The "Server Watchdog" thread, which sleeps up to {@code max-tick-time} after the server stops: {@link #createServer} sets it
 *       to -1, which starts none.
 * </ul>
 *
 * An integrated server has none of the three. A client test that starts a dedicated server does it through {@link #createServer}.
 * {@code DedicatedServerReleaseClientTest} guards all of it.
 */
public final class DedicatedServerRelease implements ModInitializer {
    /** The shutdown hook of each running dedicated server; an entry goes when its server stops, so the map never holds one. */
    private static final Map<MinecraftServer, Thread> HOOKS = new HashMap<>();

    @Override
    public void onInitialize() {
        System.setIn(InputStream.nullInputStream());
        ServerLifecycleEvents.SERVER_STOPPED.register(DedicatedServerRelease::stopped);
    }

    /**
     * Creates the dedicated server of a client test, with {@code properties} merged over the harness defaults and no watchdog.
     * Every client test that starts one calls this, never {@code worldBuilder().createServer} itself.
     */
    public static TestDedicatedServerContext createServer(ClientGameTestContext context, Properties properties) {
        Properties withoutWatchdog = new Properties();
        withoutWatchdog.putAll(properties);
        withoutWatchdog.setProperty("max-tick-time", "-1");
        return context.worldBuilder().createServer(withoutWatchdog);
    }

    /** Called by the mixin when {@code Main} has registered {@code hook} for {@code server}. */
    public static synchronized void registered(MinecraftServer server, Thread hook) {
        HOOKS.put(server, hook);
    }

    private static synchronized void stopped(MinecraftServer server) {
        Thread hook = HOOKS.remove(server);
        if (hook == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException shuttingDown) {
            // The JVM is already exiting (the hook itself stopped the server): the hook is running or about to, and
            // holds nothing
            // that outlives the process.
        }
    }
}
