package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.ColonyKit;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.outpost.BlockRatings;
import io.github.pkeppeler.deepcharter.outpost.DepthRatings;
import io.github.pkeppeler.deepcharter.outpost.Placements;
import io.github.pkeppeler.deepcharter.outpost.Placements.Readout;
import io.github.pkeppeler.deepcharter.outpost.Placements.Verdict;
import io.github.pkeppeler.deepcharter.outpost.RatingAxis;
import io.github.pkeppeler.deepcharter.outpost.RatingTuning;
import io.github.pkeppeler.deepcharter.outpost.Ratings;
import io.github.pkeppeler.deepcharter.outpost.SpecSheet;
import io.github.pkeppeler.deepcharter.surface.SurfaceBlocks;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server GameTests for #989 (ADR 0076): each depth rates heat, pressure, flooding and quake bracing, each block's ratings are block tags, and the
 * placement readout says whether the block in hand would hold at the aimed spot. The shell and habitability of an outpost are in {@link OutpostTest}.
 */
public class OutpostRatingsTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutpostRatingsTest.class);
    private static final int X = 100;
    private static final int ABOVE_FLOOR = 20;
    private static final String BELOW_RATING = "readout.deepcharter.placement.below_rating";
    private static final String CAMPAIGN = "deepcharter:campaign_sample";
    private static final String NOTHING_RATED = "readout.deepcharter.placement.nothing_rated";

    /** A spot {@value #ABOVE_FLOOR} blocks above the floor of {@code layer}'s band in {@code level}. */
    private static BlockPos spot(Level level, int layer) {
        return new BlockPos(X, TestLayers.band(level, layer).minY() + ABOVE_FLOOR, X);
    }

    private static String key(Component component) {
        return component.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : "";
    }

    /** Every block the mod registers was decided: it is in a rating tag, or in {@code rating/none}, and never both. A new block fails here until it is. */
    @GameTest
    public void everyModBlockIsRatedOrDeliberatelyUnrated(GameTestHelper helper) {
        List<String> undecided = new ArrayList<>();
        List<String> both = new ArrayList<>();
        BuiltInRegistries.BLOCK.entrySet().stream()
                .filter(entry -> entry.getKey().identifier().getNamespace().equals(DeepCharter.MOD_ID))
                .forEach(entry -> {
                    var state = entry.getValue().defaultBlockState();
                    String id = entry.getKey().identifier().toString();
                    if (!BlockRatings.isListed(state)) {
                        undecided.add(id);
                    } else if (state.is(BlockRatings.UNRATED)
                            && !BlockRatings.of(state).equals(Ratings.NONE)) {
                        both.add(id);
                    }
                });
        expect(
                helper,
                undecided.isEmpty(),
                "these blocks are in no rating tag: add each to data/deepcharter/tags/block/rating/<axis>/<level>.json or rating/none.json: %s",
                undecided);
        expect(helper, both.isEmpty(), "these blocks are both rated and in rating/none: %s", both);
        helper.succeed();
    }

    /** A block's rating on an axis is its tag's level, 0 when no tag lists it, and the data says what each material holds. */
    @GameTest
    public void aBlocksRatingsAreItsTagLevels(GameTestHelper helper) {
        expectRatings(helper, LayerBlocks.PACKED_SHALE, Ratings.all(1));
        expectRatings(helper, LayerBlocks.GREEN_SHALE, Ratings.all(2));
        expectRatings(helper, SlagBrick.BLOCK, Ratings.all(2));
        expectRatings(helper, ColonyKit.RIVETED_PLATE, Ratings.all(2));
        // Glass and grating hold some axes and not others, so the axes are not one number.
        expectRatings(helper, ColonyKit.WINDOW_GLASS, new Ratings(2, 0, 2, 0));
        expectRatings(helper, ColonyKit.GRATING, new Ratings(2, 0, 0, 2));
        expectRatings(helper, Blocks.STONE, Ratings.NONE);
        expectRatings(helper, Blocks.OAK_PLANKS, Ratings.NONE);
        expect(
                helper,
                !BlockRatings.isListed(Blocks.STONE.defaultBlockState()),
                "a vanilla block no tag lists should read as unlisted");
        helper.succeed();
    }

    private static void expectRatings(GameTestHelper helper, Block block, Ratings expected) {
        Ratings actual = BlockRatings.of(block.defaultBlockState());
        expect(helper, actual.equals(expected), "%s should be rated %s, was %s", block, expected, actual);
    }

    /** The depth table has an entry for every layer the campaign world builds, so a new layer cannot ship unrated, and no layer asks less than the one above. */
    @GameTest
    public void everyBuiltLayerAsksForRatingsAndDepthNeverAsksLess(GameTestHelper helper) {
        expect(
                helper,
                RatingTuning.DEFAULT.layers().size() == CampaignBands.lastLayer(),
                "the depth table has %s layers but the campaign world builds %s: rate the new layer",
                RatingTuning.DEFAULT.layers().size(),
                CampaignBands.lastLayer());
        expect(
                helper,
                DepthRatings.forLayer(0).orElseThrow().equals(Ratings.NONE),
                "the surface should ask for nothing");
        for (int layer = 1; layer <= CampaignBands.lastLayer(); layer++) {
            expect(
                    helper,
                    !DepthRatings.forLayer(layer).orElseThrow().equals(Ratings.NONE),
                    "layer %s should ask for something",
                    layer);
        }
        expect(
                helper,
                DepthRatings.forLayer(CampaignBands.lastLayer() + 1).isEmpty(),
                "a layer that is not built has no ratings yet");
        helper.succeed();
    }

    /** Which materials hold in layers 1 and 2 (the measured row of the PR) and the axes the others fall short on. */
    @GameTest
    public void theBlocksThatHoldInLayersOneAndTwoAreThoseOfTheirOwnRock(GameTestHelper helper) {
        for (Block block : List.of(
                LayerBlocks.PACKED_SHALE,
                LayerBlocks.GREEN_SHALE,
                SlagBrick.BLOCK,
                ColonyKit.RIVETED_PLATE,
                ColonyKit.WINDOW_GLASS,
                ColonyKit.GRATING,
                Blocks.STONE)) {
            LOGGER.info(
                    "[ratings] {}: {}; layer 1 {}, layer 2 {}",
                    BuiltInRegistries.BLOCK.getKey(block),
                    BlockRatings.of(block.defaultBlockState()),
                    holds(helper, block, 1),
                    holds(helper, block, 2));
        }
        expect(helper, holds(helper, LayerBlocks.PACKED_SHALE, 1), "layer 1's own rock holds in layer 1");
        expect(helper, !holds(helper, LayerBlocks.PACKED_SHALE, 2), "layer 1's rock is below layer 2's rating");
        expect(helper, holds(helper, LayerBlocks.GREEN_SHALE, 2), "layer 2's own rock holds in layer 2");
        expect(
                helper,
                holds(helper, SlagBrick.BLOCK, 1) && holds(helper, SlagBrick.BLOCK, 2),
                "fused slag holds in both");
        expect(
                helper,
                !holds(helper, ColonyKit.WINDOW_GLASS, 2),
                "window glass has no pressure or quake rating for layer 2");
        expect(helper, !holds(helper, Blocks.STONE, 1), "vanilla stone is unrated everywhere below the surface");
        helper.succeed();
    }

    private static boolean holds(GameTestHelper helper, Block block, int layer) {
        ServerLevel level = TestLayers.level(helper, layer);
        return Placements.holds(level, spot(level, layer), block.defaultBlockState());
    }

    @GameTest
    public void aBlockBelowTheRatingFallsShortOnExactlyTheAxesItMisses(GameTestHelper helper) {
        Ratings layerTwo = DepthRatings.forLayer(2).orElseThrow();
        List<RatingAxis> glass =
                BlockRatings.of(ColonyKit.WINDOW_GLASS.defaultBlockState()).shortfalls(layerTwo);
        expect(helper, glass.equals(List.of(RatingAxis.PRESSURE, RatingAxis.QUAKE)), "glass misses %s", glass);
        List<RatingAxis> shale =
                BlockRatings.of(LayerBlocks.PACKED_SHALE.defaultBlockState()).shortfalls(layerTwo);
        expect(helper, shale.equals(List.of(RatingAxis.HEAT, RatingAxis.QUAKE)), "layer 1 shale misses %s", shale);
        helper.succeed();
    }

    @GameTest
    public void theReadoutSaysHoldsHereOrWillNotHoldUnattendedAtTheAimedDepth(GameTestHelper helper) {
        Placements readouts = new Placements();
        expectReadout(helper, readouts, 0, Blocks.STONE, Verdict.HOLDS_HERE, "");
        expectReadout(helper, readouts, 1, LayerBlocks.PACKED_SHALE, Verdict.HOLDS_HERE, "");
        expectReadout(helper, readouts, 1, Blocks.OAK_PLANKS, Verdict.WILL_NOT_HOLD, BELOW_RATING);
        expectReadout(helper, readouts, 2, LayerBlocks.PACKED_SHALE, Verdict.WILL_NOT_HOLD, BELOW_RATING);
        expectReadout(helper, readouts, 2, SlagBrick.BLOCK, Verdict.HOLDS_HERE, "");
        expectReadout(helper, readouts, 2, ColonyKit.WINDOW_GLASS, Verdict.WILL_NOT_HOLD, BELOW_RATING);
        helper.succeed();
    }

    private static void expectReadout(
            GameTestHelper helper, Placements readouts, int layer, Block block, Verdict verdict, String reasonKey) {
        ServerLevel level = layer == 0 ? helper.getLevel() : TestLayers.level(helper, layer);
        BlockPos at = layer == 0 ? helper.absolutePos(BlockPos.ZERO) : spot(level, layer);
        Readout readout = readouts.readout(level, at, block.defaultBlockState());
        String reason = readout.reason().map(OutpostRatingsTest::key).orElse("");
        expect(
                helper,
                readout.verdict() == verdict && reason.equals(reasonKey),
                "%s in layer %s should read %s with reason '%s', read %s with '%s'",
                block,
                layer,
                verdict,
                reasonKey,
                readout.verdict(),
                reason);
    }

    /** The shelter hook is the creature rule's: with none registered nothing is sheltered, and a check that says so turns WILL NOT HOLD into SHELTERED, never a holding block. */
    @GameTest
    public void aShelterCheckTurnsWillNotHoldIntoShelteredAndNothingElse(GameTestHelper helper) {
        Placements sheltering = new Placements();
        sheltering.addShelterCheck((level, pos) -> true);
        expectReadout(helper, sheltering, 1, Blocks.OAK_PLANKS, Verdict.SHELTERED, BELOW_RATING);
        expectReadout(helper, sheltering, 1, LayerBlocks.PACKED_SHALE, Verdict.HOLDS_HERE, "");
        Placements exposed = new Placements();
        exposed.addShelterCheck((level, pos) -> false);
        expectReadout(helper, exposed, 1, Blocks.OAK_PLANKS, Verdict.WILL_NOT_HOLD, BELOW_RATING);
        helper.succeed();
    }

    /**
     * In the campaign world a layer is a band of one level: each band asks its own rating, the surface asks nothing, and the unbuilt depth
     * below the last layer has no rated material, so nothing holds there, not even slag brick, and the readout says why.
     */
    @GameTest(dimension = CAMPAIGN)
    public void inTheCampaignWorldEachBandAsksItsOwnRatingAndUnbuiltDepthHoldsNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int layerOneY = CampaignBands.LAYER_1.minY() + ABOVE_FLOOR;
        int layerTwoY = CampaignBands.LAYER_2.minY() + ABOVE_FLOOR;
        int unbuiltY = CampaignBands.UNBUILT.minY() + ABOVE_FLOOR;
        int surfaceY = CampaignBands.SURFACE.minY() + ABOVE_FLOOR;
        expect(
                helper,
                DepthRatings.requiredAt(level, surfaceY).equals(Optional.of(Ratings.NONE)),
                "the surface asks nothing");
        expect(
                helper,
                DepthRatings.requiredAt(level, layerOneY).equals(DepthRatings.forLayer(1)),
                "layer 1's band asks layer 1's rating");
        expect(
                helper,
                DepthRatings.requiredAt(level, layerTwoY).equals(DepthRatings.forLayer(2)),
                "layer 2's band asks layer 2's rating");
        expect(helper, DepthRatings.requiredAt(level, unbuiltY).isEmpty(), "unbuilt depth has no rating to ask");
        Placements readouts = new Placements();
        Readout slag = readouts.readout(level, new BlockPos(X, unbuiltY, X), SlagBrick.BLOCK.defaultBlockState());
        expect(
                helper,
                slag.verdict() == Verdict.WILL_NOT_HOLD
                        && slag.reason().map(OutpostRatingsTest::key).orElse("").equals(NOTHING_RATED),
                "nothing holds in unbuilt depth, not even slag brick: %s",
                slag);
        expect(
                helper,
                readouts.readout(level, new BlockPos(X, layerTwoY, X), SlagBrick.BLOCK.defaultBlockState())
                                .verdict()
                        == Verdict.HOLDS_HERE,
                "slag holds in layer 2's band");
        helper.succeed();
    }

    @GameTest
    public void theSpecSheetStatesTheRatingsAndWhereTheyHold(GameTestHelper helper) {
        List<Component> shale = SpecSheet.lines(LayerBlocks.PACKED_SHALE.defaultBlockState());
        expect(
                helper,
                shale.size() == 2 && key(shale.get(0)).equals("spec.deepcharter.ratings"),
                "layer 1 shale's sheet should be a ratings line and a holds line, was %s",
                shale);
        expect(
                helper,
                key(shale.get(1)).equals("spec.deepcharter.holds_in_layer"),
                "shale holds in one layer: %s",
                shale);
        List<Component> slag = SpecSheet.lines(SlagBrick.BLOCK.defaultBlockState());
        expect(
                helper,
                key(slag.get(1)).equals("spec.deepcharter.holds_in_layers"),
                "slag holds in two layers: %s",
                slag);
        List<Component> stone = SpecSheet.lines(Blocks.STONE.defaultBlockState());
        expect(helper, stone.isEmpty(), "a block no tag lists has no ratings on its sheet: %s", stone);
        List<Component> regolith = SpecSheet.lines(SurfaceBlocks.REGOLITH.defaultBlockState());
        expect(
                helper,
                key(regolith.get(1)).equals("spec.deepcharter.holds_nowhere"),
                "unrated rock holds nowhere: %s",
                regolith);
        helper.succeed();
    }
}
