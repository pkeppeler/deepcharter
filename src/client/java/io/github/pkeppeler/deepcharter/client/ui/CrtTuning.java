package io.github.pkeppeler.deepcharter.client.ui;

/**
 * Tunables for the CRT UI kit, read as {@code CrtTuning.DEFAULT.thing()}. Colours are ARGB; all lengths are GUI pixels.
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
 * @param padding space between a frame and what it holds
 * @param lineSpacing space between wrapped text lines, on top of the font height
 * @param buttonLabelOffset pixels the button label sits below the centre, which looks level with the glow
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
		int padding,
		int lineSpacing,
		int buttonLabelOffset) {
	public static final CrtTuning DEFAULT = new CrtTuning(
			40, 10, 2, 0x58000000, 0x307CFC9A, 24, 0x2878FF9A,
			0xFF050A06, 0xFF7CFC9A, 0xFF2E7A45, 0xFF123D20, 6, 2, 1);
}
