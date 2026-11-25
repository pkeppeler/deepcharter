package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.theme.Colors;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtText;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * What the catalogue's units are made of: the casing with its groove and rivets, the shadowed lip round the glass, the bulge of the glass
 * (a dark ring at its edge and a curved highlight), vents, knobs and toggle switches (#846). The layout sizes and colours are
 * {@link CatalogueLook.Casing}'s; the glass is lit as the colony terminals' is, by {@link CrtDraw}.
 *
 * <p>A unit is drawn in this order: {@link #body} (casing, lip, phosphor), its content, {@link #glassFront} (the glass over the
 * content), then the controls on the deck. The glass's rounded corners are the shared glass sprite's ({@link PanelLook#GLASS}) and the
 * scanlines stop at those corners ({@link CrtDraw#roundedScanlines}, cut to {@link PanelLook#glassRadius()}), as the shared CRT glass's do.
 */
final class CatalogueHardware {
    private CatalogueHardware() {}

    /** A console's title, a line for each {@code lines} holds, the last of them on the header rule. */
    static void title(GuiGraphicsExtractor graphics, Font font, List<String> lines, int left, int top, int right) {
        int last = lines.size() - 1;
        for (int i = 0; i < last; i++) {
            CrtDraw.glowText(
                    graphics,
                    font,
                    CrtText.terminal(lines.get(i)),
                    left,
                    top + i * font.lineHeight,
                    CrtTuning.current().phosphorColor());
        }
        CrtDraw.header(graphics, font, CrtText.terminal(lines.get(last)), left, top + last * font.lineHeight, right);
    }

    /** The casing of a unit, the lip round its glass, the vents and rivets on the casing, and the phosphor behind the glass's content. */
    static void body(GuiGraphicsExtractor graphics, CatalogueLook look, Rect casing, Rect glass, Iterable<Rect> vents) {
        CatalogueLook.Casing c = look.casing();
        shape(graphics, casing, c.chamfer(), c.casingColor());
        graphics.fill(
                casing.x() + c.chamfer(),
                casing.y(),
                casing.right() - c.chamfer(),
                casing.y() + 1,
                c.casingLightColor());
        graphics.fill(
                casing.x(),
                casing.y() + c.chamfer(),
                casing.x() + 1,
                casing.bottom() - c.chamfer(),
                c.casingLightColor());
        graphics.fill(
                casing.x() + c.chamfer(),
                casing.bottom() - 1,
                casing.right() - c.chamfer(),
                casing.bottom(),
                c.casingDarkColor());
        graphics.fill(
                casing.right() - 1,
                casing.y() + c.chamfer(),
                casing.right(),
                casing.bottom() - c.chamfer(),
                c.casingDarkColor());
        int groove = c.grooveInset();
        CrtDraw.border(
                graphics,
                casing.x() + groove,
                casing.y() + groove,
                casing.right() - groove,
                casing.bottom() - groove,
                c.grooveColor());
        rivets(graphics, c, casing, glass);
        for (Rect vent : vents) {
            vents(graphics, c, vent);
        }
        lip(graphics, c, glass);
        CrtDraw.backdrop(graphics, glass.x(), glass.y(), glass.right(), glass.bottom());
    }

    /** What is over a unit's content: the shared glass sprite, the scanlines and the bulge, inside the glass only. */
    static void glassFront(GuiGraphicsExtractor graphics, CatalogueLook look, PanelLook panel, Rect glass) {
        CatalogueLook.Casing c = look.casing();
        CrtDraw.sprite(graphics, PanelLook.GLASS, glass.x(), glass.y(), glass.width(), glass.height());
        CrtDraw.roundedScanlines(graphics, glass.x(), glass.y(), glass.right(), glass.bottom(), panel.glassRadius());
        int width = c.vignetteWidth();
        for (int i = 0; i < width; i++) {
            int shade = Colors.withAlpha(c.vignetteColor(), (c.vignetteColor() >>> 24) * (width - i) / width);
            graphics.fill(glass.x(), glass.y() + i, glass.right(), glass.y() + i + 1, shade);
            graphics.fill(glass.x(), glass.bottom() - 1 - i, glass.right(), glass.bottom() - i, shade);
            graphics.fill(glass.x() + i, glass.y(), glass.x() + i + 1, glass.bottom(), shade);
            graphics.fill(glass.right() - 1 - i, glass.y(), glass.right() - i, glass.bottom(), shade);
        }
        // The highlight hugs the top left corner, from where the glass sprite's curve (its radius) ends: the glass
        // bulges toward the room's light.
        int x = glass.x();
        int y = glass.y();
        int inset = panel.glassRadius();
        int length = c.highlightLength();
        graphics.fill(x + inset, y + 1, x + inset + length, y + 2, c.highlightColor());
        graphics.fill(x + 1, y + inset, x + 2, y + inset + length, c.highlightColor());
    }

    /** A rectangle with its corners cut by {@code chamfer} pixels. */
    private static void shape(GuiGraphicsExtractor graphics, Rect r, int chamfer, int color) {
        for (int i = 0; i < chamfer; i++) {
            int inset = chamfer - i;
            graphics.fill(r.x() + inset, r.y() + i, r.right() - inset, r.y() + i + 1, color);
            graphics.fill(r.x() + inset, r.bottom() - 1 - i, r.right() - inset, r.bottom() - i, color);
        }
        graphics.fill(r.x(), r.y() + chamfer, r.right(), r.bottom() - chamfer, color);
    }

    /** The lip: a sunken edge round the glass, shadowed above and to the left, lit below and to the right. */
    private static void lip(GuiGraphicsExtractor graphics, CatalogueLook.Casing c, Rect glass) {
        int w = c.lipWidth();
        int left = glass.x() - w;
        int top = glass.y() - w;
        int right = glass.right() + w;
        int bottom = glass.bottom() + w;
        graphics.fill(left, top, right, bottom, c.lipDarkColor());
        graphics.fill(left, bottom - 1, right, bottom, c.lipLightColor());
        graphics.fill(right - 1, top, right, bottom, c.lipLightColor());
    }

    /** A rivet in each corner of the casing, and a column of them down each side bezel at the casing's pitch. */
    private static void rivets(GuiGraphicsExtractor graphics, CatalogueLook.Casing c, Rect casing, Rect glass) {
        int leftX = casing.x() + (c.sideBezel() - 2) / 2;
        int rightX = casing.right() - (c.sideBezel() - 2) / 2 - 2;
        int topY = casing.y() + (c.topBezel() - 2) / 2;
        int bottomY = casing.bottom() - (c.bottomBezel() - 2) / 2 - 2;
        rivet(graphics, c, leftX, topY);
        rivet(graphics, c, rightX, topY);
        rivet(graphics, c, leftX, bottomY);
        rivet(graphics, c, rightX, bottomY);
        for (int y = glass.y() + c.rivetPitch(); y < glass.bottom() - c.rivetPitch() / 2; y += c.rivetPitch()) {
            rivet(graphics, c, leftX, y);
            rivet(graphics, c, rightX, y);
        }
    }

    private static void rivet(GuiGraphicsExtractor graphics, CatalogueLook.Casing c, int x, int y) {
        graphics.fill(x + 1, y + 1, x + 3, y + 3, c.rivetShadowColor());
        graphics.fill(x, y, x + 2, y + 2, c.rivetColor());
    }

    /** A grille of slots in {@code area}: dark slots with a lit lower edge. */
    private static void vents(GuiGraphicsExtractor graphics, CatalogueLook.Casing c, Rect area) {
        for (int slot = 0; slot < c.ventSlots(); slot++) {
            int y = area.y() + slot * CatalogueLook.Casing.VENT_PITCH;
            graphics.fill(area.x(), y, area.right(), y + 2, c.ventColor());
            graphics.fill(area.x(), y + 2, area.right(), y + CatalogueLook.Casing.VENT_PITCH, c.ventLightColor());
        }
    }

    /** A filled disc of diameter {@code size} with its top left at ({@code x}, {@code y}). */
    private static void disc(GuiGraphicsExtractor graphics, int x, int y, int size, int color) {
        double radius = size / 2.0;
        for (int row = 0; row < size; row++) {
            double dy = row + 0.5 - radius;
            double half = Math.sqrt(radius * radius - dy * dy);
            graphics.fill(
                    x + (int) Math.round(radius - half),
                    y + row,
                    x + (int) Math.round(radius + half),
                    y + row + 1,
                    color);
        }
    }

    /** A chunky knob with {@code label} on its face: a rim, a body, a lit edge top left; dull when it does nothing, lit when under the mouse. */
    static void knob(
            GuiGraphicsExtractor graphics,
            Font font,
            CatalogueLook look,
            PanelLook panel,
            CrtTuning crt,
            Rect rect,
            String label,
            boolean active,
            boolean hot) {
        CatalogueLook.Casing c = look.casing();
        disc(graphics, rect.x(), rect.y(), rect.width(), c.knobRimColor());
        disc(graphics, rect.x() + 1, rect.y() + 1, rect.width() - 2, active ? c.knobBodyColor() : c.knobOffColor());
        if (active) {
            graphics.fill(rect.x() + 4, rect.y() + 1, rect.x() + rect.width() / 2, rect.y() + 2, c.knobLightColor());
            graphics.fill(rect.x() + 2, rect.y() + 3, rect.x() + 3, rect.y() + rect.height() / 2, c.knobLightColor());
        }
        if (hot && active) {
            CrtDraw.border(
                    graphics, rect.x() - 1, rect.y() - 1, rect.right() + 1, rect.bottom() + 1, crt.phosphorColor());
        }
        int width = font.width(CrtText.terminal(label));
        int x = rect.x() + (rect.width() - width) / 2;
        int y = rect.y() + (rect.height() - font.lineHeight) / 2 + crt.buttonLabelOffset();
        graphics.text(font, CrtText.terminal(label), x, y, panel.labelColor(active, hot), false);
    }

    /**
     * A toggle switch on a plate, with its legend beside it: the lever sits left when off and right, lit in phosphor, when on. A switch that
     * does nothing now has a dull lever.
     */
    static void legendSwitch(
            GuiGraphicsExtractor graphics,
            Font font,
            CatalogueLook look,
            PanelLook panel,
            CrtTuning crt,
            Rect rect,
            String legend,
            boolean on,
            boolean active,
            boolean hot) {
        CatalogueLook.Casing c = look.casing();
        plate(graphics, c, rect);
        int slotX = rect.x() + c.switchPad();
        int slotY = rect.y() + (rect.height() - c.switchHeight()) / 2;
        graphics.fill(slotX, slotY, slotX + c.switchWidth(), slotY + c.switchHeight(), c.casingDarkColor());
        CrtDraw.border(graphics, slotX, slotY, slotX + c.switchWidth(), slotY + c.switchHeight(), c.casingLightColor());
        int leverWidth = c.switchWidth() / 2 - 1;
        int leverX = on ? slotX + c.switchWidth() - leverWidth - 1 : slotX + 1;
        int lever = !active ? c.leverOffColor() : on ? crt.phosphorColor() : c.leverColor();
        graphics.fill(leverX, slotY + 1, leverX + leverWidth, slotY + c.switchHeight() - 1, lever);
        if (active) {
            graphics.fill(leverX, slotY + 1, leverX + leverWidth, slotY + 2, c.knobLightColor());
        }
        int x = slotX + c.switchWidth() + c.switchPad();
        int y = rect.y() + (rect.height() - font.lineHeight) / 2 + crt.buttonLabelOffset();
        graphics.text(font, CrtText.terminal(legend), x, y, panel.labelColor(active, hot || (on && active)), false);
        if (hot && active) {
            CrtDraw.border(graphics, rect.x(), rect.y(), rect.right(), rect.bottom(), crt.dimColor());
        }
    }

    /** The plate of the page counter, with the page in dim phosphor. */
    static void counter(
            GuiGraphicsExtractor graphics,
            Font font,
            CatalogueLook look,
            PanelLook panel,
            CrtTuning crt,
            Rect rect,
            String text) {
        plate(graphics, look.casing(), rect);
        int x = rect.x() + (rect.width() - font.width(CrtText.terminal(text))) / 2;
        int y = rect.y() + (rect.height() - font.lineHeight) / 2 + crt.buttonLabelOffset();
        graphics.text(font, CrtText.terminal(text), x, y, panel.dimTextColor(), false);
    }

    /** A dark plate set into the casing, shadowed above and to the left and lit below and to the right. */
    private static void plate(GuiGraphicsExtractor graphics, CatalogueLook.Casing c, Rect rect) {
        graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), c.plateColor());
        graphics.fill(rect.x(), rect.bottom() - 1, rect.right(), rect.bottom(), c.casingLightColor());
        graphics.fill(rect.right() - 1, rect.y(), rect.right(), rect.bottom(), c.casingLightColor());
        graphics.fill(rect.x(), rect.y(), rect.right() - 1, rect.y() + 1, c.casingDarkColor());
        graphics.fill(rect.x(), rect.y(), rect.x() + 1, rect.bottom() - 1, c.casingDarkColor());
    }
}
