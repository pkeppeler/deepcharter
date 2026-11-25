package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;

/**
 * Where a spec sheet's console and its content sit on a screen of a given size, in GUI pixels: one console, centred, as large as
 * {@link CatalogueLook#sheetWidth()} wide, as tall as its content wants within {@link CatalogueLook#sheetMinHeight()} and
 * {@link CatalogueLook#sheetHeight()}, and never larger than the window less the screen edge. It is the recipe console's casing and
 * glass with one row of controls under the glass, not two: the Recipes link and the close switch side by side.
 *
 * @param casing the console's casing
 * @param glass the glass of the console
 * @param left where text and the item cell start
 * @param right where text ends
 * @param titleY the title's top
 * @param bodyTop the top of the first block under the title
 * @param bottom where content ends, which nothing may pass
 * @param recipes the Recipes link, a plate with its legend, on the deck under the glass: it opens the item's recipes in the catalogue
 * @param close the close switch, a plate with its legend, on the deck under the glass beside the link
 */
record SpecSheetLayout(
        Rect casing, Rect glass, int left, int right, int titleY, int bodyTop, int bottom, Rect recipes, Rect close) {
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
        int deckY = glass.bottom() + c.deckGap();
        int half = (glass.width() - c.deckGap()) / 2;
        return new SpecSheetLayout(
                casing,
                glass,
                glass.x() + look.pad(),
                glass.right() - look.pad(),
                titleY,
                CatalogueLayout.belowTitle(titleY, look, crt, lineHeight),
                glass.bottom() - look.contentBottom(),
                new Rect(glass.x(), deckY, half, c.deckRow()),
                new Rect(
                        glass.x() + half + c.deckGap(),
                        deckY,
                        glass.right() - glass.x() - half - c.deckGap(),
                        c.deckRow()));
    }
}
