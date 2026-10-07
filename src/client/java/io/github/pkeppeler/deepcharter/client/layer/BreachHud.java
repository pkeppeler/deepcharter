package io.github.pkeppeler.deepcharter.client.layer;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The altimeter, top centre (the pod readout is top left), always on screen while in a world (on the surface, in a layer, in or out of a pod),
 * then the breach fade over everything with the transmission typed on top of it.
 */
public final class BreachHud {
	private static final Identifier ALTIMETER = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "altimeter");
	private static final Identifier BREACH = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "breach_fade");
	private static final int MARGIN = 4;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int TRANSMISSION_GREEN = 0xFF7CFC9A;

	private BreachHud() {
	}

	public static void init() {
		HudElementRegistry.addLast(ALTIMETER, BreachHud::extractAltimeter);
		// Registered after the altimeter, so the fade covers it.
		HudElementRegistry.addLast(BREACH, BreachHud::extractBreach);
	}

	private static void extractAltimeter(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null) {
			return;
		}
		Component reading = Altimeter.reading(client);
		int[] jitter = BreachEffects.jitter();
		int x = (graphics.guiWidth() - client.font.width(reading)) / 2 + jitter[0];
		graphics.text(client.font, reading, x, MARGIN + jitter[1], WHITE);
	}

	private static void extractBreach(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		float alpha = BreachEffects.fadeAlpha(deltaTracker.getGameTimeDeltaPartialTick(false));
		if (alpha > 0f) {
			graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), ARGB.color(Math.round(alpha * 255), 0, 0, 0));
		}
		String shown = BreachEffects.transmissionShown();
		if (shown.isEmpty()) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		int[] jitter = BreachEffects.jitter();
		int y = graphics.guiHeight() / 2 - font.lineHeight + jitter[1];
		for (String line : shown.split("\n", -1)) {
			graphics.text(font, line, graphics.guiWidth() / 2 - font.width(line) / 2 + jitter[0], y, TRANSMISSION_GREEN);
			y += font.lineHeight + 4;
		}
	}
}
