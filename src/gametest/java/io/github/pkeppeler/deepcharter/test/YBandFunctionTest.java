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
import io.github.pkeppeler.deepcharter.layer.gen.NarrowCaveFunction;
import io.github.pkeppeler.deepcharter.layer.gen.YBandFunction;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The layer 1 caves evaluate their terms only in their own rows ({@link YBandFunction}, #1367) and must stay the same
 * density, float for float, as the vanilla expression they replaced ({@code test/claim_caves_vanilla}), above all at
 * the band edges, where a volume is cut in two. Volumes cross every edge {@code claim_caves} uses: block grids that
 * start in the cell under the edge and on it, stepped volumes whose rows land on the edge and step over it (the two
 * paths of the stepped case), the interpolation corners' grid, single points either side, and a whole column of the
 * campaign world. A band over an interpolation must start on its cell grid, or the cell the edge cuts is interpolated
 * from another start; the shipped bands are checked for it. The caves are 0 near most edges, so a band's own rows are
 * also checked on test bands against their {@code range_choice}. Run red: a band one row too tall, a sub-volume one row
 * off, a stepped volume sampled from its first row in the band, a band cutting a cell, and an outside value of
 * {@code 0.0} for the caves' {@code -0.0}.
 */
public class YBandFunctionTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(YBandFunctionTest.class);
    /** Every {@code min_inclusive} and {@code max_exclusive} of a {@code y_band} in {@code claim_caves.json}. */
    private static final List<Integer> EDGES = List.of(-248, -240, -96, 24);

    /**
     * Test bands with their edges, each beside its {@code range_choice} as {@code <name>_vanilla}: one off the cell grid
     * over an input that is never constant, and one over an interpolation, whose stepped volumes cross its bottom edge.
     */
    private static final List<TestBand> TEST_BANDS =
            List.of(new TestBand("test/y_band_odd", -37, 29), new TestBand("test/y_band_interpolated", -40, 29));

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

    /** A band is its input in its rows and its constant elsewhere, whatever the volume's start and step. */
    @GameTest
    public void aBandIsItsInputInsideAndItsConstantOutsideOnEveryPath(GameTestHelper helper) {
        ServerLevel level = campaign(helper);
        DensitySamplerSet samplers = samplers(level);
        int[] tally = new int[2];
        for (TestBand test : TEST_BANDS) {
            DensityFunction band = function(level, NarrowCaveFunctionTest.key(test.path()));
            DensityFunction vanilla = function(level, NarrowCaveFunctionTest.key(test.path() + "_vanilla"));
            for (int edge : List.of(test.bottom(), test.top())) {
                for (int step = 1; step <= 4; step++) {
                    for (int start = edge - 3 * step; start <= edge + 1; start++) {
                        compare(
                                helper,
                                samplers,
                                band,
                                vanilla,
                                tally,
                                new DensityVolume(3, 6, 2, 5, start, -7, 1 + step % 2, step, 2));
                    }
                }
                for (int y = edge - 1; y <= edge; y++) {
                    float got = samplers.sampleValue(band, 5, y, -7);
                    float want = samplers.sampleValue(vanilla, 5, y, -7);
                    expect(
                            helper,
                            Float.floatToRawIntBits(got) == Float.floatToRawIntBits(want),
                            "%s at y %s is %s, range_choice %s",
                            test.path(),
                            y,
                            got,
                            want);
                }
            }
            // Wholly inside, wholly outside, and both edges in one volume.
            compare(helper, samplers, band, vanilla, tally, new DensityVolume(3, 20, 2, 5, -20, -7));
            compare(helper, samplers, band, vanilla, tally, new DensityVolume(3, 20, 2, 5, 40, -7));
            compare(helper, samplers, band, vanilla, tally, new DensityVolume(3, 90, 2, 5, -50, -7));
        }
        LOGGER.info("[y-band] test bands: {} values compared", tally[0]);
        helper.succeed();
    }

    /**
     * Every shipped band over an interpolation starts on that interpolation's cell grid, so the cell its bottom edge
     * cuts is interpolated from the cell's own bottom, as it is in a volume the band does not cut.
     */
    @GameTest
    public void everyShippedBandOverAnInterpolationStartsOnItsCellGrid(GameTestHelper helper) {
        List<YBandFunction> bands = new ArrayList<>();
        visit(function(campaign(helper), SHIPPED), node -> {
            if (node instanceof YBandFunction band) {
                bands.add(band);
            }
        });
        int checked = 0;
        for (YBandFunction band : bands) {
            List<Integer> cells = new ArrayList<>();
            visit(band.input(), node -> {
                if (node instanceof InterpolatedFunction interpolated) {
                    cells.add(interpolated.cellSizeY());
                } else if (node instanceof NarrowCaveFunction narrow) {
                    cells.add(narrow.cellSizeY());
                }
            });
            for (int cell : cells) {
                expect(
                        helper,
                        Math.floorMod(band.minInclusive(), cell) == 0,
                        "a y_band from %s cuts a cell %s rows tall",
                        band.minInclusive(),
                        cell);
            }
            checked += cells.isEmpty() ? 0 : 1;
        }
        expect(helper, checked >= 3, "only %s of %s bands are over an interpolation", checked, bands.size());
        helper.succeed();
    }

    /** Calls {@code visitor} on {@code root} and every function under it. */
    private static void visit(DensityFunction root, Consumer<DensityFunction> visitor) {
        visitor.accept(root);
        root.rewriteChildren(child -> {
            visit(child, visitor);
            return child;
        });
    }

    private record TestBand(String path, int bottom, int top) {}
}
