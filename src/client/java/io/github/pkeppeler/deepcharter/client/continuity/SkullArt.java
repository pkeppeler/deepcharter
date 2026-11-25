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
 * of the mock-up): {@link SkullShape} as a mask over a grid of the terminal font's letters, which cycle through the Company's line; the
 * bones a shade dimmer; a faint outline; a glow; rows torn sideways, harder the louder the laugh; letters that shuffle on loud frames; a
 * rolling band; and an arrival that tears in from static. The skull's grid is worked out once per jaw position and screen size.
 */
final class SkullArt {
    /** Jaw positions the mask is worked out for; the laugh's loudness picks the nearest. */
    private static final int JAW_STEPS = 16;
    /** The static the skull arrives from, one letter a cell. */
    private static final String STATIC = ".:;'`,-~";
    /** The brightest the laugh makes the picture, on top of its flicker. */
    private static final double LOUD_GAIN = 0.06;

    /** One jaw position's grid: what each cell is ({@link SkullShape#SKULL} and the like), and the outline's pixels. */
    @SuppressWarnings("ArrayRecordComponent") // built once per jaw position and only read; never compared
    private record Grid(int cols, int rows, byte[] cells, int[] skullOutline, int[] boneOutline) {}

    private final RandomSource random = RandomSource.create();
    private final Map<Integer, Grid> grids = new HashMap<>();
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
        float scale = cellHeight / font.lineHeight;
        float cellWidth = Math.max(1f, CrtText.width(font, "M") * scale);
        Grid grid = grid(size, cellWidth, look.rows(), Math.round(loudness * JAW_STEPS));

        double brightness = Math.clamp(
                life * (1 + look.flicker() * (random.nextDouble() * 2 - 1) + LOUD_GAIN * loudness), 0.0, 1.0);
        int skull = dimmed(crt.phosphorColor(), brightness);
        int bone = dimmed(look.boneColor(), brightness);
        int glow = dimmed(crt.glowColor(), brightness);
        int outline = dimmed(look.outlineColor(), brightness);
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
        char[] skullRow = new char[grid.cols()];
        char[] boneRow = new char[grid.cols()];
        for (int row = 0; row < grid.rows(); row++) {
            float y = top + row * cellHeight;
            if (arrival < 1 && random.nextDouble() >= arrival) {
                char[] noise = new char[staticCols];
                for (int c = 0; c < staticCols; c++) {
                    noise[c] = STATIC.charAt(random.nextInt(STATIC.length()));
                }
                row(graphics, font, new String(noise), 0, y, scale, dimmed(look.staticColor(), random.nextDouble()), 0);
                continue;
            }
            boolean any = false;
            for (int col = 0; col < grid.cols(); col++) {
                byte cell = grid.cells()[row * grid.cols() + col];
                char letter = text.charAt((row * grid.cols() + col + shuffle) % text.length());
                skullRow[col] = cell == SkullShape.SKULL ? letter : ' ';
                boneRow[col] = cell == SkullShape.BONE ? letter : ' ';
                any |= cell != SkullShape.NONE;
            }
            if (any) {
                row(graphics, font, new String(boneRow), left + tear[row], y, scale, bone, glow);
                row(graphics, font, new String(skullRow), left + tear[row], y, scale, skull, glow);
            }
        }
        band(graphics, width, height, since, look);
    }

    /** A row of letters at ({@code x}, {@code y}), scaled, with a halo one pixel out in each direction when {@code glow} is not 0. */
    private static void row(
            GuiGraphicsExtractor graphics,
            Font font,
            String letters,
            float x,
            float y,
            float scale,
            int color,
            int glow) {
        Component styled = CrtText.terminal(letters);
        if (glow != 0) {
            for (int[] offset : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                at(graphics, font, styled, x + offset[0], y + offset[1], scale, glow);
            }
        }
        at(graphics, font, styled, x, y, scale, color);
    }

    private static void at(
            GuiGraphicsExtractor graphics, Font font, Component text, float x, float y, float scale, int color) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale, scale);
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

    private Grid grid(int size, float cellWidth, int rows, int jawStep) {
        if (size != gridSize || cellWidth != gridCellWidth) {
            grids.clear();
            gridSize = size;
            gridCellWidth = cellWidth;
        }
        return grids.computeIfAbsent(jawStep, step -> build(size, cellWidth, rows, step / (double) JAW_STEPS));
    }

    private static Grid build(int size, float cellWidth, int rows, double jaw) {
        SkullShape shape = new SkullShape(jaw);
        int cols = Math.max(1, (int) (size / cellWidth));
        byte[] cells = new byte[cols * rows];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                cells[row * cols + col] = (byte) shape.cell((col + 0.5) * cellWidth / size, (row + 0.5) / rows);
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
