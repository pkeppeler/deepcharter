package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.gen.StrataRule;
import io.github.pkeppeler.deepcharter.layer.gen.StrataRule.Style;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * The transition styles of the strata rule (#1551). Each style's profile, the share of the lower rock against depth,
 * is measured over a square of columns with the rule's own function, so it is deterministic: a gradient's share rises
 * evenly from 0 to 1, a sharp contact has its thin bed, interbedding thickens its bands, a dithered row has no bands
 * of its own, and a column reads its noise once whatever the style.
 */
public class StrataTransitionsTest {
    private static final BlockState UPPER = LayerBlocks.HARDPAN.defaultBlockState();
    private static final BlockState LOWER = LayerBlocks.PACKED_SHALE.defaultBlockState();
    private static final ResourceKey<NormalNoise> NOISE =
            ResourceKey.create(Registries.NOISE, Identifier.fromNamespaceAndPath("deepcharter", "claim_contact"));
    /** Columns on a side of the square a share is measured over: about 4,000, so a share is within 0.05 of its chance. */
    private static final int SIDE = 64;

    private static final double SHARE_TOLERANCE = 0.05;
    private static final int LONG = 64;
    private static final int SHORT = 16;
    /** Four whole cycles of the interbedding's six rows, so each band is whole. */
    private static final int INTERBEDS = 25;

    private static final int CYCLE = 6;
    /** Rows of layer 1, which the cost check walks. */
    private static final int LAYER_ROWS = 192;

    private static StrataRule rule(Style style, int length) {
        return new StrataRule(List.of(new StrataRule.Contact(0, UPPER, style, length)), LOWER, NOISE, 0, 0);
    }

    /** The share of the lower rock in row {@code y} over the square, in columns whose contact is moved by {@code shift}. */
    private static double lowerShare(StrataRule rule, int y, int shift) {
        int lower = 0;
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                lower += rule.rockAt(x, y, z, shift) == LOWER ? 1 : 0;
            }
        }
        return (double) lower / (SIDE * SIDE);
    }

    @GameTest
    public void aGradientsLowerRockShareRisesEvenlyWithDepth(GameTestHelper helper) {
        for (StrataRule rule : List.of(rule(Style.LONG_GRADIENT, LONG), rule(Style.SHORT_GRADIENT, SHORT))) {
            int length = rule.contacts().getFirst().length();
            int top = length / 2;
            double previous = 0;
            for (int y = top + 3; y >= -top - 3; y--) {
                double expected = Math.clamp((double) (top - y) / length, 0, 1);
                double share = lowerShare(rule, y, 0);
                expect(
                        helper,
                        Math.abs(share - expected) <= SHARE_TOLERANCE,
                        "%s rows: the lower rock's share at y=%s is %s, the straight line says %s",
                        length,
                        y,
                        share,
                        expected);
                // The lower rock never gives way again, but for the chance of the dither over a row.
                expect(
                        helper,
                        share + SHARE_TOLERANCE >= previous,
                        "%s rows: the share falls from %s to %s at y=%s",
                        length,
                        previous,
                        share,
                        y);
                previous = share;
            }
            expect(helper, lowerShare(rule, top + 3, 0) == 0, "%s rows: the lower rock is over the gradient", length);
            expect(helper, lowerShare(rule, -top - 3, 0) == 1, "%s rows: the rock under it is not all lower", length);
        }
        helper.succeed();
    }

    @GameTest
    public void aGradientMovesWithTheColumnsShift(GameTestHelper helper) {
        StrataRule rule = rule(Style.LONG_GRADIENT, LONG);
        double centre = lowerShare(rule, 0, 0);
        double moved = lowerShare(rule, 8, 8);
        expect(
                helper,
                Math.abs(centre - moved) <= SHARE_TOLERANCE,
                "a column moved up 8 rows has at 8 rows over it the share of an unmoved one at 0: %s and %s",
                moved,
                centre);
        helper.succeed();
    }

    @GameTest
    public void aSharpContactHasAThinBedOfTheLowerRockOverIt(GameTestHelper helper) {
        StrataRule rule = rule(Style.SHARP, 0);
        for (int y = 10; y >= -10; y--) {
            double expected = y < 0 || y == 2 || y == 3 ? 1 : 0;
            double share = lowerShare(rule, y, 0);
            expect(
                    helper,
                    share == expected,
                    "sharp: the lower rock's share at y=%s is %s, not %s",
                    y,
                    share,
                    expected);
        }
        helper.succeed();
    }

    @GameTest
    public void interbeddingThickensItsBandsOfTheLowerRockWithDepth(GameTestHelper helper) {
        StrataRule rule = rule(Style.INTERBEDDED, INTERBEDS);
        int top = INTERBEDS / 2;
        int previous = 0;
        for (int first = top - 1; first > top - INTERBEDS; first -= CYCLE) {
            int lowerRows = 0;
            for (int y = first; y > first - CYCLE; y--) {
                double share = lowerShare(rule, y, 0);
                expect(helper, share == 0 || share == 1, "interbedded: a row is part both rocks at y=%s: %s", y, share);
                lowerRows += share == 1 ? 1 : 0;
            }
            expect(
                    helper,
                    lowerRows == previous + 1,
                    "interbedded: the band from y=%s is %s rows after one of %s",
                    first,
                    lowerRows,
                    previous);
            previous = lowerRows;
        }
        expect(helper, lowerShare(rule, top, 0) == 0, "interbedded: the row the transition starts under is not upper");
        expect(helper, lowerShare(rule, top - INTERBEDS, 0) == 1, "interbedded: the rock under it is not all lower");
        helper.succeed();
    }

    /** The dither is a hash of the position: no row of the middle of a gradient is in stripes. */
    @GameTest
    public void aDitheredRowHasNoBands(GameTestHelper helper) {
        StrataRule rule = rule(Style.LONG_GRADIENT, LONG);
        int half = SIDE / 2;
        for (int stripe = 0; stripe < SIDE; stripe++) {
            int alongX = 0;
            int alongZ = 0;
            for (int along = 0; along < SIDE; along++) {
                alongX += rule.rockAt(stripe, 0, along, 0) == LOWER ? 1 : 0;
                alongZ += rule.rockAt(along, 0, stripe, 0) == LOWER ? 1 : 0;
            }
            expect(
                    helper,
                    Math.abs(alongX - half) <= SIDE / 4 && Math.abs(alongZ - half) <= SIDE / 4,
                    "the middle row of a gradient is banded at %s: %s and %s of %s are lower rock",
                    stripe,
                    alongX,
                    alongZ,
                    SIDE);
        }
        helper.succeed();
    }

    /** What the prefill relies on: a row it keeps one guess for is the same in every column. */
    @GameTest
    public void aRowOutsideEveryGradientIsOneRockInEveryColumn(GameTestHelper helper) {
        StrataRule rule = rule(Style.LONG_GRADIENT, LONG);
        for (int y = LONG; y >= -LONG; y--) {
            BlockState uniform = rule.uniformAt(y);
            boolean inside = Math.abs(y) < LONG / 2;
            expect(helper, (uniform == null) == inside, "y=%s: the guess is %s", y, uniform);
            if (uniform != null) {
                expect(
                        helper,
                        lowerShare(rule, y, 0) == (uniform == LOWER ? 1 : 0),
                        "y=%s: the rock differs by column",
                        y);
            }
        }
        helper.succeed();
    }

    /**
     * The cost of a style: a column reads the noise and the surface depth once, however many blocks ask, so a gradient
     * costs one position hash a block and no sampling beyond what the strata cost before. (The timing against ADR 0086's
     * bar is {@code CampaignCostTest}'s, which generates the shipped rules.)
     */
    @GameTest
    public void aColumnReadsItsNoiseOnceWhateverTheStyle(GameTestHelper helper) {
        int columns = 8;
        for (Style style : EnumSet.allOf(Style.class)) {
            StrataRule rule = rule(style, style.minLength());
            AtomicInteger noiseReads = new AtomicInteger();
            AtomicInteger depthReads = new AtomicInteger();
            RuleEvaluator evaluator = rule.evaluator(
                    () -> {
                        noiseReads.incrementAndGet();
                        return 0.0;
                    },
                    () -> {
                        depthReads.incrementAndGet();
                        return 0;
                    });
            for (int x = 0; x < columns; x++) {
                for (int z = 0; z < columns; z++) {
                    for (int y = LAYER_ROWS; y >= 0; y--) {
                        evaluator.tryApply(x, y, z);
                    }
                }
            }
            expect(
                    helper,
                    noiseReads.get() == columns * columns && depthReads.get() == columns * columns,
                    "%s read the noise %s times and the surface depth %s for %s columns",
                    style,
                    noiseReads.get(),
                    depthReads.get(),
                    columns * columns);
        }
        helper.succeed();
    }

    @GameTest
    public void aTransitionOfTheWrongLengthOrOverlappingAnotherIsRefused(GameTestHelper helper) {
        for (Style style : EnumSet.allOf(Style.class)) {
            for (int length : new int[] {style.minLength() - 1, style.maxLength() + 1}) {
                boolean refused = false;
                try {
                    new StrataRule.Contact(0, UPPER, style, length);
                } catch (IllegalArgumentException expected) {
                    refused = true;
                }
                expect(helper, refused, "%s of %s rows is not refused", style, length);
            }
        }
        expect(helper, parse(-64).isSuccess(), "two gradients lying end to end are refused");
        expect(helper, parse(-20).isError(), "two gradients overlapping are not refused");
        helper.succeed();
    }

    /** A rule of a long gradient at y 0 and a second one at {@code secondY}. */
    private static DataResult<StrataRule> parse(int secondY) {
        String json = """
                {"contacts": [
                  {"y": 0, "upper": "deepcharter:hardpan", "style": "long_gradient", "length": 64},
                  {"y": %d, "upper": "deepcharter:packed_shale", "style": "long_gradient", "length": 64}],
                 "bottom": "deepcharter:black_shale", "noise": "deepcharter:claim_contact",
                 "amplitude": 0, "surface_depth_multiplier": 0}
                """.formatted(secondY);
        return StrataRule.CODEC.codec().parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }

    /** Layer 1's two zone contacts are long gradients, and at least three styles are in the shipped world. */
    @GameTest
    public void theShippedWorldUsesAtLeastThreeStylesAndLayerOneIsLong(GameTestHelper helper) {
        Set<Style> used = EnumSet.noneOf(Style.class);
        for (String path : List.of("claim_strata", "basalt_top_strata")) {
            StrataRule shipped = (StrataRule) helper.getLevel()
                    .registryAccess()
                    .lookupOrThrow(Registries.MATERIAL_RULE)
                    .getValueOrThrow(ResourceKey.create(
                            Registries.MATERIAL_RULE, Identifier.fromNamespaceAndPath("deepcharter", path)));
            shipped.contacts().forEach(contact -> used.add(contact.style()));
            if (path.equals("claim_strata")) {
                expect(
                        helper,
                        shipped.contacts().get(0).style() == Style.LONG_GRADIENT
                                && shipped.contacts().get(1).style() == Style.LONG_GRADIENT,
                        "layer 1's zone contacts are %s and %s",
                        shipped.contacts().get(0).style(),
                        shipped.contacts().get(1).style());
            }
        }
        expect(helper, used.size() >= 3, "the shipped world uses %s", used);
        helper.succeed();
    }
}
