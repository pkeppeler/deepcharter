package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;

/**
 * Where a spec sheet's console and its content sit on a screen of a given size, in GUI pixels: one console, centred, as large as
 * {@link CatalogueLook#sheetWidth()} wide, as tall as its content wants within {@link CatalogueLook#sheetMinHeight()} and
 * {@link CatalogueLook#sheetHeight()}, and never larger than the window less the screen edge. It is the recipe console's casing and glass (one row of controls under the glass, not two), so a sheet looks like the console
 * it was opened from.
 *
 * @param casing the console's casing
 * @param glass the glass of the console
 * @param left where text and the item cell start
 * @param right where text ends
 * @param titleY the title's top
 * @param bodyTop the top of the first block under the title
 * @param bottom where content ends, which nothing may pass
 * @param close the close switch, a plate with its legend, on the deck under the glass
 */
record SpecSheetLayout(Rect casing, Rect glass, int left, int right, int titleY, int bodyTop, int bottom, Rect close) {
    /** What a console is as tall as, apart from its glass: the top bezel and the deck under the glass. */
    static int chrome(CatalogueLook look) {
        CatalogueLook.Casing c = look.casing();
        return c.topBezel() + c.deckGap() + c.deckRow() + c.bottomBezel();
    }

    /**
     * The layout on a screen {@code width} by {@code height} for a console whose glass should be {@code wantedGlass} tall, which
     * the theme's least and most height and the window clamp.
     */
    static SpecSheetLayout of(
            int width, int height, CatalogueLook look, CrtTuning crt, int lineHeight, int wantedGlass) {
        CatalogueLook.Casing c = look.casing();
        int casingWidth = Math.min(look.sheetWidth(), width - 2 * look.screenEdge());
        int wanted = Math.clamp(wantedGlass + chrome(look), look.sheetMinHeight(), look.sheetHeight());
        int casingHeight = Math.min(wanted, height - 2 * look.screenEdge());
        Rect casing = new Rect((width - casingWidth) / 2, (height - casingHeight) / 2, casingWidth, casingHeight);
        Rect glass = CatalogueLayout.glass(casing, look, c.deckGap() + c.deckRow() + c.bottomBezel());
        int titleY = glass.y() + look.titleTop();
        return new SpecSheetLayout(
                casing,
                glass,
                glass.x() + look.pad(),
                glass.right() - look.pad(),
                titleY,
                CatalogueLayout.belowTitle(titleY, look, crt, lineHeight),
                glass.bottom() - look.contentBottom(),
                new Rect(glass.x(), glass.bottom() + c.deckGap(), glass.width(), c.deckRow()));
    }
}
