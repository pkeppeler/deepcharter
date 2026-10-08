package io.github.pkeppeler.deepcharter.client.handbook;

import io.github.pkeppeler.deepcharter.client.theme.UiTheme;
import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * The look of the handbook screen, read as {@code HandbookScreenTuning.current()} from {@code theme/handbook.json} (ADR 0032). Colours are
 * ARGB, lengths are GUI pixels, alphas run 0 to 255. Read it on every draw and never keep it: a resource reload swaps in a new one.
 *
 * @param paperWidth     width of the sheet, margin column included, shrunk to fit a small window
 * @param paperMaxHeight tallest the sheet grows
 * @param bindingWidth   the dark band down the left edge
 * @param padding        space between the sheet edge and its text
 * @param marginWidth    width of the column of miners' notes on the right of the sheet, left out when the sheet is too narrow
 * @param tabWidth       width of one tab
 * @param tabHeight      height of one tab
 * @param buttonWidth    width of the Back and Next buttons
 * @param buttonHeight   height of the Back and Next buttons
 * @param flipTicks      how long a page flip takes
 * @param shadowOffset   how far the sheet's drop shadow sits down and right of it
 * @param ruleSpacing    distance between the faint ruled lines
 * @param ruleAlpha      opacity of a ruled line, in the paper's edge colour
 * @param marginRuleAlpha opacity of the pencil line beside a margin note, in the margin ink
 * @param topMargin      space between the top of the sheet and a page's first line
 * @param contentTop     half the distance from the top of the sheet to the first margin note
 * @param paragraphGap   space between paragraphs
 * @param marginGap      space between the text column and the margin column
 * @param shadowColor    the sheet's drop shadow
 * @param paperColor     the sheet
 * @param paperEdgeColor the sheet's outline and its ruled lines
 * @param bindingColor   the binding band
 * @param inkColor       the employer's ink
 * @param marginInkColor a previous miner's pencil
 * @param stampColor     rubber stamps
 * @param redactionColor the black bar over a redacted word
 * @param faintInkColor  page numbers and other quiet print
 * @param hoverFillColor a tab or button under the mouse or focus
 * @param disabledInkColor the label of a button that cannot be pressed
 */
public record HandbookScreenTuning(
		int paperWidth,
		int paperMaxHeight,
		int bindingWidth,
		int padding,
		int marginWidth,
		int tabWidth,
		int tabHeight,
		int buttonWidth,
		int buttonHeight,
		int flipTicks,
		int shadowOffset,
		int ruleSpacing,
		int ruleAlpha,
		int marginRuleAlpha,
		int topMargin,
		int contentTop,
		int paragraphGap,
		int marginGap,
		int shadowColor,
		int paperColor,
		int paperEdgeColor,
		int bindingColor,
		int inkColor,
		int marginInkColor,
		int stampColor,
		int redactionColor,
		int faintInkColor,
		int hoverFillColor,
		int disabledInkColor) {
	public HandbookScreenTuning {
		if (flipTicks < 1) {
			throw new IllegalArgumentException("flipTicks must be at least 1, got " + flipTicks);
		}
	}

	public static HandbookScreenTuning current() {
		return UiTheme.current().handbook();
	}

	public static HandbookScreenTuning of(ThemeData d) {
		return new HandbookScreenTuning(d.integer("paperWidth", 1), d.integer("paperMaxHeight", 100), d.integer("bindingWidth", 0),
				d.integer("padding", 0), d.integer("marginWidth", 0), d.integer("tabWidth", 1), d.integer("tabHeight", 1),
				d.integer("buttonWidth", 1), d.integer("buttonHeight", 1), d.integer("flipTicks", 1), d.integer("shadowOffset", 0),
				d.integer("ruleSpacing", 1), alpha(d, "ruleAlpha"), alpha(d, "marginRuleAlpha"), d.integer("topMargin", 0),
				d.integer("contentTop", 0), d.integer("paragraphGap", 0), d.integer("marginGap", 0), d.color("shadowColor"),
				d.color("paperColor"), d.color("paperEdgeColor"), d.color("bindingColor"), d.color("inkColor"), d.color("marginInkColor"),
				d.color("stampColor"), d.color("redactionColor"), d.color("faintInkColor"), d.color("hoverFillColor"),
				d.color("disabledInkColor"));
	}

	private static int alpha(ThemeData d, String key) {
		int alpha = d.integer(key, 0);
		if (alpha > 255) {
			throw new IllegalArgumentException("theme area 'handbook', key '" + key + "' must be at most 255, got " + alpha);
		}
		return alpha;
	}
}
