package io.github.pkeppeler.deepcharter.client.scanner;

import io.github.pkeppeler.deepcharter.client.theme.ScannerLook;
import it.unimi.dsi.fastutil.HashCommon;
import java.util.List;

/**
 * The rock grain of the scanner map (#1272): two quadrants of a rock cell are lightened and the other two darkened by
 * the same levels, in a pattern a hash of the block's place picks. Uniform rock then shows a texture that moves past as
 * the pod digs, and a block looks the same in every frame and from wherever the pod is. The cell's mean colour, which
 * the eye reads a band of rock or an ore against, does not move: the middle row and column of an odd-sized cell are
 * left as they are, so the four quadrants are the same size, and the amplitude is held to what every channel of the
 * cell can move both ways without clipping.
 */
public final class ScannerGrain {
    /**
     * The patterns, each lightening two quadrants of a cell and darkening the other two: bit {@code q} lightens quadrant
     * {@code q} (0 top left, 1 top right, 2 bottom left, 3 bottom right).
     */
    public static final List<Integer> PATTERNS = List.of(0b0011, 0b1100, 0b0101, 0b1010, 0b1001, 0b0110);

    private ScannerGrain() {}

    /**
     * The pattern of the block whose {@code BlockPos.asLong} is {@code pos}, for a cell {@code pixels} square;
     * {@code 0}, none, for a cell too small to split or a look with {@link ScannerLook#rockGrainLevels()} 0.
     */
    static int pattern(ScannerLook look, long pos, int pixels) {
        if (look.rockGrainLevels() == 0 || pixels < 2) {
            return 0;
        }
        return PATTERNS.get(Math.floorMod(HashCommon.murmurHash3(pos), PATTERNS.size()));
    }

    /**
     * The levels {@code pattern} moves the pixel ({@code dx}, {@code dy}) of a cell {@code pixels} square by, at
     * {@code amplitude}: up or down by it in a quadrant, 0 on the middle row or column of an odd-sized cell.
     */
    public static int levels(int pattern, int amplitude, int dx, int dy, int pixels) {
        int column = half(dx, pixels);
        int row = half(dy, pixels);
        if (pattern == 0 || column < 0 || row < 0) {
            return 0;
        }
        return ((pattern >> (2 * row + column)) & 1) == 1 ? amplitude : -amplitude;
    }

    /**
     * The largest amplitude up to {@code levels} that moves every channel of {@code argb} (the cell's pixels) both ways
     * without leaving 0 to 255, so lightening and darkening stay equal.
     */
    public static int amplitude(int levels, int[] argb) {
        int amplitude = levels;
        for (int pixel : argb) {
            for (int bits = 0; bits <= 16; bits += 8) {
                int channel = (pixel >> bits) & 0xFF;
                amplitude = Math.min(amplitude, Math.min(channel, 255 - channel));
            }
        }
        return amplitude;
    }

    /** 0 for the first half of {@code pixels}, 1 for the second, -1 for the middle one of an odd count. */
    private static int half(int at, int pixels) {
        if (at < pixels / 2) {
            return 0;
        }
        return at >= pixels - pixels / 2 ? 1 : -1;
    }
}
