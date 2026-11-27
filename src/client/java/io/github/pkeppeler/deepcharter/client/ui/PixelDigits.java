package io.github.pkeppeler.deepcharter.client.ui;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook.Digits;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;

/**
 * Text in the cockpit's own pixel digits (#1539), laid out here and drawn by {@link CrtDraw#pixelDigits}: the altimeter's reading, which the vanilla font drew as chunky, shadowed letters that did not match the worn
 * instruments around it. The glyphs are a sheet of white texels ({@code tools/cockpit_ribbon.py} writes it), each blitted a GUI pixel to a texel and tinted, so they stay crisp at
 * every GUI scale. This class holds no client-only import, so a server test can drive it. The sheet holds {@link #WIDE} glyphs, each {@link Digits#width} wide, then {@link #NARROW} ones, each {@link Digits#narrowWidth} wide. A space
 * is blank and as wide as a narrow glyph. A character outside those is refused, because a blank would hide part of a reading the pilot needs.
 */
public final class PixelDigits {
    /** The glyph sheet. */
    static final Identifier SHEET =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "textures/gui/cockpit/digits.png");

    /** The glyphs of the full width, in sheet order. */
    static final String WIDE = "0123456789-ft";

    /** The narrow glyphs, in sheet order after the wide ones. */
    static final String NARROW = ",.";

    private static final char SPACE = ' ';

    private PixelDigits() {}

    /**
     * One glyph placed in a text.
     *
     * @param x its left, from the text's left
     * @param u where its columns start in the sheet
     * @param width how wide it is
     */
    public record Cell(int x, int u, int width) {}

    /** How wide {@code c} is, spaces included; a character with no glyph is refused. */
    private static int glyphWidth(char c, Digits look) {
        if (c == SPACE || NARROW.indexOf(c) >= 0) {
            return look.narrowWidth();
        }
        if (WIDE.indexOf(c) < 0) {
            throw new IllegalArgumentException("the pixel digits have no glyph for '" + c + "'");
        }
        return look.width();
    }

    /** The glyphs of {@code text}, left to right, with the spaces left out. */
    public static List<Cell> layout(String text, Digits look) {
        List<Cell> cells = new ArrayList<>();
        int x = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int width = glyphWidth(c, look);
            if (c != SPACE) {
                int wide = WIDE.indexOf(c);
                int u = wide >= 0
                        ? wide * look.width()
                        : WIDE.length() * look.width() + NARROW.indexOf(c) * look.narrowWidth();
                cells.add(new Cell(x, u, width));
            }
            x += width + look.gap();
        }
        return cells;
    }

    /** How wide {@code text} is: its glyphs and the gaps between them, none after the last. */
    public static int width(String text, Digits look) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += glyphWidth(text.charAt(i), look) + look.gap();
        }
        return Math.max(0, width - look.gap());
    }

    /** The width of the glyph sheet, which its texture must be. */
    static int sheetWidth(Digits look) {
        return WIDE.length() * look.width() + NARROW.length() * look.narrowWidth();
    }
}
