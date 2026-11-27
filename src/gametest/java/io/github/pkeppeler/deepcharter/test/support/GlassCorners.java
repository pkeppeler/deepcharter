package io.github.pkeppeler.deepcharter.test.support;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;

/** The glass of a catalogue console has rounded corners and a dark ring at its edge (#984): what must stay clear of them. Shared by the catalogue's and the spec sheet's client tests. */
public final class GlassCorners {
    private GlassCorners() {}

    /**
     * Fails when a row of {@code box} reaches past the rounded corner of {@code glass} ({@link PanelLook#cornerInset}). A positive {@code ring}
     * is the width of the dark ring that dims the glass's edge: inside a corner's rows the box must also keep that far from the sides, and
     * from the top and bottom edges.
     */
    public static void requireClearOfCorners(Rect box, Rect glass, int ring, String what) {
        int radius = PanelLook.current().glassRadius();
        for (int y = box.y(); y < box.bottom(); y++) {
            int row = y - glass.y();
            int inset = Math.max(
                    PanelLook.cornerInset(radius, row), PanelLook.cornerInset(radius, glass.height() - 1 - row));
            boolean inCorner = inset > 0;
            int margin = inCorner ? Math.max(inset, ring) : 0;
            require(
                    box.x() >= glass.x() + margin && box.right() <= glass.right() - margin,
                    what + " " + box + " is cut by the rounded corner of the glass " + glass + " at row " + row
                            + ", where the glass starts " + margin + " pixels in (radius " + radius + ", ring " + ring
                            + ")");
            require(
                    ring == 0 || (row >= ring && glass.height() - row > ring),
                    what + " " + box + " is under the dark ring at the top or bottom of the glass " + glass + " at row "
                            + row + " (ring " + ring + ")");
        }
    }
}
