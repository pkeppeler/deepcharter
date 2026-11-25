package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.scanner.ScannerClientInit;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * #1250: leaving a world frees the scanner map's texture on the render thread. The disconnect event can fire on a
 * network thread, and freeing a GPU texture there logs "Rendersystem called from wrong thread" and may leak it.
 */
public class ScannerMapReleaseClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(20);
            ScannerHudTest.rideWithGoldAhead(context, singleplayer.getServer(), 1);
            ClientWait.until(context, "the scanner map drawn", client -> ScannerClientInit.mapHeld());
        }
        ClientWait.until(context, "the scanner map freed after the disconnect", client -> !ScannerClientInit.mapHeld());
        require(
                Boolean.TRUE.equals(ScannerClientInit.mapReleasedOnRenderThread()),
                "the scanner map was freed off the render thread: " + ScannerClientInit.mapReleasedOnRenderThread());
    }
}
