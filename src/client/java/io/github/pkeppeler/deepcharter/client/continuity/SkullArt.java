package io.github.pkeppeler.deepcharter.client.continuity;

import io.github.pkeppeler.deepcharter.client.theme.Colors;
import io.github.pkeppeler.deepcharter.client.theme.ContinuityLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtText;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

/**
 * Draws the skull of the Company's own words as a CRT shows it (#1337, the user's pick C4, after {@code draw_typographic} and {@code crt}
 * of the mock-up): {@link SkullShape} as a mask over a dense grid of narrow terminal letters, which cycle through the Company's line; the
 * eye sockets, nose and open mouth cut out as dark holes with a margin; the bones a shade dimmer; a faint outline; a soft phosphor bloom
 * under the letters and a halo round them; rows torn sideways, harder the louder the laugh; letters that shuffle on loud frames; a rolling
 * band; and an arrival that tears in from static. The skull's grid is worked out once per jaw position and screen size.
 */
final class SkullArt {
    /** The static the skull arrives from, one letter a cell. */
    private static final String STATIC = ".:;'`,-~";

    /** One jaw position's grid: what each cell is ({@link SkullShape#SKULL} and the like), and the outline's pixels. */
    @SuppressWarnings("ArrayRecordComponent") // built once per jaw position and only read; never compared
    private record Grid(int cols, int rows, byte[] cells, int[] skullOutline, int[] boneOutline) {}

    private final RandomSource random = RandomSource.create();
    private final Map<Integer, Grid> grids = new HashMap<>();
    private @Nullable ContinuityLook gridLook;
    private int gridSize = -1;
    private float gridCellWidth = -1;

    /**
     * Draws a frame.
     *
     * @param since    seconds since the skull appeared
     * @param loudness the laugh's loudness now, 0 to 1
     * @param life     the skull's brightness, 1 while it laughs and down to 0 as it fades
     * @param text     the Company's line, which the letters cycle through
     */
    void draw(
            GuiGraphicsExtractor graphics,
            Font font,
            int width,
            int height,
            double since,
            float loudness,
            float life,
            String text) {
        ContinuityLook look = ContinuityLook.current();
        CrtTuning crt = CrtTuning.current();
        int size = (int) Math.round(height * look.boxFraction());
        int left = (width - size) / 2;
        int top = (height - size) / 2;
        float cellHeight = size / (float) look.rows();
        float cellWidth = Math.max(1f, (float) (cellHeight * look.cellAspect()));
        // The letters are squeezed to their cell: as wide as a cell, and glyphFill cells tall a line.
        float scaleX = cellWidth / Math.max(1, font.width(CrtText.terminal("M")));
        float scaleY = (float) (cellHeight * look.glyphFill() / font.lineHeight);
        Grid grid = grid(look, size, cellWidth, Math.round(loudness * look.jawSteps()));

        double brightness = Math.clamp(
                life * (1 + look.flicker() * (random.nextDouble() * 2 - 1) + look.loudGain() * loudness), 0.0, 1.0);
        int skull = dimmed(crt.phosphorColor(), brightness);
        int bone = dimmed(look.boneColor(), brightness);
        int glow = dimmed(crt.glowColor(), brightness);
        int outline = dimmed(look.outlineColor(), brightness);
        int bloom = dimmed(look.bloomColor(), brightness);
        float halo = (float) look.haloPixels();
        double arrival = look.arrivalSeconds() <= 0 ? 1 : Math.min(1, since / look.arrivalSeconds());

        dots(graphics, grid.skullOutline(), left, top, outline);
        dots(graphics, grid.boneOutline(), left, top, outline);

        int[] tear = new int[grid.rows()];
        int torn = 1 + Math.round(look.tearRows() * loudness);
        for (int i = 0; i < torn; i++) {
            tear[random.nextInt(grid.rows())] =
                    (int) Math.round(random.nextGaussian() * (look.tearPixels() * loudness + 2));
        }
        int shuffle = loudness > look.shuffleLoudness() ? random.nextInt(3) : 0;
        int staticCols = (int) (width / cellWidth) + 1;
        boolean[] arrived = new boolean[grid.rows()];
        for (int row = 0; row < grid.rows(); row++) {
            arrived[row] = arrival >= 1 || random.nextDouble() < arrival;
        }
        if (look.bloomLayers() > 0) {
            for (int row = 0; row < grid.rows(); row++) {
                if (arrived[row]) {
                    bloom(
                            graphics,
                            grid,
                            row,
                            left + tear[row],
                            top + row * cellHeight,
                            cellWidth,
                            cellHeight,
                            look,
                            bloom);
                }
            }
        }
        char[] skullRow = new char[grid.cols()];
        char[] boneRow = new char[grid.cols()];
        int lit = 0;
        for (int row = 0; row < grid.rows(); row++) {
            float y = top + row * cellHeight;
            if (!arrived[row]) {
                char[] noise = new char[staticCols];
                for (int c = 0; c < staticCols; c++) {
                    noise[c] = STATIC.charAt(random.nextInt(STATIC.length()));
                }
                row(
                        graphics,
                        font,
                        new String(noise),
                        0,
                        y,
                        scaleX,
                        scaleY,
                        dimmed(look.staticColor(), random.nextDouble()),
                        0,
                        0f);
                continue;
            }
            boolean any = false;
            for (int col = 0; col < grid.cols(); col++) {
                byte cell = grid.cells()[row * grid.cols() + col];
                // The line runs on through the lit cells only, so it reads on from row to row and never lines up in
                // columns.
                char letter = cell == SkullShape.NONE ? ' ' : text.charAt((lit++ + shuffle) % text.length());
                skullRow[col] = cell == SkullShape.SKULL ? letter : ' ';
                boneRow[col] = cell == SkullShape.BONE ? letter : ' ';
                any |= cell != SkullShape.NONE;
            }
            if (any) {
                row(graphics, font, new String(boneRow), left + tear[row], y, scaleX, scaleY, bone, glow, halo);
                row(graphics, font, new String(skullRow), left + tear[row], y, scaleX, scaleY, skull, glow, halo);
            }
        }
        band(graphics, width, height, since, look);
    }

