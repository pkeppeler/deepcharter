package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.RockTint;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.layer.gen.StrataRule;
import io.github.pkeppeler.deepcharter.ore.OreBlock;
import io.github.pkeppeler.deepcharter.ore.OreType;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The colour of the layers' rock by depth (#1551), which the world's block colour and the scanner both use. In layer 1
 * each channel moves in one direction from a rock's home colour to the next one's over a gradient, in small steps, the
 * same for whichever rock a block is (so the dither of the rocks shows nothing). Layers 2 and 3 give each zone a colour
 * of its own, and each of their zone contacts gives way in its style: layer 2 a sharp line with a thin bed, then a
 * short gradient; layer 3 interbedding, then a long gradient. A rock outside its layer's rows, or a block that is not a
 * layer's rock, keeps its own colour.
 */
public class RockTintTest {
    private static final CampaignBands.Band LAYER = CampaignBands.LAYER_1;
    private static final int HALF = LayerBlocks.CLAIM_GRADIENT_LENGTH / 2;
    /** The most a channel may move between two rows: its whole step over a gradient is under 130 over 64 rows. */
    private static final int MAX_STEP = 3;

    /** The most rows the contour noise and the surface depth move a contact, with a margin. */
    private static final int SHIFT = 14;
    /** Layer 2's short gradient and layer 3's interbedding and long gradient, as {@code RockTint} lays them. */
    private static final int SHORT = 16;

    private static final int INTERBEDS = 24;
    private static final int LONG = 64;
    private static final int CYCLE = 6;
    /**
     * The least RGB distance between two neighbouring zones' tints (of 441), so a zone reads as its own colour; the
     * scanner draws the texture times the tint, which scales the distance down by the texture's brightness.
     */
    private static final double ZONE_DISTANCE = 40;

    private static int channel(int rgb, int shift) {
        return (rgb >> shift) & 0xFF;
    }

