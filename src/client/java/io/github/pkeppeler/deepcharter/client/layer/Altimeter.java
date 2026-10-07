package io.github.pkeppeler.deepcharter.client.layer;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import io.github.pkeppeler.deepcharter.layer.Depth;

/** The depth readout, for example "-1,234 ft.". Below sea level is negative. */
public final class Altimeter {
	private Altimeter() {
	}

	/** The reading for the client's player. Public so tests can check the text without reading pixels. */
	public static Component reading(Minecraft client) {
		int feet = Depth.feet(Depth.of(client.level, client.player.getBlockY()));
		// Locale.US so the thousands separator is the same on every machine.
		return Component.translatable("deepcharter.breach.altimeter", String.format(Locale.US, "%,d", -feet));
	}
}
