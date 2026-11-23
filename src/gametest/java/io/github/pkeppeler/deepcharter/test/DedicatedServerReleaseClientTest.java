package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.lang.ref.WeakReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.server.MinecraftServer;

/**
 * A dedicated server is collectable once the test has left it (#914): each client test class that started one used to leave it, with
 * its levels, on the heap until the JVM exited. A weak reference to the server must clear. If it does not, something holds the server:
 * dump the heap ({@code jcmd <pid> GC.heap_dump}) and look for the path to a GC root. The known holders are
 * {@code DedicatedServerRelease}'s three, then anything that keeps a {@code ServerLevel} (a static set of chunks or levels that
 * {@code SERVER_STOPPED} does not clear, as {@code FarChunks} once did).
 */
public class DedicatedServerReleaseClientTest implements FabricClientGameTest {
    private static final long RELEASE_MILLIS = 30_000;
    /** A full collection pauses the game; ticks between them are enough for a reference to clear. */
    private static final long GC_INTERVAL_MILLIS = 250;

    @Override
    public void runTest(ClientGameTestContext context) {
        WeakReference<MinecraftServer> left = joinAndLeave(context);
        long start = System.currentTimeMillis();
        long lastGc = 0;
        int collections = 0;
        while (left.get() != null && System.currentTimeMillis() - start < RELEASE_MILLIS) {
            if (System.currentTimeMillis() - lastGc >= GC_INTERVAL_MILLIS) {
                System.gc();
                collections++;
                lastGc = System.currentTimeMillis();
            }
            context.waitTick();
        }
        if (left.get() != null) {
            throw new AssertionError(
                    "The dedicated server is still on the heap " + RELEASE_MILLIS + " ms (deadline) and " + collections
                            + " full collections after the test left it (#914): something holds it");
        }
    }

    /** In its own frame, so no local of the test keeps the server or its context alive. */
    private static WeakReference<MinecraftServer> joinAndLeave(ClientGameTestContext context) {
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            return two.server().computeOnServer(WeakReference::new);
        }
    }
}
