package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import java.lang.ref.WeakReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A world that has ended is garbage (#803). The client run makes a server for each test class, and a world that stays on the heap costs
 * about 70 MB: it was 4 GB by the 57th class. Two things kept worlds: {@code FarChunks}, whose record of a pinned chunk (the colony pad's,
 * in every world) holds its {@code ServerLevel}, and a mock player still on when its server stopped, which stayed in Fabric's networking
 * registry. This test builds a world with a colony and a mock, leaves it, and waits for the collector to take its overworld and the mock.
 */
public class WorldRetentionClientTest implements FabricClientGameTest {
    private static final int COLLECT_SECONDS = 30;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        WeakReference<?>[] kept = new WeakReference<?>[2];
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the client in the world", client -> client.player != null && client.level != null);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel overworld = server.overworld();
                ServerPlayer real = server.getPlayerList().getPlayers().getFirst();
                ServerPlayer mock = MockPlayers.join(server, "retained-mock", overworld, real.position())
                        .player();
                FarChunks.pin(
                        overworld,
                        real.chunkPosition().x(),
                        real.chunkPosition().z());
                require(FarChunks.heldCount() > 0, "a pinned chunk should be held while its world runs");
                kept[0] = new WeakReference<>(overworld);
                kept[1] = new WeakReference<>(mock);
            });
        }
        require(
                FarChunks.heldCount() == 0,
                "the world has ended, and tests still hold " + FarChunks.heldCount() + " chunks of it");
        requireCollected("the overworld of the ended world", kept[0]);
        requireCollected("the mock player of the ended world", kept[1]);
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
                what + " is still on the heap " + COLLECT_SECONDS + " s and as many collections after it ended");
    }
}
