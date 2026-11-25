package io.github.pkeppeler.deepcharter.test.support;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * Counts what a vanilla respawn does and a seam swap must not (#1229, ADR 0089), while a client test has it armed: the
 * loading screen opening, {@code SoundManager.stop()} running, a respawn packet being handled, and the client being not
 * loaded. The sound and respawn counts come from test mixins ({@code SoundManagerStopProbeMixin}, {@code
 * ClientPacketListenerMixin}); inert until {@link #arm}, and a test calls {@link #disarm} in a {@code finally}. Client
 * thread to record, any thread to read.
 */
public final class SwapProbes {
    /** What was counted between {@link #arm} and {@link #disarm}. */
    public record Counts(int loadingScreens, int respawns, int soundStops, boolean everUnloaded) {}

    private static boolean installed;
    private static boolean armed;
    private static int loadingScreens;
    private static int respawns;
    private static int soundStops;
    private static boolean everUnloaded;

    private SwapProbes() {}

    /** Registers the screen and tick watchers, once; events cannot be unregistered, so they only count while armed. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof LevelLoadingScreen) {
                recordLoadingScreen();
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientPacketListener connection = client.getConnection();
            if (connection != null && !connection.hasClientLoaded()) {
                recordUnloaded();
            }
        });
    }

    /** Starts a fresh count. */
    public static synchronized void arm() {
        loadingScreens = 0;
        respawns = 0;
        soundStops = 0;
        everUnloaded = false;
        armed = true;
    }

    /** Stops counting and returns the counts. */
    public static synchronized Counts disarm() {
        armed = false;
        return new Counts(loadingScreens, respawns, soundStops, everUnloaded);
    }

    public static synchronized void recordLoadingScreen() {
        if (armed) {
            loadingScreens++;
        }
    }

    public static synchronized void recordRespawn() {
        if (armed) {
            respawns++;
        }
    }

    public static synchronized void recordSoundStop() {
        if (armed) {
            soundStops++;
        }
    }

    private static synchronized void recordUnloaded() {
        if (armed) {
            everUnloaded = true;
        }
    }
}