    private static double distance(int a, int b) {
        double sum = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            double d = channel(a, shift) - channel(b, shift);
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    private static int contact(int zone) {
        return Zones.span(LAYER.minY(), LAYER.height(), zone).low() - LayerBlocks.CLAIM_CONTACT_UNDER_EDGE;
    }

    /** The lower edge of zone {@code zone} of {@code band}, where layers 2 and 3 centre their zone contacts. */
    private static int edge(CampaignBands.Band band, int zone) {
        return Zones.span(band.minY(), band.height(), zone).low();
    }

    /** The middle row of zone {@code zone} of {@code band}. */
    private static int middle(CampaignBands.Band band, int zone) {
        Zones.Span span = Zones.span(band.minY(), band.height(), zone);
        return (span.low() + span.high()) / 2;
    }

    private static CampaignBands.Band band(int layer) {
        return CampaignBands.LAYERS.get(layer - 1);
    }

    private static BlockState state(Block block) {
        return block.defaultBlockState();
    }

    private static BlockState ore(OreBlock.Host host) {
        return BuiltInRegistries.BLOCK
                .getValue(OreType.IRONIUM.blockId())
                .defaultBlockState()
                .setValue(OreBlock.HOST, host);
    }

    /**
     * Every channel of {@code layer}'s colour moves in one direction, at most {@code maxStep} a row, from {@code from}
     * over the gradient's {@code length} rows from {@code top} to {@code to}, and is half way at the centre.
     */
    private static void expectGradient(
            GameTestHelper helper, int layer, int top, int length, int from, int to, int maxStep, String what) {
        expect(helper, RockTint.layerColour(layer, top) == from, "%s: not the upper colour at its top", what);
        expect(helper, RockTint.layerColour(layer, top - length) == to, "%s: not the lower colour at its foot", what);
        int mid = RockTint.layerColour(layer, top - length / 2);
        for (int shift = 0; shift <= 16; shift += 8) {
            int expected = (channel(from, shift) + channel(to, shift) + 1) / 2;
            expect(
                    helper,
                    Math.abs(channel(mid, shift) - expected) <= 1,
                    "%s: the channel at bit %s is %s at the centre, half way is %s",
                    what,
                    shift,
                    channel(mid, shift),
                    expected);
        }
        for (int y = top; y > top - length; y--) {
            int now = RockTint.layerColour(layer, y);
            int next = RockTint.layerColour(layer, y - 1);
            for (int shift = 0; shift <= 16; shift += 8) {
                int step = channel(next, shift) - channel(now, shift);
                int whole = channel(to, shift) - channel(from, shift);
                expect(
                        helper,
                        Math.abs(step) <= maxStep && Integer.signum(step) * Integer.signum(whole) >= 0,
                        "%s: a channel steps %s at y=%s on its way from %s to %s",
                        what,
                        step,
                        y,
                        channel(from, shift),
                        channel(to, shift));
            }
        }
    }

    @GameTest
    public void theColourDriftsFromEachRocksHomeToTheNextOverALongGradient(GameTestHelper helper) {
        int hardpan = RockTint.home(LayerBlocks.HARDPAN);
        int packed = RockTint.home(LayerBlocks.PACKED_SHALE);
        int black = RockTint.home(LayerBlocks.BLACK_SHALE);
        expect(
                helper,
                RockTint.layerColour(1, LAYER.maxY()) == hardpan,
                "the top of layer 1 is not the hardpan's colour");
        expect(helper, RockTint.layerColour(1, LAYER.minY()) == black, "the floor of layer 1 is not the black shale's");
        for (int zone = 0; zone < 2; zone++) {
            expectGradient(
                    helper,
                    1,
                    contact(zone) + HALF,
                    LayerBlocks.CLAIM_GRADIENT_LENGTH,
                    zone == 0 ? hardpan : packed,
                    zone == 0 ? packed : black,
                    MAX_STEP,
                    "layer 1 zone " + zone);
        }
        helper.succeed();
    }

    /**
     * Each zone of layers 2 and 3 has a colour of its own, the first zone's is its rock's home colour, and two
     * neighbouring zones are far enough apart to read as two.
     */
    @GameTest
    public void everyZoneOfLayersTwoAndThreeHasItsOwnColour(GameTestHelper helper) {
        for (int layer = 2; layer <= 3; layer++) {
            CampaignBands.Band band = band(layer);
            Block rock = layer == 2 ? LayerBlocks.GREEN_SHALE : LayerBlocks.BASALT_CORE;
            expect(
                    helper,
                    RockTint.layerColour(layer, middle(band, 0)) == RockTint.home(rock),
                    "layer %s: its top zone is not its rock's home colour",
                    layer);
            for (int zone = 0; zone + 1 < Zones.COUNT; zone++) {
                int upper = RockTint.layerColour(layer, middle(band, zone));
                int lower = RockTint.layerColour(layer, middle(band, zone + 1));
                expect(
                        helper,
                        distance(upper, lower) >= ZONE_DISTANCE,
                        "layer %s: zones %s and %s are %s apart in RGB, under %s",
                        layer,
                        zone,
                        zone + 1,
                        distance(upper, lower),
                        ZONE_DISTANCE);
            }
            expect(
                    helper,
                    RockTint.layerColour(layer, band.maxY()) == RockTint.layerColour(layer, middle(band, 0))
                            && RockTint.layerColour(layer, band.minY()) == RockTint.layerColour(layer, middle(band, 2)),
                    "layer %s: the top or the floor is not its zone's colour",
                    layer);
        }
        helper.succeed();
    }

    /**
     * Layer 2's first zone contact is sharp: the Upper Levels' colour down to its edge, a thin bed of Shift Change's in
     * it just over the edge (the strata rule's own bed rows), and Shift Change's from the edge down.
     */
    @GameTest
    public void layerTwosFirstContactIsASharpLineWithAThinBed(GameTestHelper helper) {
        CampaignBands.Band band = band(2);
        int upper = RockTint.layerColour(2, middle(band, 0));
        int lower = RockTint.layerColour(2, middle(band, 1));
        int centre = edge(band, 0);
        int bedRows = 0;
        for (int y = centre + 12; y >= centre - 12; y--) {
            int above = y - centre;
            int expected = above < 0 || StrataRule.inBed(above) ? lower : upper;
            bedRows += above >= 0 && expected == lower ? 1 : 0;
            expect(
                    helper,
                    RockTint.layerColour(2, y) == expected,
                    "layer 2's sharp contact: y=%s, %s rows over the line, is not the %s colour",
                    y,
                    above,
                    expected == lower ? "lower" : "upper");
        }
        expect(helper, bedRows > 0, "layer 2's sharp contact has no bed");
        helper.succeed();
    }

    @GameTest
    public void layerTwosSecondContactIsAShortGradient(GameTestHelper helper) {
        CampaignBands.Band band = band(2);
        expectGradient(
                helper,
                2,
                edge(band, 1) + SHORT / 2,
                SHORT,
                RockTint.layerColour(2, middle(band, 1)),
                RockTint.layerColour(2, middle(band, 2)),
                // The whole step over 16 rows is at most 60 in a channel.
                5,
                "layer 2's short gradient");
        helper.succeed();
    }

    /**
     * Layer 3's first zone contact is interbedded: every row is one zone's colour or the other's, never a mix, and the
     * bands of the lower colour thicken from cycle to cycle down to the Spore Fields' colour.
     */
    @GameTest
    public void layerThreesFirstContactIsInterbedded(GameTestHelper helper) {
        CampaignBands.Band band = band(3);
        int upper = RockTint.layerColour(3, middle(band, 0));
        int lower = RockTint.layerColour(3, middle(band, 1));
        int top = edge(band, 0) + INTERBEDS / 2;
        expect(helper, RockTint.layerColour(3, top) == upper, "layer 3's beds: the top row is not the upper colour");
        expect(
                helper,
                RockTint.layerColour(3, top - INTERBEDS) == lower,
                "layer 3's beds: the row under them is not the lower colour");
        int previous = -1;
        for (int cycle = 0; cycle < INTERBEDS / CYCLE; cycle++) {
            int lowerRows = 0;
            for (int row = 1; row <= CYCLE; row++) {
                int colour = RockTint.layerColour(3, top - cycle * CYCLE - row);
                expect(
                        helper,
                        colour == upper || colour == lower,
                        "layer 3's beds: y=%s is a mix",
                        top - cycle * CYCLE - row);
                lowerRows += colour == lower ? 1 : 0;
            }
            expect(
                    helper,
                    lowerRows > previous && lowerRows < CYCLE,
                    "layer 3's beds: cycle %s has %s rows of the lower colour after %s",
                    cycle,
                    lowerRows,
                    previous);
            previous = lowerRows;
        }
        helper.succeed();
    }

    @GameTest
    public void layerThreesSecondContactIsALongGradient(GameTestHelper helper) {
        CampaignBands.Band band = band(3);
        expectGradient(
                helper,
                3,
                edge(band, 1) + LONG / 2,
                LONG,
                RockTint.layerColour(3, middle(band, 1)),
                RockTint.layerColour(3, middle(band, 2)),
                MAX_STEP,
                "layer 3's long gradient");
        helper.succeed();
    }

    /**
     * The scanner and the world read the same function, so both show the drift; a rock in its rows is the same colour
     * whichever it is.
     */
    @GameTest
    public void everyRockOfAGradientHasTheSameColourSoTheDitherShowsNothing(GameTestHelper helper) {
        for (int zone = 0; zone < 2; zone++) {
            Block upper = zone == 0 ? LayerBlocks.HARDPAN : LayerBlocks.PACKED_SHALE;
            Block lower = zone == 0 ? LayerBlocks.PACKED_SHALE : LayerBlocks.BLACK_SHALE;
            for (int y = contact(zone) + HALF; y >= contact(zone) - HALF; y--) {
                expect(
                        helper,
                        RockTint.of(state(upper), y) == RockTint.of(state(lower), y),
                        "zone %s: the two rocks differ in colour at y=%s",
                        zone,
                        y);
            }
        }
        helper.succeed();
    }

    /**
     * The function the scanner and the block colour call: a layer's rock, and an ore in it, are the layer's colour at
     * their height, row by row through layers 2 and 3.
     */
    @GameTest
    public void theScannerColourOfLayersTwoAndThreesRockAndOreIsTheirLayersColour(GameTestHelper helper) {
        for (int layer = 2; layer <= 3; layer++) {
            CampaignBands.Band band = band(layer);
            Block rock = layer == 2 ? LayerBlocks.GREEN_SHALE : LayerBlocks.BASALT_CORE;
            OreBlock.Host host = layer == 2 ? OreBlock.Host.GREEN_SHALE : OreBlock.Host.BASALT_CORE;
            for (int y = band.maxY(); y >= band.minY(); y--) {
                int colour = RockTint.layerColour(layer, y);
                expect(
                        helper,
                        RockTint.of(state(rock), y) == colour && RockTint.of(ore(host), y) == colour,
                        "layer %s: the rock or an ore in it at y=%s is not the layer's colour",
                        layer,
                        y);
            }
            expect(
                    helper,
                    RockTint.homeOf(ore(host)) == RockTint.home(rock),
                    "layer %s: an ore's home colour is not its host's",
                    layer);
        }
        helper.succeed();
    }

    @GameTest
    public void aRockOutsideItsRowsOrABlockThatIsNotLayerRockKeepsItsColour(GameTestHelper helper) {
        expect(
                helper,
                RockTint.of(state(LayerBlocks.BLACK_SHALE), LAYER.maxY() + 1) == RockTint.home(LayerBlocks.BLACK_SHALE),
                "black shale above layer 1 is not its own colour");
        expect(
                helper,
                RockTint.of(state(LayerBlocks.HARDPAN), LAYER.minY() - 1) == RockTint.home(LayerBlocks.HARDPAN),
                "hardpan under layer 1 is not its own colour");
        expect(
                helper,
                RockTint.of(state(LayerBlocks.HARDPAN), CampaignBands.SURFACE.maxY())
                        == RockTint.home(LayerBlocks.HARDPAN),
                "hardpan in the surface band is not its own colour");
        // Layer 1's floor gradient into green shale, and layer 3's top beds of it: green shale out of layer 2 is its
        // own.
        expect(
                helper,
                RockTint.of(state(LayerBlocks.GREEN_SHALE), LAYER.minY()) == RockTint.home(LayerBlocks.GREEN_SHALE)
                        && RockTint.of(state(LayerBlocks.GREEN_SHALE), band(3).maxY())
                                == RockTint.home(LayerBlocks.GREEN_SHALE),
                "green shale out of layer 2 is not its own colour");
        expect(
                helper,
                RockTint.of(state(LayerBlocks.BASALT_CORE), contact(1)) == RockTint.home(LayerBlocks.BASALT_CORE),
                "basalt in layer 1 is not its own colour");
        expect(
                helper,
                RockTint.of(state(LayerBlocks.COLUMNAR_BASALT), middle(band(3), 1)) == RockTint.NEUTRAL,
                "the columns are tinted");
        helper.succeed();
    }

    /**
     * The column shift puts a rock up to a dozen rows outside its nominal gradient: it must still wear the drift
     * colour.
     */
    @GameTest
    public void aRockPlacedByAShiftedColumnWearsTheDriftsColour(GameTestHelper helper) {
        for (int shift = -SHIFT; shift <= SHIFT; shift++) {
            for (int zone = 0; zone < 2; zone++) {
                Block upper = zone == 0 ? LayerBlocks.HARDPAN : LayerBlocks.PACKED_SHALE;
                Block lower = zone == 0 ? LayerBlocks.PACKED_SHALE : LayerBlocks.BLACK_SHALE;
                for (int y : new int[] {contact(zone) + HALF + shift, contact(zone) - HALF + shift}) {
                    int drift = RockTint.layerColour(1, y);
                    expect(
                            helper,
                            RockTint.of(state(upper), y) == drift && RockTint.of(state(lower), y) == drift,
                            "zone %s: a rock at y=%s, a shift of %s off its gradient, is not the drift colour",
                            zone,
                            y,
                            shift);
                }
            }
        }
        helper.succeed();
    }
}
