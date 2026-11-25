package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * A world that has ended leaves no chunk held in {@link FarChunks} (#803). A held chunk's record keeps its {@code ServerLevel}, which keeps
 * the world's chunks; the colony pad's chunks are pinned in every world, so before the server's stop released them each client test class
 * left its world on the heap (70 MB a class, 4 GB by the 57th). The real stop fires {@code SERVER_STOPPED}, which this test waits for by
 * leaving the world.
 */
public class FarChunksReleaseClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the client in the world", client -> client.player != null && client.level != null);
            singleplayer.getServer().runOnServer(server -> {
                FarChunks.pin(server.overworld(), 0, 0);
                require(FarChunks.heldCount() > 0, "a pinned chunk should be held while its world runs");
            });
        }
        require(
                FarChunks.heldCount() == 0,
                "the world has ended, and tests still hold " + FarChunks.heldCount() + " chunks of it");
    }
}
