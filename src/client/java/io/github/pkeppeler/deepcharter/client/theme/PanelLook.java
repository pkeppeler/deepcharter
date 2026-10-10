package io.github.pkeppeler.deepcharter.client.theme;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * The machine panel round the CRT terminals ({@code theme/panel.json}, #246 concept round). The mod ships it switched off, so a terminal
 * is the full-screen CRT it always was; a resource pack switches it on ({@code enabled} 1) and draws it with GUI sprites at fixed slots
 * under {@code textures/gui/sprites/panel/}: {@link #FRAME} (a nine-slice over the whole screen with a see-through middle), {@link #GLASS} (a
 * nine-slice over the CRT glass, drawn after the content), the button sprites, and a few decals placed by the numbers below. The terminal
 * font is a font file, {@code font/terminal.json}.
 *
 * @param enabled whether the panel is drawn at all
 * @param wallColor what shows behind the frame where it is see-through
 * @param content where a screen puts its text and widgets: the screen's rectangle less these insets
 * @param glass the CRT glass: the screen's rectangle less these insets gets the phosphor backdrop, the glass sprite and the scanlines
 * @param nameplate the Company nameplate
 * @param dressA a decal of dressing: toggles, keys, labels, vents, serial plates
 * @param dressB another decal
 * @param dressC another decal
 * @param dressD another decal
 * @param buttonUnderColor what a button's rectangle is filled with before its sprite, behind any see-through corner
 * @param buttonLabelColor a button label, idle
 * @param buttonLabelHotColor a button label under the mouse or focus
 * @param buttonLabelOffColor the label of a button that cannot be pressed
 * @param buttonAlign 0 centres a button's label, 1 sets it to the left after the pip
 * @param buttonPad pixels between a button's edge (or its pip) and a left-set label
 * @param pipSize the side of the square pip drawn inside a button (a lamp, a toggle lever, a knob), 0 for none
 * @param pipX pixels from a button's left edge to its pip
 * @param pipY pixels the pip sits below the vertical centre of its button, negative for above
 */
public record PanelLook(
		boolean enabled,
		int wallColor,
		Insets content,
		Insets glass,
		Decal nameplate,
		Decal dressA,
		Decal dressB,
		Decal dressC,
		Decal dressD,
		int buttonUnderColor,
		int buttonLabelColor,
		int buttonLabelHotColor,
		int buttonLabelOffColor,
		int buttonAlign,
		int buttonPad,
		int pipSize,
		int pipX,
		int pipY) {
	/** The pixels a label keeps clear of the right edge of its button. */
	public static final int LABEL_MARGIN = 2;
	public static final Identifier FRAME = sprite("panel/frame");
	public static final Identifier GLASS = sprite("panel/glass");
	public static final Identifier NAMEPLATE = sprite("panel/nameplate");
	public static final Identifier DRESS_A = sprite("panel/dress_a");
	public static final Identifier DRESS_B = sprite("panel/dress_b");
	public static final Identifier DRESS_C = sprite("panel/dress_c");
	public static final Identifier DRESS_D = sprite("panel/dress_d");
	public static final Identifier BUTTON = sprite("panel/button");
	public static final Identifier BUTTON_HOT = sprite("panel/button_hover");
	public static final Identifier BUTTON_OFF = sprite("panel/button_off");
	public static final Identifier PIP = sprite("panel/pip");
	public static final Identifier PIP_HOT = sprite("panel/pip_hot");
	public static final Identifier PIP_OFF = sprite("panel/pip_off");

	/** Distances from the four edges of the screen to a rectangle inside it. */
	public record Insets(int left, int top, int right, int bottom) {
		static Insets of(ThemeData d, String prefix) {
			return new Insets(d.integer(prefix + "Left", 0, 200), d.integer(prefix + "Top", 0, 200),
					d.integer(prefix + "Right", 0, 200), d.integer(prefix + "Bottom", 0, 200));
		}
	}

	/**
	 * A sprite of a fixed size put at a corner, an edge or the centre of the screen, moved by an offset. A width of 0 means the slot is off.
	 *
	 * @param anchorX 0 measures {@code x} from the left edge, 1 from the centre, 2 from the right edge (and the decal sits inside it)
	 * @param anchorY 0 measures {@code y} from the top edge, 1 from the centre, 2 from the bottom edge
	 */
	public record Decal(int width, int height, int anchorX, int anchorY, int x, int y) {
		static Decal of(ThemeData d, String prefix) {
			return new Decal(d.integer(prefix + "W", 0, 1024), d.integer(prefix + "H", 0, 1024), d.integer(prefix + "AnchorX", 0, 2),
					d.integer(prefix + "AnchorY", 0, 2), d.integer(prefix + "X", -1024, 1024), d.integer(prefix + "Y", -1024, 1024));
		}

		public boolean on() {
			return width > 0 && height > 0;
		}

		/** The left edge on a screen {@code screenWidth} wide. */
		public int left(int screenWidth) {
			return switch (anchorX) {
				case 0 -> x;
				case 1 -> (screenWidth - width) / 2 + x;
				default -> screenWidth - width - x;
			};
		}

		/** The top edge on a screen {@code screenHeight} high. */
		public int top(int screenHeight) {
			return switch (anchorY) {
				case 0 -> y;
				case 1 -> (screenHeight - height) / 2 + y;
				default -> screenHeight - height - y;
			};
		}
	}

	/**
	 * Whether a button {@code buttonWidth} wide has room for its pip beside a label {@code labelWidth} wide. Only a left-set label has a pip.
	 * A screen draws pips on all of its buttons or on none ({@link io.github.pkeppeler.deepcharter.client.ui.CrtDraw#pipsFit}), so one button
	 * that cannot fit its pip never leaves the others looking odd.
	 */
	public boolean showsPip(int buttonWidth, int labelWidth) {
		return pipSize > 0 && buttonAlign == 1 && pipX + pipSize + buttonPad + labelWidth + LABEL_MARGIN <= buttonWidth;
	}

	/** The x, from a button's left edge, where its label starts; {@code pips} says whether the screen draws pips. */
	public int labelStart(int buttonWidth, int labelWidth, boolean pips) {
		if (buttonAlign == 0) {
			return (buttonWidth - labelWidth) / 2;
		}
		return pips && pipSize > 0 ? pipX + pipSize + buttonPad : buttonPad;
	}

	private static Identifier sprite(String path) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
	}

	public static PanelLook current() {
		return UiTheme.current().panel();
	}

	public static PanelLook of(ThemeData d) {
		return new PanelLook(d.integer("enabled", 0, 1) == 1, d.color("wallColor"), Insets.of(d, "inset"), Insets.of(d, "glass"),
				Decal.of(d, "nameplate"), Decal.of(d, "dressA"), Decal.of(d, "dressB"), Decal.of(d, "dressC"), Decal.of(d, "dressD"),
				d.color("buttonUnderColor"), d.color("buttonLabelColor"), d.color("buttonLabelHotColor"), d.color("buttonLabelOffColor"),
				d.integer("buttonAlign", 0, 1), d.integer("buttonPad", 0, 64), d.integer("pipSize", 0, 64), d.integer("pipX", 0, 256), d.integer("pipY", -32, 32));
	}
}
