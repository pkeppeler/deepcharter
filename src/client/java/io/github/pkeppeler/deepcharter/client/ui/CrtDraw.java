package io.github.pkeppeler.deepcharter.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Drawing helpers of the CRT look, shared by {@link CrtScreen} and the widgets. All colours come from {@link CrtTuning}. */
public final class CrtDraw {
	private static final CrtTuning T = CrtTuning.DEFAULT;

	private CrtDraw() {
	}

	/** Near-black background with a faint phosphor bloom along the top and bottom edges. */
	public static void backdrop(GuiGraphicsExtractor graphics, int width, int height) {
		graphics.fill(0, 0, width, height, T.backgroundColor());
		int transparent = T.bloomColor() & 0x00FFFFFF;
		graphics.fillGradient(0, 0, width, T.bloomHeight(), T.bloomColor(), transparent);
		graphics.fillGradient(0, height - T.bloomHeight(), width, height, transparent, T.bloomColor());
	}

	/** One dark line every {@link CrtTuning#scanlineSpacing()} pixels, drawn over everything. */
	public static void scanlines(GuiGraphicsExtractor graphics, int width, int height) {
		for (int y = 0; y < height; y += T.scanlineSpacing()) {
			graphics.fill(0, y, width, y + 1, T.scanlineColor());
		}
	}

	/** Text with a halo: a dim copy one pixel out in each direction, then the bright text on top. */
	public static void glowText(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color) {
		int halo = T.glowColor();
		graphics.text(font, text, x - 1, y, halo, false);
		graphics.text(font, text, x + 1, y, halo, false);
		graphics.text(font, text, x, y - 1, halo, false);
		graphics.text(font, text, x, y + 1, halo, false);
		graphics.text(font, text, x, y, color, false);
	}

	/** A one-pixel rectangle outline. */
	public static void border(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int color) {
		graphics.fill(left, top, right, top + 1, color);
		graphics.fill(left, bottom - 1, right, bottom, color);
		graphics.fill(left, top, left + 1, bottom, color);
		graphics.fill(right - 1, top, right, bottom, color);
	}
}
