package io.github.pkeppeler.deepcharter.client.transmission;

import java.util.List;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.TransmissionLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;

/**
 * Draws {@link TransmissionOverlay} as a CRT panel over the whole HUD: a dark window with a border, the header in its framing's colour,
 * the text typed below it, and scanlines over the panel. It is registered once the client has started, with {@code addLast}, after the
 * breach fade of the layer feature, so the text is drawn over the black and not under it.
 */
public final class TransmissionHud {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "transmission");

	private TransmissionHud() {
	}

	static void init() {
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> HudElementRegistry.addLast(ID, TransmissionHud::extract));
	}

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (!TransmissionOverlay.active()) {
			return;
		}
		CrtTuning tuning = CrtTuning.current();
		TransmissionLook look = TransmissionLook.current();
		Font font = Minecraft.getInstance().font;
		int padding = tuning.padding();
		int width = Math.min(look.maxWidth(), graphics.guiWidth() - 2 * look.screenMargin());
		int textWidth = width - 2 * padding;
		int step = font.lineHeight + tuning.lineSpacing();

		List<String> lines = wrap(font, TransmissionOverlay.bodyFull(), textWidth);
		int height = 2 * padding + step + look.headerGap() + lines.size() * step;
		int left = (graphics.guiWidth() - width) / 2;
		int top = Math.max(look.screenMargin(), Math.round(graphics.guiHeight() * (float) look.centerY() - height / 2f));
		int right = left + width;
		int bottom = top + height;

		graphics.fill(left, top, right, bottom, look.panelFillColor());
		CrtDraw.border(graphics, left, top, right, bottom, tuning.dimColor());

		int x = left + padding;
		int y = top + padding;
		int headerColor = TransmissionOverlay.headerColor(TransmissionOverlay.transmission().orElseThrow().framing());
		CrtDraw.glowText(graphics, font, TransmissionOverlay.headerShown(), x, y, headerColor);
		y += step + look.headerGap();

		// The panel is sized for the whole text, and the letters appear in it, so the lines never re-wrap as they type.
		int remaining = TransmissionOverlay.bodyShown().length();
		int lastX = x;
		int lastY = y;
		for (String line : lines) {
			if (remaining <= 0) {
				break;
			}
			String shown = line.substring(0, Math.min(line.length(), remaining));
			CrtDraw.glowText(graphics, font, shown, x, y, look.textColor());
			lastX = x + font.width(shown);
			lastY = y;
			remaining -= line.length();
			y += step;
		}
		if (!TransmissionOverlay.typed()) {
			graphics.fill(lastX + 1, lastY, lastX + font.width("W"), lastY + font.lineHeight - 1, look.textColor());
		}

		graphics.enableScissor(left, top, right, bottom);
		CrtDraw.scanlines(graphics, graphics.guiWidth(), graphics.guiHeight());
		graphics.disableScissor();
	}

	private static List<String> wrap(Font font, String text, int width) {
		return font.getSplitter().splitLines(FormattedText.of(text), width, Style.EMPTY).stream().map(FormattedText::getString).toList();
	}
}
