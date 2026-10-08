package io.github.pkeppeler.deepcharter.client.ui;

import io.github.pkeppeler.deepcharter.client.theme.UiTheme;
import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * The look of the CRT UI kit, read as {@code CrtTuning.current()} from {@code theme/crt.json} (ADR 0032). Colours are ARGB; all lengths are
 * GUI pixels. Read it on every draw and never keep it: a resource reload swaps in a new one.
 *
 * @param lettersPerSecond typewriter speed
 * @param cursorBlinkTicks ticks the typewriter block cursor stays on, then off
 * @param scanlineSpacing pixels from one scanline to the next
 * @param scanlineColor colour of one scanline, translucent black
 * @param glowColor colour of the halo drawn behind text, translucent phosphor
 * @param bloomHeight height of the soft phosphor light along the top and bottom screen edges
 * @param bloomColor phosphor colour at the very edge of the bloom
 * @param backgroundColor screen background, near black
 * @param phosphorColor bright text and borders
 * @param dimColor idle borders and disabled text
 * @param hoverFillColor button fill under the mouse or focus
 * @param refusalColor the contract terminal's refusal line
 * @param padding space between a frame and what it holds
 * @param lineSpacing space between wrapped text lines, on top of the font height
 * @param buttonLabelOffset pixels the button label sits below the centre, which looks level with the glow
 * @param headerRuleInset pixels the rule under a screen's title reaches past the margins on each side
 * @param headerRuleGap pixels between the title and the rule under it
 */
public record CrtTuning(
		double lettersPerSecond,
		int cursorBlinkTicks,
		int scanlineSpacing,
		int scanlineColor,
		int glowColor,
		int bloomHeight,
		int bloomColor,
		int backgroundColor,
		int phosphorColor,
		int dimColor,
		int hoverFillColor,
		int refusalColor,
		int padding,
		int lineSpacing,
		int buttonLabelOffset,
		int headerRuleInset,
		int headerRuleGap) {
	public static CrtTuning current() {
		return UiTheme.current().crt();
	}

	public static CrtTuning of(ThemeData d) {
		return new CrtTuning(d.decimal("lettersPerSecond", 0.1, 1000), d.integer("cursorBlinkTicks", 1), d.integer("scanlineSpacing", 1), d.color("scanlineColor"),
				d.color("glowColor"), d.integer("bloomHeight", 0), d.color("bloomColor"), d.color("backgroundColor"), d.color("phosphorColor"),
				d.color("dimColor"), d.color("hoverFillColor"), d.color("refusalColor"), d.integer("padding", 0), d.integer("lineSpacing", 0),
				d.integer("buttonLabelOffset", 0), d.integer("headerRuleInset", 0), d.integer("headerRuleGap", 0));
	}
}
