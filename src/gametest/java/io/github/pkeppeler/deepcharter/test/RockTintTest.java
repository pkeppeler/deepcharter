package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.RockTint;
import io.github.pkeppeler.deepcharter.layer.Zones;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The colour of layer 1's strata rock by depth (#1551), which the world's block colour and the scanner both use: each
 * channel moves in one direction from a rock's home colour to the next one's over a gradient, in small steps, the same
 * for whichever rock a block is (so the dither of the rocks shows nothing), and a rock outside its rows or a block that
 * is not strata rock keeps its own colour.
 */
public class RockTintTest {
    private static final CampaignBands.Band LAYER = CampaignBands.LAYER_1;
    private static final int HALF = LayerBlocks.CLAIM_GRADIENT_LENGTH / 2;
    /** The most a channel may move between two rows: its whole step over a gradient is under 130 over 64 rows. */
    private static final int MAX_STEP = 3;

    /** The most rows the contour noise and the surface depth move a contact, with a margin. */
    private static final int SHIFT = 14;

    private static int channel(int rgb, int shift) {
        return (rgb >> shift) & 0xFF;
    }

    private static int contact(int zone) {
        return Zones.span(LAYER.minY(), LAYER.height(), zone).low() - LayerBlocks.CLAIM_CONTACT_UNDER_EDGE;
    }

    private static BlockState state(Block block) {
        return block.defaultBlockState();
    }

    @GameTest
    public void theColourDriftsFromEachRocksHomeToTheNextOverALongGradient(GameTestHelper helper) {
        int hardpan = RockTint.home(LayerBlocks.HARDPAN);
        int packed = RockTint.home(LayerBlocks.PACKED_SHALE);
        int black = RockTint.home(LayerBlocks.BLACK_SHALE);
        expect(helper, RockTint.drift(LAYER.maxY()) == hardpan, "the top of layer 1 is not the hardpan's colour");
        expect(helper, RockTint.drift(LAYER.minY()) == black, "the floor of layer 1 is not the black shale's colour");
        for (int zone = 0; zone < 2; zone++) {
            int centre = contact(zone);
            int from = zone == 0 ? hardpan : packed;
            int to = zone == 0 ? packed : black;
            expect(helper, RockTint.drift(centre + HALF) == from, "zone %s: not the upper colour at its top", zone);
            expect(helper, RockTint.drift(centre - HALF) == to, "zone %s: not the lower colour at its foot", zone);
            for (int shift = 0; shift <= 16; shift += 8) {
                int mid = RockTint.drift(centre);
                int expected = (channel(from, shift) + channel(to, shift) + 1) / 2;
                expect(
                        helper,
                        Math.abs(channel(mid, shift) - expected) <= 1,
                        "zone %s: the channel at bit %s is %s at the centre, half way is %s",
                        zone,
                        shift,
                        channel(mid, shift),
                        expected);
            }
            for (int y = centre + HALF; y > centre - HALF; y--) {
                int now = RockTint.drift(y);
                int next = RockTint.drift(y - 1);
                for (int shift = 0; shift <= 16; shift += 8) {
                    int step = channel(next, shift) - channel(now, shift);
                    int whole = channel(to, shift) - channel(from, shift);
                    expect(
                            helper,
                            Math.abs(step) <= MAX_STEP && Integer.signum(step) * Integer.signum(whole) >= 0,
                            "zone %s: a channel steps %s at y=%s on its way from %s to %s",
                            zone,
                            step,
                            y,
                            channel(from, shift),
                            channel(to, shift));
                }
            }
        }
        helper.succeed();
    }

    /** The scanner and the world read the same function, so both show the drift; a rock in its rows is the same colour whichever it is. */
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

    @GameTest
    public void aRockOutsideItsRowsOrABlockThatIsNotStrataRockKeepsItsColour(GameTestHelper helper) {
        expect(
                helper,
                RockTint.of(state(LayerBlocks.BLACK_SHALE), LAYER.maxY()) == RockTint.home(LayerBlocks.BLACK_SHALE),
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
        expect(
                helper,
                RockTint.of(state(LayerBlocks.GREEN_SHALE), contact(0)) == RockTint.NEUTRAL,
                "green shale is tinted");
        expect(helper, RockTint.of(state(LayerBlocks.BASALT_CORE), contact(1)) == RockTint.NEUTRAL, "basalt is tinted");
        helper.succeed();
    }

    /** The column shift puts a rock up to a dozen rows outside its nominal gradient: it must still wear the drift colour. */
    @GameTest
    public void aRockPlacedByAShiftedColumnWearsTheDriftsColour(GameTestHelper helper) {
        for (int shift = -SHIFT; shift <= SHIFT; shift++) {
            for (int zone = 0; zone < 2; zone++) {
                Block upper = zone == 0 ? LayerBlocks.HARDPAN : LayerBlocks.PACKED_SHALE;
                Block lower = zone == 0 ? LayerBlocks.PACKED_SHALE : LayerBlocks.BLACK_SHALE;
                for (int y : new int[] {contact(zone) + HALF + shift, contact(zone) - HALF + shift}) {
                    expect(
                            helper,
                            RockTint.of(state(upper), y) == RockTint.drift(y)
                                    && RockTint.of(state(lower), y) == RockTint.drift(y),
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
