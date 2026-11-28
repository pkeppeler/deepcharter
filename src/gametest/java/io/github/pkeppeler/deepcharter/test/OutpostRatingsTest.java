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
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server GameTests for #989 (ADR 0076): each depth rates heat, pressure, flooding and quake bracing, each block's ratings are block tags, and the
 * placement readout says whether the block in hand would hold at the aimed spot. Layers 1 and 2 ask nothing (ratings bite from layer 3), so the
 * readout's verdicts are checked against an example row, {@link #EXAMPLE_LAYER}, that no layer asks yet.
 */
public class OutpostRatingsTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutpostRatingsTest.class);
    private static final int X = 100;
    private static final int LOCAL_HEIGHT = 20;
    private static final String CAMPAIGN = "deepcharter:campaign_sample";
    private static final String SHORT_ON = "readout.deepcharter.placement.short_on";
    private static final String NOTHING_RATED = "readout.deepcharter.placement.nothing_rated";
    private static final String INSIDE_SHELL = "readout.deepcharter.placement.inside_shell";
    /** An example of what a later layer might ask, not set for any layer: heat and quake at 2, pressure and flooding at 1. */
    private static final Ratings EXAMPLE_LAYER = new Ratings(2, 1, 1, 2);

    /** The tag of blocks that were looked at and rated nothing, {@code deepcharter:rating/none}. */
    private static final TagKey<Block> UNRATED =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "rating/none"));

    private static final Pattern TAG_FILE = Pattern.compile("rating/([a-z]+)/(\\d+)\\.json");

    private static Ratings all(int level) {
        return new Ratings(level, level, level, level);
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
                    BlockState state = entry.getValue().defaultBlockState();
                    String id = entry.getKey().identifier().toString();
                    boolean rated = !BlockRatings.of(state).equals(Ratings.NONE);
                    if (state.is(UNRATED) == rated) {
                        (rated ? both : undecided).add(id);
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

    /** Only levels 1 to {@code maxLevel} are read, so a tag file above it would rate nothing and say nothing: it fails here. */
    @GameTest
    public void noRatingTagIsAboveTheHighestLevelTheGameReads(GameTestHelper helper) {
        List<String> tooHigh = new ArrayList<>();
        int files = 0;
        for (var id : helper.getLevel()
                .getServer()
                .getResourceManager()
                .listResources("tags/block/rating", path -> true)
                .keySet()) {
            Matcher match = TAG_FILE.matcher(id.getPath().substring("tags/block/".length()));
            if (!match.matches() && !id.getPath().endsWith("rating/none.json")) {
                tooHigh.add(id + " is not rating/<axis>/<level>.json");
            } else if (match.matches()) {
                files++;
                boolean axis = Arrays.stream(RatingAxis.values()).anyMatch(a -> a.id().equals(match.group(1)));
                if (!axis
                        || Integer.parseInt(match.group(2)) < 1
                        || Integer.parseInt(match.group(2)) > RatingTuning.DEFAULT.maxLevel()) {
                    tooHigh.add(id.toString());
                }
            }
        }
        expect(helper, files > 0, "no rating tag files were found: the listing is broken");
        expect(helper, tooHigh.isEmpty(), "these rating tags name an axis or level the game never reads: %s", tooHigh);
        helper.succeed();
    }

    /** A block's rating on an axis is its tag's level, 0 when no tag lists it, and the data says what each material holds. */
    @GameTest
    public void aBlocksRatingsAreItsTagLevels(GameTestHelper helper) {
        expectRatings(helper, LayerBlocks.PACKED_SHALE, all(1));
        expectRatings(helper, LayerBlocks.GREEN_SHALE, all(2));
        expectRatings(helper, LayerBlocks.BASALT_CORE, all(3));
        expectRatings(helper, LayerBlocks.COLUMNAR_BASALT, all(3));
        expectRatings(helper, LayerBlocks.GLOWCAP, Ratings.NONE);
        expectRatings(helper, SlagBrick.BLOCK, all(2));
        expectRatings(helper, ColonyKit.RIVETED_PLATE, all(2));
        // Glass and grating hold some axes and not others, so the axes are not one number.
        expectRatings(helper, ColonyKit.WINDOW_GLASS, new Ratings(2, 0, 2, 0));
        expectRatings(helper, ColonyKit.GRATING, new Ratings(2, 0, 0, 2));
        expectRatings(helper, Blocks.STONE, Ratings.NONE);
        expectRatings(helper, Blocks.OAK_PLANKS, Ratings.NONE);
        helper.succeed();
    }

    private static void expectRatings(GameTestHelper helper, Block block, Ratings expected) {
        Ratings actual = BlockRatings.of(block.defaultBlockState());
        LOGGER.info("[ratings] {}: {}", BuiltInRegistries.BLOCK.getKey(block), actual);
        expect(helper, actual.equals(expected), "%s should be rated %s, was %s", block, expected, actual);
    }

    /**
     * Ratings bite from layer 3 (CREATURES section 9: "below layer 2"): layers 1 and 2 and the surface ask nothing. The table has a row for every built layer, so a
     * layer cannot ship without stating its row, and a row from layer 3 on asks for something. Layer 3's row comes with its worldgen (#972).
     */
    @GameTest
    public void layersOneAndTwoAskNothingAndEveryLaterLayerMustStateItsRow(GameTestHelper helper) {
        expect(
                helper,
                RatingTuning.DEFAULT.layers().size() == CampaignBands.lastLayer(),
                "the depth table has %s rows but the campaign world builds %s layers: state the new layer's row",
                RatingTuning.DEFAULT.layers().size(),
                CampaignBands.lastLayer());
        for (int layer = 0; layer <= Math.min(2, CampaignBands.lastLayer()); layer++) {
            expect(
                    helper,
                    DepthRatings.forLayer(layer).orElseThrow().equals(Ratings.NONE),
                    "layer %s asks nothing",
                    layer);
        }
        for (int layer = 3; layer <= CampaignBands.lastLayer(); layer++) {
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

    @GameTest
    public void aBlockFallsShortOnExactlyTheAxesItMisses(GameTestHelper helper) {
        List<RatingAxis> glass =
                BlockRatings.of(ColonyKit.WINDOW_GLASS.defaultBlockState()).shortfalls(EXAMPLE_LAYER);
        expect(helper, glass.equals(List.of(RatingAxis.PRESSURE, RatingAxis.QUAKE)), "glass misses %s", glass);
        List<RatingAxis> shale =
                BlockRatings.of(LayerBlocks.PACKED_SHALE.defaultBlockState()).shortfalls(EXAMPLE_LAYER);
        expect(helper, shale.equals(List.of(RatingAxis.HEAT, RatingAxis.QUAKE)), "layer 1 shale misses %s", shale);
        expect(
                helper,
                BlockRatings.of(SlagBrick.BLOCK.defaultBlockState()).meets(EXAMPLE_LAYER),
                "slag brick should meet the example row");
        helper.succeed();
    }

    /** Where the depth asks something the readout says HOLDS HERE or WILL NOT HOLD UNATTENDED and why; where it asks nothing it says nothing. */
    @GameTest
    public void theReadoutSaysHoldsHereOrWillNotHoldUnattendedWhereTheDepthAsksSomething(GameTestHelper helper) {
        expectReadout(helper, Optional.of(EXAMPLE_LAYER), SlagBrick.BLOCK, Optional.of(Verdict.HOLDS_HERE), "");
        expectReadout(helper, Optional.of(EXAMPLE_LAYER), LayerBlocks.GREEN_SHALE, Optional.of(Verdict.HOLDS_HERE), "");
        expectReadout(
                helper,
                Optional.of(EXAMPLE_LAYER),
                LayerBlocks.PACKED_SHALE,
                Optional.of(Verdict.WILL_NOT_HOLD),
                SHORT_ON);
        expectReadout(
                helper, Optional.of(EXAMPLE_LAYER), Blocks.OAK_PLANKS, Optional.of(Verdict.WILL_NOT_HOLD), SHORT_ON);
        expectReadout(helper, Optional.of(Ratings.NONE), Blocks.OAK_PLANKS, Optional.empty(), "");
        expectReadout(helper, Optional.empty(), SlagBrick.BLOCK, Optional.of(Verdict.WILL_NOT_HOLD), NOTHING_RATED);
        helper.succeed();
    }

    /**
     * A block that would not hold on its own reads SHELTERED when it would stand inside a closed shell that does hold: the rule of ADR 0092.
     * What shelters is {@code OutpostShellTest}'s; here the words, and that a holding block never asks.
     */
    @GameTest
    public void aBlockThatWouldNotHoldReadsShelteredInsideAClosedShell(GameTestHelper helper) {
        Optional<Ratings> layer = DepthRatings.forLayer(3);
        Optional<Readout> inside = Placements.readout(layer, Blocks.OAK_PLANKS.defaultBlockState(), () -> true);
        expect(
                helper,
                inside.map(Readout::verdict).equals(Optional.of(Verdict.SHELTERED))
                        && inside.flatMap(Readout::reason).map(OutpostRatingsTest::key).orElse("").equals(INSIDE_SHELL),
                "a plank in a closed shell should read SHELTERED with its reason, read %s",
                inside);
        expect(
                helper,
                key(Verdict.SHELTERED.label()).equals("readout.deepcharter.placement.sheltered"),
                "the verdict's words are the lang key's");
        expectReadout(helper, layer, SlagBrick.BLOCK, Optional.of(Verdict.HOLDS_HERE), "");
        boolean[] asked = {false};
        Optional<Readout> holds = Placements.readout(layer, SlagBrick.BLOCK.defaultBlockState(), () -> {
            asked[0] = true;
            return true;
        });
        expect(
                helper,
                holds.map(Readout::verdict).equals(Optional.of(Verdict.HOLDS_HERE)) && !asked[0],
                "a block that holds says so without asking the shell, read %s",
                holds);
        Optional<Readout> open = Placements.readout(layer, Blocks.OAK_PLANKS.defaultBlockState(), () -> false);
        expect(
                helper,
                open.map(Readout::verdict).equals(Optional.of(Verdict.WILL_NOT_HOLD))
                        && open.flatMap(Readout::reason).map(OutpostRatingsTest::key).orElse("").equals(SHORT_ON),
                "a plank in the open still will not hold, read %s",
                open);
        expect(
                helper,
                Placements.readout(Optional.empty(), SlagBrick.BLOCK.defaultBlockState(), () -> true)
                        .flatMap(Readout::reason)
                        .map(OutpostRatingsTest::key)
                        .orElse("")
                        .equals(NOTHING_RATED),
                "where nothing is rated nothing shelters, whatever is round it");
        expect(
                helper,
                Placements.readout(Optional.of(Ratings.NONE), Blocks.OAK_PLANKS.defaultBlockState(), () -> true)
                        .isEmpty(),
                "where the depth asks nothing the readout says nothing, sheltered or not");
        helper.succeed();
    }

    private static void expectReadout(
            GameTestHelper helper,
            Optional<Ratings> required,
            Block block,
            Optional<Verdict> verdict,
            String reasonKey) {
        Optional<Readout> readout = Placements.readout(required, block.defaultBlockState(), () -> false);
        Optional<Verdict> said = readout.map(Readout::verdict);
        String reason =
                readout.flatMap(Readout::reason).map(OutpostRatingsTest::key).orElse("");
        expect(
                helper,
                said.equals(verdict) && reason.equals(reasonKey),
                "%s against %s should read %s with reason '%s', read %s with '%s'",
                block,
                required,
                verdict,
                reasonKey,
                said,
                reason);
    }

    /** In layers 1 and 2 and on the surface nothing is asked, so the readout is empty for a block that would hold nowhere else. */
    @GameTest
    public void whereNothingIsAskedTheReadoutSaysNothing(GameTestHelper helper) {
        for (int layer : new int[] {1, 2}) {
            ServerLevel level = TestLayers.level(helper, layer);
            BlockPos at = TestLayers.pos(layer, X, LOCAL_HEIGHT, X);
            expect(
                    helper,
                    Placements.readout(level, at, Blocks.OAK_PLANKS.defaultBlockState())
                            .isEmpty(),
                    "a plank in layer %s should read nothing",
                    layer);
        }
        expect(
                helper,
                Placements.readout(
                                helper.getLevel(), helper.absolutePos(BlockPos.ZERO), Blocks.STONE.defaultBlockState())
                        .isEmpty(),
                "stone on the surface should read nothing");
        helper.succeed();
    }

    /**
     * In the campaign world a layer is a band of one level: the surface and layers 1 and 2 ask nothing, and the unbuilt depth below the last layer has no
     * rated material, so nothing holds there, not even slag brick, and the readout says why.
     */
    @GameTest(dimension = CAMPAIGN, environment = CampaignEnvironments.B)
    public void inTheCampaignWorldTheBuiltBandsAskNothingAndUnbuiltDepthHoldsNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (CampaignBands.Band band : List.of(CampaignBands.SURFACE, CampaignBands.LAYER_1, CampaignBands.LAYER_2)) {
            expect(
                    helper,
                    DepthRatings.requiredAt(level, band.minY() + LOCAL_HEIGHT).equals(Optional.of(Ratings.NONE)),
                    "the %s asks nothing",
                    band.name());
            expect(
                    helper,
                    Placements.readout(
                                    level,
                                    new BlockPos(X, band.minY() + LOCAL_HEIGHT, X),
                                    Blocks.OAK_PLANKS.defaultBlockState())
                            .isEmpty(),
                    "a plank in the %s should read nothing",
                    band.name());
        }
        int unbuiltY = CampaignBands.UNBUILT.minY() + LOCAL_HEIGHT;
        expect(helper, DepthRatings.requiredAt(level, unbuiltY).isEmpty(), "unbuilt depth has no rating to ask");
        Optional<Readout> slag =
                Placements.readout(level, new BlockPos(X, unbuiltY, X), SlagBrick.BLOCK.defaultBlockState());
        expect(
                helper,
                slag.map(Readout::verdict).equals(Optional.of(Verdict.WILL_NOT_HOLD))
                        && slag.flatMap(Readout::reason)
                                .map(OutpostRatingsTest::key)
                                .orElse("")
                                .equals(NOTHING_RATED),
                "nothing holds in unbuilt depth, not even slag brick: %s",
                slag);
        helper.succeed();
    }

    /** The spec sheet states a rated block's ratings, and nothing for a block rated nothing, listed or not. */
    @GameTest
    public void theSpecSheetStatesTheRatingsOfARatedBlockAndNothingElse(GameTestHelper helper) {
        for (Block rated : List.of(LayerBlocks.PACKED_SHALE, SlagBrick.BLOCK, ColonyKit.WINDOW_GLASS)) {
            List<Component> lines = SpecSheet.lines(rated.defaultBlockState());
            expect(
                    helper,
                    lines.size() == 1 && key(lines.getFirst()).equals("spec.deepcharter.ratings"),
                    "%s's sheet should be one ratings line, was %s",
                    rated,
                    lines);
        }
        for (Block unrated : List.of(Blocks.STONE, SurfaceBlocks.REGOLITH)) {
            expect(
                    helper,
                    SpecSheet.lines(unrated.defaultBlockState()).isEmpty(),
                    "%s is rated nothing and has no ratings line",
                    unrated);
        }
        helper.succeed();
    }
}
