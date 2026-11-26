package io.github.pkeppeler.deepcharter.client.sound;

import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreens;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

/**
 * The terminal's music: one loop from the tick a terminal screen opens to the tick it closes, a working terminal's or an
 * unrepaired one's ({@link TerminalScreens#isTerminalScreen}). The level's own background music is stopped when it starts,
 * and the level may start its music again while the terminal is open.
 */
public final class TerminalMusic {
    private static @Nullable Music playing;

    private TerminalMusic() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(TerminalMusic::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> playing = null);
    }

    private static void tick(Minecraft client) {
        if (!TerminalScreens.isTerminalScreen(client.gui.screen())) {
            return;
        }
        client.getMusicManager().stopPlaying();
        // The engine can drop a loop without stopping it (SoundManager.stop() clears its channels and leaves the
        // instances as they were, as the Continuity Event's silence does at a death), so a loop it no longer plays is
        // gone, as vanilla's MusicManager reads it.
        if (playing != null && !playing.isStopped() && client.getSoundManager().isActive(playing)) {
            return;
        }
        playing = new Music();
        client.getSoundManager().play(playing);
    }

    /** Ends itself when the terminal screen is closed. */
    private static final class Music extends AbstractTickableSoundInstance {
        private Music() {
            super(DeepSound.MUSIC_TERMINAL.event(), SoundSource.MUSIC, RandomSource.create());
            this.looping = true;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override
        public void tick() {
            if (!TerminalScreens.isTerminalScreen(Minecraft.getInstance().gui.screen())) {
                stop();
            }
        }
    }
}
