package io.github.pkeppeler.deepcharter.client.sound;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import io.github.pkeppeler.deepcharter.client.terminal.TerminalViewScreen;
import io.github.pkeppeler.deepcharter.sound.DeepSound;

/**
 * The terminal's music: one loop from the tick a terminal screen opens to the tick it closes. The level's own background music is
 * stopped when it starts, and the level may start its music again while the terminal is open.
 */
public final class TerminalMusic {
	private static Music playing;

	private TerminalMusic() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(TerminalMusic::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> playing = null);
	}

	private static void tick(Minecraft client) {
		if (!(client.gui.screen() instanceof TerminalViewScreen) || (playing != null && !playing.isStopped())) {
			return;
		}
		client.getMusicManager().stopPlaying();
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
			if (!(Minecraft.getInstance().gui.screen() instanceof TerminalViewScreen)) {
				stop();
			}
		}
	}
}