    /**
     * The phosphor's bloom under one row: each run of lit cells gets {@code bloomLayers} translucent boxes, each reaching {@code
     * bloomSpread} further out than the last, so the glow is brightest in the mass of letters and fades off its edges, as the mock-up's
     * blurred under-pass does. The holes' margin keeps their middle dark.
     */
    private static void bloom(
            GuiGraphicsExtractor graphics,
            Grid grid,
            int row,
            float left,
            float y,
            float cellWidth,
            float cellHeight,
            ContinuityLook look,
            int color) {
        int cols = grid.cols();
        int col = 0;
        while (col < cols) {
            if (grid.cells()[row * cols + col] == SkullShape.NONE) {
                col++;
                continue;
            }
            int start = col;
            while (col < cols && grid.cells()[row * cols + col] != SkullShape.NONE) {
                col++;
            }
            int x0 = Math.round(left + start * cellWidth);
            int x1 = Math.round(left + col * cellWidth);
            int y0 = Math.round(y);
            int y1 = Math.round(y + cellHeight);
            for (int layer = 1; layer <= look.bloomLayers(); layer++) {
                int reach = layer * look.bloomSpread();
                graphics.fill(x0 - reach, y0 - reach, x1 + reach, y1 + reach, color);
            }
        }
    }

    /** A row of letters at ({@code x}, {@code y}), scaled, with a halo {@code halo} pixels out in each direction when {@code glow} is not 0. */
    private static void row(
            GuiGraphicsExtractor graphics,
            Font font,
            String letters,
            float x,
            float y,
            float scaleX,
            float scaleY,
            int color,
            int glow,
            float halo) {
        Component styled = CrtText.terminal(letters);
        if (glow != 0) {
            for (int[] offset : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                at(graphics, font, styled, x + offset[0] * halo, y + offset[1] * halo, scaleX, scaleY, glow);
            }
        }
        at(graphics, font, styled, x, y, scaleX, scaleY, color);
    }

