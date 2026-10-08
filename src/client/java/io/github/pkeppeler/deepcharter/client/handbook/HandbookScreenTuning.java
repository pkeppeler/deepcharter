package io.github.pkeppeler.deepcharter.client.handbook;

/**
 * Tunables for the handbook screen, read as {@code HandbookScreenTuning.DEFAULT.thing()}. Colours are ARGB, lengths are GUI pixels.
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
 * @param shadowColor    the sheet's drop shadow
 * @param paperColor     the sheet
 * @param paperEdgeColor the sheet's outline and its ruled lines
 * @param bindingColor   the binding band
 * @param inkColor       the employer's ink
 * @param marginInkColor a previous miner's pencil
 * @param stampColor     rubber stamps
 * @param redactionColor the black bar over a redacted word
 * @param faintInkColor  page numbers and other quiet print
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
		int shadowColor,
		int paperColor,
		int paperEdgeColor,
		int bindingColor,
		int inkColor,
		int marginInkColor,
		int stampColor,
		int redactionColor,
		int faintInkColor) {
	public static final HandbookScreenTuning DEFAULT = new HandbookScreenTuning(
			320, 200, 8, 8, 70, 56, 13, 46, 13, 6,
			0x66000000, 0xFFF1E4C3, 0xFFC9B48A, 0xFF6B2D2D, 0xFF1B2A4E, 0xFF4A6A3A, 0xFFB3261E, 0xFF111111, 0xFF7A7058);

	public HandbookScreenTuning {
		if (flipTicks < 1) {
			throw new IllegalArgumentException("flipTicks must be at least 1, got " + flipTicks);
		}
	}
}
