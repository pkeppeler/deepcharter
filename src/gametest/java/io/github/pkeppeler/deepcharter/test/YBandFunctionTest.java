package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.NarrowCaveFunctionTest.CHUNKS;
import static io.github.pkeppeler.deepcharter.test.NarrowCaveFunctionTest.SHIPPED;
import static io.github.pkeppeler.deepcharter.test.NarrowCaveFunctionTest.VANILLA;
import static io.github.pkeppeler.deepcharter.test.NarrowCaveFunctionTest.campaign;
import static io.github.pkeppeler.deepcharter.test.NarrowCaveFunctionTest.compare;
import static io.github.pkeppeler.deepcharter.test.NarrowCaveFunctionTest.function;
import static io.github.pkeppeler.deepcharter.test.NarrowCaveFunctionTest.samplers;
import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.gen.YBandFunction;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The layer 1 caves evaluate their terms only in their own rows ({@link YBandFunction}, #1367) and must stay the same
 * density, float for float, as the vanilla expression they replaced ({@code test/claim_caves_vanilla}), above all at
 * the band edges, where a volume is cut in two. Volumes cross every edge {@code claim_caves} uses: block grids that
 * start in the cell under the edge and on it, stepped volumes whose rows land on the edge and step over it (the two
 * paths of the stepped case), the interpolation corners' grid, single points either side, and a whole column of the
 * campaign world. Run red: a band that takes one row too many, a sub-volume one row off, and an outside value of
 * {@code 0.0} for the {@code -0.0} the caves give over the surface each make values differ.
 */
public class YBandFunctionTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(YBandFunctionTest.class);
    /** Every {@code min_inclusive} and {@code max_exclusive} of a {@code y_band} in {@code claim_caves.json}. */
    private static final List<Integer> EDGES = List.of(-248, -240, -96, 24);

    @GameTest
    public void theBandedCavesAreTheirVanillaExpressionAcrossEveryBandEdge(GameTestHelper helper) {
        ServerLevel level = campaign(helper);
        DensityFunction shipped = function(level, SHIPPED);
        DensityFunction vanilla = function(level, VANILLA);
        expect(
                helper,
                shipped.toString().contains(YBandFunction.class.getSimpleName()),
                "the shipped claim_caves has no y_band, so this compares vanilla with itself: %s",
                shipped);
        DensitySamplerSet samplers = samplers(level);
        int[] tally = new int[2];
        for (int[] chunk : CHUNKS) {
            int x = chunk[0] * 16;
            int z = chunk[1] * 16;
            for (int edge : EDGES) {
                // Starts and ends inside a cell, the edge between.
                compare(helper, samplers, shipped, vanilla, tally, new DensityVolume(16, 37, 16, x, edge - 13, z));
                // Starts on the cell under the edge.
                compare(helper, samplers, shipped, vanilla, tally, new DensityVolume(16, 32, 16, x, edge - 16, z));
                // Every other row, one of them the edge.
                compare(
                        helper,
                        samplers,
                        shipped,
                        vanilla,
                        tally,
                        new DensityVolume(8, 20, 8, x, edge - 10, z, 2, 2, 2));
                // Every third row, stepping over the edge.
                compare(
                        helper,
                        samplers,
                        shipped,
                        vanilla,
                        tally,
                        new DensityVolume(8, 14, 8, x, edge - 11, z, 2, 3, 2));
                // The corners an interpolated function samples.
                compare(
                        helper,
                        samplers,
                        shipped,
                        vanilla,
                        tally,
                        new DensityVolume(5, 6, 5, x, edge - 24, z, 4, 8, 4));
                for (int y = edge - 1; y <= edge + 1; y++) {
                    for (int i = 0; i < 4; i++) {
                        float got = samplers.sampleValue(shipped, x + 3 * i, y, z + 5 * i);
                        float want = samplers.sampleValue(vanilla, x + 3 * i, y, z + 5 * i);
                        expect(
                                helper,
                                Float.floatToRawIntBits(got) == Float.floatToRawIntBits(want),
                                "claim_caves at %s %s %s is %s, vanilla %s",
                                x + 3 * i,
                                y,
                                z + 5 * i,
                                got,
                                want);
                    }
                }
            }
            int bottom = CampaignBands.UNBUILT.minY();
            compare(
                    helper,
                    samplers,
                    shipped,
                    vanilla,
                    tally,
                    new DensityVolume(4, CampaignBands.SURFACE.topExclusive() - bottom, 4, x + 6, bottom, z + 6));
        }
        LOGGER.info("[y-band] {} values compared, {} of them in a cave", tally[0], tally[1]);
        // A control: equal everywhere means nothing if the caves never open near an edge.
        expect(helper, tally[1] > 1000, "only %s of %s values compared are in a cave", tally[1], tally[0]);
        helper.succeed();
    }
}