    private static void at(
            GuiGraphicsExtractor graphics,
            Font font,
            Component text,
            float x,
            float y,
            float scaleX,
            float scaleY,
            int color) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scaleX, scaleY);
        graphics.text(font, text, 0, 0, color, false);
        graphics.pose().popMatrix();
    }

    private static void dots(GuiGraphicsExtractor graphics, int[] pixels, int left, int top, int color) {
        for (int i = 0; i < pixels.length; i += 2) {
            int x = left + pixels[i];
            int y = top + pixels[i + 1];
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }

    /** The rolling band: a soft bright stripe that runs down the screen and round again. */
    private static void band(GuiGraphicsExtractor graphics, int width, int height, double since, ContinuityLook look) {
        int band = look.bandHeight();
        if (band <= 1) {
            return;
        }
        int y = (int) ((since * look.bandSpeed()) % (height + 2 * band)) - band;
        int clear = Colors.rgb(look.bandColor());
        graphics.fillGradient(0, y, width, y + band / 2, clear, look.bandColor());
        graphics.fillGradient(0, y + band / 2, width, y + band, look.bandColor(), clear);
    }

    /** {@code color} with its alpha times {@code brightness}. */
    private static int dimmed(int color, double brightness) {
        return Colors.withAlpha(color, (int) Math.round((color >>> 24) * Math.clamp(brightness, 0.0, 1.0)));
    }

    private Grid grid(ContinuityLook look, int size, float cellWidth, int jawStep) {
        if (look != gridLook || size != gridSize || cellWidth != gridCellWidth) {
            grids.clear();
            gridLook = look;
            gridSize = size;
            gridCellWidth = cellWidth;
        }
        return grids.computeIfAbsent(jawStep, step -> build(look, size, cellWidth, step / (double) look.jawSteps()));
    }

    private static Grid build(ContinuityLook look, int size, float cellWidth, double jaw) {
        SkullShape shape = new SkullShape(jaw);
        int rows = look.rows();
        int cols = Math.max(1, (int) (size / cellWidth));
        double cellX = cellWidth / size;
        double cellY = 1.0 / rows;
        double marginX = cellX * (0.5 + look.holeMargin());
        double marginY = cellY * (0.5 + look.holeMargin());
        byte[] cells = new byte[cols * rows];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                double x = (col + 0.5) * cellX;
                double y = (row + 0.5) * cellY;
                int cell = shape.cell(x, y);
                if (cell == SkullShape.SKULL && nearHole(shape, x, y, marginX, marginY)) {
                    cell = SkullShape.NONE;
                }
                cells[row * cols + col] = (byte) cell;
            }
        }
        PixelList skullOutline = new PixelList();
        for (SkullShape.Line line : shape.skullLines()) {
            trace(line, size, skullOutline, null);
        }
        PixelList boneOutline = new PixelList();
        for (SkullShape.Line line : shape.boneLines()) {
            trace(line, size, boneOutline, shape);
        }
        for (SkullShape.Circle knob : shape.knobs()) {
            int steps = Math.max(8, (int) (2 * Math.PI * knob.r() * size));
            for (int i = 0; i < steps; i++) {
                double a = 2 * Math.PI * i / steps;
                boneOutline.add(knob.x() + knob.r() * Math.cos(a), knob.y() + knob.r() * Math.sin(a), size, shape);
            }
        }
        return new Grid(cols, rows, cells, skullOutline.toArray(), boneOutline.toArray());
    }

    /** Whether a hole of the skull reaches into the box of half-size {@code mx} by {@code my} round ({@code x}, {@code y}). */
    private static boolean nearHole(SkullShape shape, double x, double y, double mx, double my) {
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                if (shape.inHole(x + i * mx, y + j * my)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The pixels of {@code line} at {@code size}, one a pixel of length; with {@code under} set, none the skull covers. */
    private static void trace(SkullShape.Line line, int size, PixelList into, @Nullable SkullShape under) {
        int count = line.xs().length;
        int segments = line.closed() ? count : count - 1;
        for (int i = 0; i < segments; i++) {
            int j = (i + 1) % count;
            double length = Math.hypot(line.xs()[j] - line.xs()[i], line.ys()[j] - line.ys()[i]) * size;
            int steps = Math.max(1, (int) Math.ceil(length));
            for (int s = 0; s <= steps; s++) {
                double t = s / (double) steps;
                into.add(
                        line.xs()[i] + (line.xs()[j] - line.xs()[i]) * t,
                        line.ys()[i] + (line.ys()[j] - line.ys()[i]) * t,
                        size,
                        under);
            }
        }
    }

    /** Pixel pairs (x, y) in the skull's square, each pixel once. */
    private static final class PixelList {
        private final LinkedHashSet<Long> pixels = new LinkedHashSet<>();

        void add(double x, double y, int size, @Nullable SkullShape under) {
            if (under != null && under.coversBones(x, y)) {
                return;
            }
            int px = (int) Math.floor(x * size);
            int py = (int) Math.floor(y * size);
            pixels.add(((long) px << 32) | Integer.toUnsignedLong(py));
        }

        int[] toArray() {
            int[] out = new int[pixels.size() * 2];
            int i = 0;
            for (long pixel : pixels) {
                out[i++] = (int) (pixel >> 32);
                out[i++] = (int) pixel;
            }
            return out;
        }
    }
}
