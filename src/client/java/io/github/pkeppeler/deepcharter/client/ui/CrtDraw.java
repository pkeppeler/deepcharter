package io.github.pkeppeler.deepcharter.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import io.github.pkeppeler.deepcharter.client.theme.Colors;

/** Drawing helpers of the CRT look, shared by {@link CrtScreen} and the widgets. All colours come from {@link CrtTuning}. */
public final class CrtDraw {
	private CrtDraw() {
	}

	/** Near-black background with a faint phosphor bloom along the top and bottom edges. */
	public static void backdrop(GuiGraphicsExtractor graphics, int width, int height) {
		CrtTuning tuning = CrtTuning.current();
		graphics.fill(0, 0, width, height, tuning.backgroundColor());
		int transparent = Colors.transparent(tuning.bloomColor());
		graphics.fillGradient(0, 0, width, tuning.bloomHeight(), tuning.bloomColor(), transparent);
		graphics.fillGradient(0, height - tuning.bloomHeight(), width, height, transparent, tuning.bloomColor());
	}

	/** One dark line every {@link CrtTuning#scanlineSpacing()} pixels, drawn over everything. */
	public static void scanlines(GuiGraphicsExtractor graphics, int width, int height) {
		CrtTuning tuning = CrtTuning.current();
		for (int y = 0; y < height; y += tuning.scanlineSpacing()) {
			graphics.fill(0, y, width, y + 1, tuning.scanlineColor());
		}
	}

	/** Text with a halo: a dim copy one pixel out in each direction, then the bright text on top. */
	public static void glowText(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color) {
		int halo = CrtTuning.current().glowColor();
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

	/**
	 * A terminal screen's title in capitals in bright phosphor at {@code margin}, and the dim rule under it.
	 */
	public static void header(GuiGraphicsExtractor graphics, Font font, String title, int margin, int width) {
		CrtTuning tuning = CrtTuning.current();
		glowText(graphics, font, title, margin, margin, tuning.phosphorColor());
		int ruleTop = margin + font.lineHeight + tuning.headerRuleGap();
		border(graphics, margin - tuning.headerRuleInset(), ruleTop, width - margin + tuning.headerRuleInset(), ruleTop + 1, tuning.dimColor());
	}
}
