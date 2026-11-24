package io.github.pkeppeler.deepcharter.test.support;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;

/**
 * The box in the middle of a still that the sky and water fog tests read, about 16 degrees across and 10 up and down,
 * with its median on each channel.
 */
public final class ScreenBox {
    /** Half the width of the box, in pixels. */
    private static final int HALF_WIDTH = 100;

    /** Half the height of the box, in pixels. */
    private static final int HALF_HEIGHT = 60;

    /** Channel {@code c} (0 red, 1 green, 2 blue) of each pixel, row by row. */
    private final int[][] channels;

    private final int[] median;
    private final int[] max;

    private ScreenBox(int[][] channels) {
        this.channels = channels;
        this.median = new int[3];
        this.max = new int[3];
        for (int c = 0; c < 3; c++) {
            int[] sorted = channels[c].clone();
            Arrays.sort(sorted);
            median[c] = sorted[sorted.length / 2];
            max[c] = sorted[sorted.length - 1];
        }
    }

    /** Reads the box in the middle of {@code shot}. */
    public static ScreenBox boxMedian(Path shot) {
        BufferedImage image;
        try {
            image = ImageIO.read(shot.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int cx = image.getWidth() / 2;
        int cy = image.getHeight() / 2;
        int[][] channels = new int[3][(2 * HALF_WIDTH) * (2 * HALF_HEIGHT)];
        int i = 0;
        for (int y = cy - HALF_HEIGHT; y < cy + HALF_HEIGHT; y++) {
            for (int x = cx - HALF_WIDTH; x < cx + HALF_WIDTH; x++) {
                int rgb = image.getRGB(x, y);
                channels[0][i] = (rgb >> 16) & 0xFF;
                channels[1][i] = (rgb >> 8) & 0xFF;
                channels[2][i] = rgb & 0xFF;
                i++;
            }
        }
        return new ScreenBox(channels);
    }

    /** How many pixels the box holds. */
    public int count() {
        return channels[0].length;
    }

    /** Channel {@code c} of pixel {@code i}. */
    public int channel(int c, int i) {
        return channels[c][i];
    }

    /** The median of channel {@code c}. */
    public int median(int c) {
        return median[c];
    }

    /** The median as {@code #rrggbb}. */
    public String medianHex() {
        return hex(median);
    }

    /** The brightest value of each channel, as {@code #rrggbb}. */
    public String maxHex() {
        return hex(max);
    }

    /** {@code rgb} as {@code #rrggbb}. */
    public static String hex(int[] rgb) {
        return "#%02x%02x%02x".formatted(rgb[0], rgb[1], rgb[2]);
    }
}
