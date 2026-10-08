package io.github.pkeppeler.deepcharter.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;

import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.sound.SoundTuning;

/**
 * The letter hook of a typewriter: one click for every {@link SoundTuning#typewriterLettersPerClick()} letters, none for a
 * space. A typewriter can reveal several letters in a tick, so the click rate is below the letter rate. Make one per typewriter.
 */
public final class TypewriterSound implements Typewriter.LetterHook {
	private int lettersSinceClick;

	@Override
	public void onLetter(int index, char letter) {
		if (Character.isWhitespace(letter)) {
			return;
		}
		if (++lettersSinceClick >= SoundTuning.DEFAULT.typewriterLettersPerClick()) {
			lettersSinceClick = 0;
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(DeepSound.UI_TYPEWRITER.event(), 1.0f));
		}
	}
}
