package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.client.scanner.ScannerGrain;
import java.util.Arrays;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * #1272: the scanner's rock grain moves no cell's mean colour, so a band of rock keeps its contrast with the next
 * and an ore mark with the rock round it. Pure arithmetic, so it runs without a client.
 */
public class ScannerGrainTest {
    private static final int LEVELS = 6;

    /** Every pattern lightens as many pixels as it darkens, by as much, at every cell size, odd ones included. */
    @GameTest
    public void everyPatternSumsToZeroAtEveryCellSize(GameTestHelper helper) {
        for (int pattern : ScannerGrain.PATTERNS) {
            for (int pixels = 2; pixels <= 8; pixels++) {
                int sum = 0;
                int moved = 0;
                for (int dy = 0; dy < pixels; dy++) {
                    for (int dx = 0; dx < pixels; dx++) {
                        int levels = ScannerGrain.levels(pattern, LEVELS, dx, dy, pixels);
                        sum += levels;
                        moved += levels == 0 ? 0 : 1;
                    }
                }
                if (sum != 0) {
                    throw failure(
                            helper,
                            "pattern %s on a %d-pixel cell moves its pixels by %d levels in all, not 0",
                            Integer.toBinaryString(pattern),
                            pixels,
                            sum);
                }
                if (moved == 0) {
                    throw failure(
                            helper,
                            "pattern %s on a %d-pixel cell moves no pixel: no grain shows",
                            Integer.toBinaryString(pattern),
                            pixels);
                }
            }
        }
        helper.succeed();
    }

    /** Near black or white, the amplitude shrinks so that lightening and darkening both fit in 0 to 255. */
    @GameTest
    public void theAmplitudeFitsTheChannelsHeadroom(GameTestHelper helper) {
        int[] grey = new int[4];
        Arrays.fill(grey, 0xFF808080);
        expectAmplitude(helper, "mid grey", grey, LEVELS);
        int[] dark = {0xFF808080, 0xFF020810, 0xFF808080, 0xFF808080};
        expectAmplitude(helper, "a pixel with a red of 2", dark, 2);
        int[] light = {0xFF808080, 0xFF80FD80, 0xFF808080, 0xFF808080};
        expectAmplitude(helper, "a pixel with a green of 253", light, 2);
        int[] black = {0xFF000000, 0xFF808080, 0xFF808080, 0xFF808080};
        expectAmplitude(helper, "a black pixel", black, 0);
        helper.succeed();
    }

    private static void expectAmplitude(GameTestHelper helper, String what, int[] argb, int expected) {
        int amplitude = ScannerGrain.amplitude(LEVELS, argb);
        if (amplitude != expected) {
            throw failure(helper, "a cell with %s should take an amplitude of %d, not %d", what, expected, amplitude);
        }
    }
}
