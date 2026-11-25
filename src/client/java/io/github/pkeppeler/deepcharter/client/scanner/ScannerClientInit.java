package io.github.pkeppeler.deepcharter.client.scanner;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.jspecify.annotations.Nullable;

/**
 * Client entry point for the scanner, called from DeepCharterClient.
 * Each part of the scanner feature registers itself from here, one line per part.
 */
public final class ScannerClientInit {
    private ScannerClientInit() {}

    public static void init() {
        ScannerClientRegistry.register();
        ScannerHud.init();
        // Release once the level is gone: a last frame would re-create the texture after a queued release.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null && ScannerMap.held()) {
                ScannerMap.release();
            }
        });
    }

    // test-seam: the client test reads that the map's picture is held after a scan and gone after the disconnect
    public static boolean mapHeld() {
        return ScannerMap.held();
    }

    // test-seam: the client test reads which thread freed the picture, which nothing in the game reads back
    public static @Nullable Boolean mapReleasedOnRenderThread() {
        return ScannerMap.releasedOnRenderThread();
    }
}
