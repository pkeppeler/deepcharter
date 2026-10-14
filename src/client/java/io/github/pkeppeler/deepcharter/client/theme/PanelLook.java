package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.theme.ThemeData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

/**
 * The machine panel round the CRT terminals ({@code theme/panel.json}, #246). The mod ships it on, as Hatch; a resource pack restyles it by
 * key or switches it off ({@code enabled} 0), and then a terminal is the full-screen CRT it was. The panel is drawn with GUI sprites at fixed slots
 * under {@code textures/gui/sprites/panel/}: {@link #FRAME} (a nine-slice over the whole screen with a see-through middle), {@link #GLASS} (a
 * nine-slice over the CRT glass, drawn after the content), the button sprites, and a few decals placed by the numbers below. The terminal
 * font is a font file, {@code font/terminal.json}.
 *
 * @param enabled whether the panel is drawn at all (see {@link #drawn()}: not on a GUI too small to hold the screens)
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
 * @param dimTextColor the dim text of a screen (the ore prices, the repair station's notes) while the panel is drawn: lighter than the plain CRT's dim colour, which the glass and its scanlines swallow
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
        int dimTextColor,
        int buttonAlign,
        int buttonPad,
        int pipSize,
        int pipX,
        int pipY) {
    /**
     * The pixels a label keeps clear of the right edge of its button: the button sprite's 3 pixel border and a row clear of it, which is the
     * 4 pixels {@code buttonPad} leaves on the left.
     */
    public static final int LABEL_MARGIN = 4;
    /** The content a screen needs at the least: the screens were drawn for a 320 x 240 GUI less a 16 pixel margin on each side. */
    public static final int MIN_CONTENT_WIDTH = 288;

    public static final int MIN_CONTENT_HEIGHT = 192;
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
            return new Insets(
                    d.integer(prefix + "Left", 0, 200),
                    d.integer(prefix + "Top", 0, 200),
                    d.integer(prefix + "Right", 0, 200),
                    d.integer(prefix + "Bottom", 0, 200));
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
            return new Decal(
                    d.integer(prefix + "W", 0, 1024),
                    d.integer(prefix + "H", 0, 1024),
                    d.integer(prefix + "AnchorX", 0, 2),
                    d.integer(prefix + "AnchorY", 0, 2),
                    d.integer(prefix + "X", -1024, 1024),
                    d.integer(prefix + "Y", -1024, 1024));
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

    /**
     * Whether the panel can be drawn on a GUI {@code guiWidth} by {@code guiHeight}: it is on, and what is left inside its insets holds the screens
     * ({@link #MIN_CONTENT_WIDTH} by {@link #MIN_CONTENT_HEIGHT}). A smaller GUI (the game's own minimum is 320 by 240) draws the plain CRT, in the game's font.
     */
    public boolean fits(int guiWidth, int guiHeight) {
        return enabled
                && guiWidth - content.left() - content.right() >= MIN_CONTENT_WIDTH
                && guiHeight - content.top() - content.bottom() >= MIN_CONTENT_HEIGHT;
    }

    /**
     * Whether the panel is drawn now: a {@link PanelScreen} is open and the panel {@link #fits} its size. Every terminal screen and button asks this,
     * never {@link #enabled()}. The HUD, with no screen open, and any other screen are not in the panel: they keep the game's font.
     */
    public static boolean drawn() {
        Screen screen = Minecraft.getInstance().gui.screen();
        return screen instanceof PanelScreen && current().fits(screen.width, screen.height);
    }

    /** The colour of a screen's dim text: the panel's {@link #dimTextColor} while the panel is drawn, else the CRT's own dim colour. */
    public static int dimText(int crtDim) {
        return drawn() ? current().dimTextColor() : crtDim;
    }

    /** The colour of a button's label for its state. */
    public int labelColor(boolean active, boolean lit) {
        return !active ? buttonLabelOffColor : lit ? buttonLabelHotColor : buttonLabelColor;
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
        return new PanelLook(
                d.integer("enabled", 0, 1) == 1,
                d.color("wallColor"),
                Insets.of(d, "inset"),
                Insets.of(d, "glass"),
                Decal.of(d, "nameplate"),
                Decal.of(d, "dressA"),
                Decal.of(d, "dressB"),
                Decal.of(d, "dressC"),
                Decal.of(d, "dressD"),
                d.color("buttonUnderColor"),
                d.color("buttonLabelColor"),
                d.color("buttonLabelHotColor"),
                d.color("buttonLabelOffColor"),
                d.color("dimTextColor"),
                d.integer("buttonAlign", 0, 1),
                d.integer("buttonPad", 0, 64),
                d.integer("pipSize", 0, 64),
                d.integer("pipX", 0, 256),
                d.integer("pipY", -32, 32));
    }
}
