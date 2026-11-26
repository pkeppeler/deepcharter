package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import static io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueHardware.fit;
import static io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueHardware.termWidth;

import com.google.common.base.Splitter;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtText;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.handbook.SpecSheet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Draws a spec sheet on a {@link SpecSheetLayout}: the recipe console's casing and glass, the item's name as the title, its cell,
 * what it is, then its ratings, in the terminal font and phosphor of the catalogue (#848). The standalone {@link SpecSheetScreen} and the
 * parts catalogue's own overlay both draw it, so a sheet looks the same wherever it opens.
 *
 * <p>The text is planned first ({@link #plan}) as lines at fixed places, and drawn from that plan. A sheet that does not fit its glass
 * is {@linkplain Plan#complete() incomplete}: what does not fit is left out, never drawn past the glass, and the client test fails the
 * sheet of any shipped item that is.
 */
final class SpecSheetView {
    private SpecSheetView() {}

    /** What a click on a placed sheet does. */
    enum Click {
        /** The Recipes link: show the item's recipes in the catalogue. */
        RECIPES,
        /** The close switch, or anywhere off the sheet's console: put the sheet away. */
        CLOSE,
        /** Anywhere else on the console: nothing, so a stray click never loses the sheet. */
        NONE
    }

    /** What a click at ({@code x}, {@code y}) does to a sheet laid out as {@code layout}. */
    static Click clickAt(SpecSheetLayout layout, double x, double y) {
        if (layout.recipes().contains(x, y)) {
            return Click.RECIPES;
        }
        return layout.close().contains(x, y) || !layout.casing().contains(x, y) ? Click.CLOSE : Click.NONE;
    }

    /** How a planned line is drawn. */
    enum Style {
        BRIGHT,
        DIM
    }

    /** A line of text with its top left. */
    record Line(String text, int x, int y, Style style) {}

    /**
     * Everything a sheet draws as text.
     *
     * @param title the item's name, in capitals, a line for each the width takes
     * @param cell the item's cell
     * @param lines the lines under the title
     * @param usedBottom the y under the last of the text
     * @param complete whether every line of the sheet fits
     */
    record Plan(List<String> title, Rect cell, List<Line> lines, int usedBottom, boolean complete) {}

    /** A sheet placed on a screen: the console, and the text on its glass. */
    record Placed(SpecSheetLayout layout, Plan plan) {}

    /** Places {@code sheet} on a screen {@code width} by {@code height}: a console as tall as the text needs, centred. */
    static Placed place(SpecSheet sheet, int width, int height, CatalogueLook look, CrtTuning crt, Font font) {
        // The text is planned once in the tallest console to see how much room it takes, then again in the console that
        // size.
        SpecSheetLayout tallest = SpecSheetLayout.of(width, height, look, crt, font.lineHeight, Integer.MAX_VALUE / 2);
        Plan measured = plan(sheet, tallest, look, font);
        int needed = measured.usedBottom() - tallest.glass().y() + look.pad();
        SpecSheetLayout layout = SpecSheetLayout.of(width, height, look, crt, font.lineHeight, needed);
        return new Placed(layout, plan(sheet, layout, look, font));
    }

    /** Lays the text of {@code sheet} out inside {@code layout}. */
    static Plan plan(SpecSheet sheet, SpecSheetLayout layout, CatalogueLook look, Font font) {
        boolean complete = true;
        int width = layout.right() - layout.left();
        List<String> title = TextWrap.lines(
                text -> termWidth(font, text),
                new ItemStack(sheet.item()).getHoverName().getString().toUpperCase(Locale.ROOT),
                width);
        List<Line> lines = new ArrayList<>();
        int pitch = font.lineHeight + look.sheetLineGap();

        // Each line of the title past the first pushes the body down.
        int bodyTop = layout.bodyTop() + (title.size() - 1) * font.lineHeight;
        Rect cell = new Rect(layout.left(), bodyTop, look.slotSize(), look.slotSize());
        int kindX = cell.right() + look.sheetGap();
        lines.add(new Line(
                fit(font, sheet.kind().getString(), layout.right() - kindX),
                kindX,
                cell.y() + (cell.height() - font.lineHeight) / 2,
                Style.DIM));
        int y = cell.bottom() + look.sheetGap();

        for (String text : wrap(font, sheet.blurb().getString(), width)) {
            if (y + font.lineHeight > layout.bottom()) {
                complete = false;
                break;
            }
            lines.add(new Line(text, layout.left(), y, Style.BRIGHT));
            y += pitch;
        }
        y += look.sheetGap();

        for (SpecSheet.Rating rating : sheet.ratings()) {
            if (y + font.lineHeight > layout.bottom()) {
                complete = false;
                break;
            }
            String value = rating.value().getString();
            String label = rating.label().getString();
            int room = width - termWidth(font, value) - look.sheetValueGap();
            if (termWidth(font, label) > room) {
                complete = false;
                label = fit(font, label, Math.max(0, room));
            }
            lines.add(new Line(label, layout.left(), y, Style.DIM));
            lines.add(new Line(value, layout.right() - termWidth(font, value), y, Style.BRIGHT));
            y += pitch;
        }
        return new Plan(title, cell, List.copyOf(lines), y, complete);
    }

    /** Draws {@code sheet} as {@code plan} laid it out, with the mouse at ({@code mouseX}, {@code mouseY}). */
    static void draw(
            GuiGraphicsExtractor graphics,
            Font font,
            SpecSheetLayout layout,
            SpecSheet sheet,
            Plan plan,
            int mouseX,
            int mouseY,
            boolean recipesActive) {
        CatalogueLook look = CatalogueLook.current();
        CrtTuning crt = CrtTuning.current();
        PanelLook panel = PanelLook.current();
        graphics.nextStratum();
        CatalogueHardware.body(graphics, look, layout.casing(), layout.glass(), List.of());
        graphics.nextStratum();

        CatalogueHardware.title(graphics, font, plan.title(), layout.left(), layout.titleY(), layout.right());
        Rect cell = plan.cell();
        CrtDraw.border(graphics, cell.x(), cell.y(), cell.right(), cell.bottom(), crt.phosphorColor());
        graphics.item(new ItemStack(sheet.item()), cell.x() + look.iconInset(), cell.y() + look.iconInset());
        for (Line line : plan.lines()) {
            Component text = CrtText.terminal(line.text());
            if (line.style() == Style.BRIGHT) {
                CrtDraw.glowText(graphics, font, text, line.x(), line.y(), crt.phosphorColor());
            } else {
                graphics.text(font, text, line.x(), line.y(), panel.dimTextColor(), false);
            }
        }

        graphics.nextStratum();
        CatalogueHardware.glassFront(graphics, look, panel, layout.glass());
        graphics.nextStratum();
        CatalogueHardware.legendSwitch(
                graphics,
                font,
                look,
                panel,
                crt,
                layout.recipes(),
                Component.translatable("deepcharter.catalogue.sheet.recipes").getString(),
                false,
                recipesActive,
                layout.recipes().contains(mouseX, mouseY));
        CatalogueHardware.legendSwitch(
                graphics,
                font,
                look,
                panel,
                crt,
                layout.close(),
                Component.translatable("deepcharter.catalogue.sheet.close").getString(),
                false,
                true,
                layout.close().contains(mouseX, mouseY));
    }

    /** {@code text} broken at spaces into lines no wider than {@code width}; a word wider than that is cut. */
    private static List<String> wrap(Font font, String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : Splitter.on(' ').omitEmptyStrings().split(text)) {
            String joined = line.isEmpty() ? word : line + " " + word;
            if (termWidth(font, joined) <= width) {
                line = new StringBuilder(joined);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
            }
            line = new StringBuilder(fit(font, word, width));
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }
}
