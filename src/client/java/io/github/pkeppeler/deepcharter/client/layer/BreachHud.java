package io.github.pkeppeler.deepcharter.client.layer;

import java.util.List;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
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
 * The altimeter, top centre (the pod readout is top left), always on screen while in a world. Then the
 * breach fade over the whole HUD, with the transmission typed on top of it.
 *
 * <p>The fade is a full blackout, so it is registered when the client has started, after every mod's
 * initializer has registered its own elements. {@code addLast} puts it after all of them, so no other
 * HUD element draws over the black. An element registered later than that would draw over it.
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
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> HudElementRegistry.addLast(BREACH, BreachHud::extractBreach));
	}

	private static void extractAltimeter(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null) {
			return;
		}
		Component reading = Altimeter.reading(client);
		BreachEffects.Offset jitter = BreachEffects.jitter();
		int x = (graphics.guiWidth() - client.font.width(reading)) / 2 + jitter.x();
		graphics.text(client.font, reading, x, MARGIN + jitter.y(), WHITE);
	}

	private static void extractBreach(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		float alpha = BreachEffects.fadeAlpha(deltaTracker.getGameTimeDeltaPartialTick(false));
		if (alpha > 0f) {
			graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), ARGB.color(Math.round(alpha * 255), 0, 0, 0));
		}
		List<String> shown = BreachEffects.transmissionShown();
		Font font = Minecraft.getInstance().font;
		BreachEffects.Offset jitter = BreachEffects.jitter();
		int y = graphics.guiHeight() / 2 - font.lineHeight + jitter.y();
		for (String line : shown) {
			graphics.text(font, line, graphics.guiWidth() / 2 - font.width(line) / 2 + jitter.x(), y, TRANSMISSION_GREEN);
			y += font.lineHeight + 4;
		}
	}
}
