package io.github.pkeppeler.deepcharter.sound;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public final class SoundRegistry {
	private SoundRegistry() {
	}

	/** Registers every {@link DeepSound}. Placeholders live in sounds.json; the private pack replaces them. */
	public static void register() {
		for (DeepSound sound : DeepSound.values()) {
			Registry.register(BuiltInRegistries.SOUND_EVENT, sound.event().location(), sound.event());
		}
	}
}
