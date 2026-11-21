package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.test.support.DedicatedServers;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import java.lang.ref.WeakReference;
import java.util.Properties;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.server.level.ServerLevel;

/**
 * A server that has stopped is garbage (#803). The client run makes a server for each test class, and one that stays on the heap costs
 * about 70 MB: it was 4 GB by the 57th class. Two things kept servers: {@code FarChunks}, whose record of a pinned chunk (the colony pad's,
 * in every world) holds its {@code ServerLevel}, and the dedicated server's console thread, which reads standard input for the life of
 * the JVM and holds its server ({@link DedicatedServers#start}). This test starts a dedicated server, stops it, and waits for the
 * collector to take the server and its overworld.
 */
public class WorldRetentionClientTest implements FabricClientGameTest {
    private static final int COLLECT_SECONDS = 30;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        Properties properties = new Properties();
        properties.setProperty("server-port", Integer.toString(DedicatedServers.freePort()));
        WeakReference<?>[] kept = new WeakReference<?>[2];
        TestDedicatedServerContext server = DedicatedServers.start(context, properties);
        try {
            server.runOnServer(minecraftServer -> {
                ServerLevel overworld = minecraftServer.overworld();
                FarChunks.pin(overworld, 0, 0);
                require(FarChunks.heldCount() > 0, "a pinned chunk should be held while its server runs");
                kept[0] = new WeakReference<>(minecraftServer);
                kept[1] = new WeakReference<>(overworld);
            });
        } finally {
            server.close();
        }
        require(
                FarChunks.heldCount() == 0,
                "the server has stopped, and tests still hold " + FarChunks.heldCount() + " chunks of it");
        requireCollected("the stopped server", kept[0]);
        requireCollected("the stopped server's overworld", kept[1]);
    }

    private static void requireCollected(String what, WeakReference<?> reference) {
        long end = System.nanoTime() + COLLECT_SECONDS * 1_000_000_000L;
        while (reference.get() != null && System.nanoTime() < end) {
            System.gc();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the collector", e);
            }
        }
        require(
                reference.get() == null,
                what + " is still on the heap, " + COLLECT_SECONDS + " s and as many collections after it stopped");
    }
}
