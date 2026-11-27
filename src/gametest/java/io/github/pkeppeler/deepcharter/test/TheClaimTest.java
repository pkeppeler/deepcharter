package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.layer.gen.ClaimShaftFeature;
import io.github.pkeppeler.deepcharter.layer.gen.StrataRule;
import io.github.pkeppeler.deepcharter.ore.OreBlock;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The dig from the colony through layer 1, The Claim (#1277): natural caves that open up with depth, from the surface
 * band's underground down to the Deep Claim; a rock of its own for each zone (Topsoil Claims' hardpan, Stone Benches'
 * packed shale, Deep Claim's black shale) with a contact that waves either side of the zone's edge; first-generation
 * shafts dressed as the lore has them (LORE section 11); and every one of these features writing inside its own chunk
 * (ADR 0088). The world is {@code deepcharter:campaign_full}, the shipped overworld, sampled in a square of chunks far
 * from the colony and the structure sites.
 */
public class TheClaimTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(TheClaimTest.class);
    private static final String FULL = "deepcharter:campaign_full";
    private static final int MAX_TICKS = 20_000;

    /** The first chunk of the sample on each axis, and its chunks on a side: far from the colony at the origin. */
    private static final int SAMPLE_CHUNK = 40;

    private static final int SAMPLE_CHUNKS = 8;
    /** A larger square for the shafts, which stand in about one chunk in ten. */
    private static final int SHAFT_CHUNKS = 12;

    private static final CampaignBands.Band LAYER = CampaignBands.LAYER_1;

    /**
     * The surface band's underground that the caves reach: under the deepest ground (the great pit's floor) and over
     * its floor.
     */
    private static final int SURFACE_CAVES_LOW = CampaignBands.SURFACE.minY() + 8;

    private static final int SURFACE_CAVES_HIGH = 16;
    /** Rows under a column's rock top that are not counted: the top's own relief is no cave. */
    private static final int TOP_SKIN = 4;

    /** Rows the contour noise and the surface depth can move a contact either way, with a margin. */
    private static final int SHIFT_REACH = 14;

    /**
     * The most the gradients' dither may be off the straight line of its share against depth, over a few hundred
     * columns.
     */
    private static final double SHARE_TOLERANCE = 0.12;

    /** The rock of each zone, top first. */
    private static final List<String> STRATA = List.of("hardpan", "packed_shale", "black_shale", "green_shale");

    /**
     * The Claim's features, and the fewest positions each one's last placement step keeps in the sample, so its
     * in-chunk check has positions to judge (#1401, where the cave stalks kept one while their rows missed most of
     * Topsoil Claims): about half of what each keeps, measured at #1453.
     */
    private static final Map<String, Integer> MIN_KEPT = new TreeMap<>(
            Map.of("claim_shafts", 4, "claim_cave_ores", 200, "claim_salvage_caches", 8, "fungal_stalks_cave", 40));

    /**
     * Caves open up with depth. The share of open blocks under each column's rock top grows from the surface band's
     * underground, where there are a few, through Topsoil Claims to the Deep Claim, and stays small everywhere:
     * pockets, fissures and passages, nothing like layer 3's halls.
     */
    @GameTest(
            environment = CampaignEnvironments.C,
            dimension = FULL,
            maxTicks = FarChunks.AWAIT_BUDGET_TICKS + MAX_TICKS)
    public void cavesOpenUpWithDepth(GameTestHelper helper) {
        FarChunks.whenBlocksLoaded(
                helper, sampleChunks(helper, SAMPLE_CHUNKS), () -> cavesOpenUpWithDepthLoaded(helper));
    }

    private static void cavesOpenUpWithDepthLoaded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long[] open = new long[Zones.COUNT + 1];
        long[] all = new long[Zones.COUNT + 1];
        for (BlockPos column : columns(SAMPLE_CHUNKS)) {
            // sync-load: whenBlocksLoaded loaded the chunks
            ChunkAccess chunk = level.getChunk(column);
            for (int y = SURFACE_CAVES_LOW; y <= SURFACE_CAVES_HIGH; y++) {
                all[0]++;
                if (chunk.getBlockState(column.atY(y)).isAir()) {
                    open[0]++;
                }
            }
            int top = rockTop(chunk, column);
            for (int y = LAYER.minY() + LayerTuning.DEFAULT.crustThickness() + 12; y <= top - TOP_SKIN; y++) {
                int zone = 1 + Zones.index(LAYER.minY(), LAYER.height(), y);
                all[zone]++;
                if (chunk.getBlockState(column.atY(y)).isAir()) {
                    open[zone]++;
                }
            }
        }
        double[] share = new double[open.length];
        for (int i = 0; i < share.length; i++) {
            share[i] = all[i] == 0 ? 0 : (double) open[i] / all[i];
        }
        LOGGER.info(
                "[the-claim] open share: surface underground {}, Topsoil Claims {}, Stone Benches {}, Deep Claim {}",
                percent(share[0]),
                percent(share[1]),
                percent(share[2]),
                percent(share[3]));
        expect(helper, share[0] >= 0.002, "the surface band's underground has almost no caves: %s", percent(share[0]));
        expect(helper, share[0] < share[1], "the surface's underground should have fewer caves than Topsoil Claims");
        expect(helper, share[1] >= 0.01, "Topsoil Claims has almost no caves: %s", percent(share[1]));
        expect(
                helper,
                share[3] > 1.3 * share[1],
                "the Deep Claim (%s) should hold clearly more cave than Topsoil Claims (%s)",
                percent(share[3]),
                percent(share[1]));
        expect(helper, share[2] > share[1], "Stone Benches should hold more cave than Topsoil Claims");
        for (int zone = 1; zone <= Zones.COUNT; zone++) {
            expect(
                    helper,
                    share[zone] <= 0.15,
                    "zone %s is %s open: caverns, not caves",
                    zone - 1,
                    percent(share[zone]));
        }
        helper.succeed();
    }

    /**
     * Each zone has its own rock, and the rock changes over a long gradient (#1551). Every block of layer rock, and the
     * host of every ore, is one the strata rule can lay in its row; above the first gradient it is all hardpan; across
     * the gradient at the first contact the share of hardpan among the hardpan and packed shale falls with depth; and
     * the rows over the floor are layer 2's rock.
     */
    @GameTest(
            environment = CampaignEnvironments.C,
            dimension = FULL,
            maxTicks = FarChunks.AWAIT_BUDGET_TICKS + MAX_TICKS)
    public void eachZoneHasItsOwnRock(GameTestHelper helper) {
        FarChunks.whenBlocksLoaded(
                helper, sampleChunks(helper, SAMPLE_CHUNKS), () -> eachZoneHasItsOwnRockLoaded(helper));
    }

    private static void eachZoneHasItsOwnRockLoaded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StrataRule rule = (StrataRule) level.registryAccess()
                .lookupOrThrow(Registries.MATERIAL_RULE)
                .getValueOrThrow(ResourceKey.create(
                        Registries.MATERIAL_RULE, Identifier.fromNamespaceAndPath("deepcharter", "claim_strata")));
        int firstContact = rule.contacts().getFirst().y();
        int reach = LayerBlocks.CLAIM_GRADIENT_LENGTH / 4;
        int[] hardpanAbove = new int[2];
        int[] hardpanBelow = new int[2];
        int[] pureHardpan = new int[2];
        int[] floorRock = new int[2];
        List<String> strays = new ArrayList<>();
        int bottomRow = LAYER.minY() + LayerTuning.DEFAULT.crustThickness();
        // The rocks each row can hold, worked out once for the rows and not for every block.
        List<Set<String>> possible = new ArrayList<>();
        for (int y = bottomRow; y < LAYER.topExclusive(); y++) {
            possible.add(rule.possibleAt(y, SHIFT_REACH).stream()
                    .map(block -> BuiltInRegistries.BLOCK.getKey(block).getPath())
                    .collect(Collectors.toSet()));
        }
        for (BlockPos column : columns(SAMPLE_CHUNKS)) {
            // sync-load: whenBlocksLoaded loaded the chunks
            ChunkAccess chunk = level.getChunk(column);
            for (int y = bottomRow; y < LAYER.topExclusive(); y++) {
                @Nullable String stratum = stratumOf(chunk.getBlockState(column.atY(y)));
                if (stratum == null) {
                    continue;
                }
                if (!possible.get(y - bottomRow).contains(stratum) && strays.size() < 5) {
                    strays.add(stratum + " at " + column.atY(y).toShortString());
                }
                boolean rockOfFirst = stratum.equals(STRATA.getFirst()) || stratum.equals(STRATA.get(1));
                if (y == firstContact + reach && rockOfFirst) {
                    hardpanAbove[1]++;
                    hardpanAbove[0] += stratum.equals(STRATA.getFirst()) ? 1 : 0;
                }
                if (y == firstContact - reach && rockOfFirst) {
                    hardpanBelow[1]++;
                    hardpanBelow[0] += stratum.equals(STRATA.getFirst()) ? 1 : 0;
                }
                if (y >= firstContact + LayerBlocks.CLAIM_GRADIENT_LENGTH / 2 + SHIFT_REACH) {
                    pureHardpan[1]++;
                    pureHardpan[0] += stratum.equals(STRATA.getFirst()) ? 1 : 0;
                }
                if (y == bottomRow) {
                    floorRock[1]++;
                    floorRock[0] += stratum.equals("green_shale") ? 1 : 0;
                }
            }
        }
        expect(helper, strays.isEmpty(), "rock the strata rule cannot lay in its row: %s", strays);
        expect(
                helper,
                pureHardpan[1] > 0 && pureHardpan[0] >= 0.99 * pureHardpan[1],
                "above the first gradient %s of %s rock blocks are hardpan",
                pureHardpan[0],
                pureHardpan[1]);
        expect(helper, hardpanAbove[1] > 0 && hardpanBelow[1] > 0, "no hardpan or packed shale on the gradient's rows");
        double upper = (double) hardpanAbove[0] / hardpanAbove[1];
        double lower = (double) hardpanBelow[0] / hardpanBelow[1];
        LOGGER.info(
                "[the-claim] hardpan's share of the first gradient: {} at {} rows over its centre, {} at {} under",
                upper,
                reach,
                lower,
                reach);
        expect(
                helper,
                upper - lower >= 0.5 - SHARE_TOLERANCE,
                "the hardpan's share should fall by half from %s rows over the contact to %s under, from %s to %s",
                reach,
                reach,
                upper,
                lower);
        expect(
                helper,
                floorRock[1] > 0 && floorRock[0] >= 0.8 * floorRock[1],
                "only %s of %s rock blocks in layer 1's lowest row are layer 2's green shale",
                floorRock[0],
                floorRock[1]);
        helper.succeed();
    }

    /**
     * The first generation's shafts are there and dressed as the lore has them: a lunch pail at the foot of each, a
     * ladder, timber of stalk planks and candle stubs burnt out in niches, and, in some, a prayer card; and some of
     * them cut through a cave.
     */
    @GameTest(
            environment = CampaignEnvironments.C,
            dimension = FULL,
            maxTicks = FarChunks.AWAIT_BUDGET_TICKS + MAX_TICKS)
    public void abandonedShaftsAreDressedAsTheLoreHasThem(GameTestHelper helper) {
        FarChunks.whenBlocksLoaded(
                helper,
                sampleChunks(helper, SHAFT_CHUNKS),
                () -> abandonedShaftsAreDressedAsTheLoreHasThemLoaded(helper));
    }

    private static void abandonedShaftsAreDressedAsTheLoreHasThemLoaded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Block pail = block("lunch_pail");
        Block card = block("prayer_card");
        Block planks = block("stalk_planks");
        List<BlockPos> pails = new ArrayList<>();
        int cards = 0;
        for (BlockPos column : columns(SHAFT_CHUNKS)) {
            // sync-load: whenBlocksLoaded loaded the chunks
            ChunkAccess chunk = level.getChunk(column);
            for (int y = LAYER.minY(); y < LAYER.topExclusive(); y++) {
                BlockState state = chunk.getBlockState(column.atY(y));
                if (state.is(pail)) {
                    pails.add(column.atY(y));
                } else if (state.is(card)) {
                    cards++;
                }
            }
        }
        int throughCaves = 0;
        for (BlockPos foot : pails) {
            int ladders = 0;
            int stubs = 0;
            int timber = 0;
            int caveCells = 0;
            // sync-load: whenBlocksLoaded loaded the chunks (the shafts lie in the sample)
            ChunkAccess chunk = level.getChunk(foot);
            for (BlockPos at : BlockPos.betweenClosed(foot.offset(-8, 0, -8), foot.offset(8, 32, 8))) {
                if (!ChunkPos.containing(at).equals(chunk.getPos())) {
                    continue;
                }
                BlockState state = chunk.getBlockState(at);
                if (state.is(Blocks.LADDER)) {
                    ladders++;
                } else if (state.is(Blocks.CANDLE)) {
                    expect(helper, !state.getValue(CandleBlock.LIT), "a candle stub at %s is lit", at.toShortString());
                    stubs++;
                } else if (state.is(planks)) {
                    timber++;
                }
            }
            // A cave through the shaft: open blocks five or six out from its ladder, at the rows the ladder runs,
            // which is past the shaft's walls, niches and sealed shell; the drift, at the foot's rows, is left out.
            for (BlockPos rung : BlockPos.betweenClosed(foot.offset(-6, 3, -6), foot.offset(6, 32, 6))) {
                if (!ChunkPos.containing(rung).equals(chunk.getPos())
                        || !chunk.getBlockState(rung).is(Blocks.LADDER)) {
                    continue;
                }
                for (BlockPos at : BlockPos.betweenClosed(rung.offset(-6, 0, -6), rung.offset(6, 0, 6))) {
                    int out = Math.max(Math.abs(at.getX() - rung.getX()), Math.abs(at.getZ() - rung.getZ()));
                    if (out >= 5
                            && ChunkPos.containing(at).equals(chunk.getPos())
                            && chunk.getBlockState(at).isAir()) {
                        caveCells++;
                    }
                }
            }
            LOGGER.info(
                    "[the-claim] shaft with its pail at {}: {} rungs, {} candle stubs, {} timber, {} cave blocks by it",
                    foot.toShortString(),
                    ladders,
                    stubs,
                    timber,
                    caveCells);
            expect(helper, ladders >= 3, "the shaft at %s has %s rungs of ladder", foot.toShortString(), ladders);
            expect(helper, stubs >= 1, "the shaft at %s has no candle stub", foot.toShortString());
            expect(helper, timber >= 4, "the shaft at %s has %s blocks of timber", foot.toShortString(), timber);
            if (caveCells >= 3) {
                throughCaves++;
            }
        }
        LOGGER.info("[the-claim] {} shafts, {} prayer cards, {} through caves", pails.size(), cards, throughCaves);
        expect(helper, pails.size() >= 4, "%s shafts in %s chunks", pails.size(), SHAFT_CHUNKS * SHAFT_CHUNKS);
        expect(helper, cards >= 1, "no prayer card in %s shafts", pails.size());
        expect(helper, throughCaves >= 1, "none of %s shafts cuts through a cave", pails.size());
        helper.succeed();
    }

    /**
     * Each of the Claim's features stays in its chunk (ADR 0088): every position each placement step picks for a chunk
     * lies in that chunk, and every block a shaft may write, its shell included, lies in the chunk its placement
     * picked.
     */
    @GameTest(
            environment = CampaignEnvironments.C,
            dimension = FULL,
            maxTicks = FarChunks.AWAIT_BUDGET_TICKS + MAX_TICKS)
    public void theClaimsFeaturesStayInTheirChunks(GameTestHelper helper) {
        FarChunks.whenBlocksLoaded(
                helper, sampleChunks(helper, SAMPLE_CHUNKS), () -> theClaimsFeaturesStayInTheirChunksLoaded(helper));
    }

    private static void theClaimsFeaturesStayInTheirChunksLoaded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var registry = level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
        for (Map.Entry<String, Integer> entry : MIN_KEPT.entrySet()) {
            String name = entry.getKey();
            int kept = 0;
            PlacedFeature feature = registry.getValueOrThrow(ResourceKey.create(
                    Registries.PLACED_FEATURE, Identifier.fromNamespaceAndPath("deepcharter", name)));
            for (int dx = 0; dx < SAMPLE_CHUNKS; dx++) {
                for (int dz = 0; dz < SAMPLE_CHUNKS; dz++) {
                    ChunkPos chunk = new ChunkPos(SAMPLE_CHUNK + dx, SAMPLE_CHUNK + dz);
                    // sync-load: whenBlocksLoaded loaded the chunks
                    level.getChunk(chunk.x(), chunk.z());
                    PlacementContext context =
                            new PlacementContext(level, level.getChunkSource().getGenerator(), Optional.of(feature));
                    RandomSource random = RandomSource.create(chunk.pack() ^ name.hashCode());
                    List<BlockPos> picked = List.of(chunk.getWorldPosition());
                    for (PlacementModifier modifier : feature.placement()) {
                        List<BlockPos> next = new ArrayList<>();
                        for (BlockPos pos : picked) {
                            modifier.modify(context, random, pos, next::add);
                        }
                        for (BlockPos pos : next) {
                            expect(
                                    helper,
                                    ChunkPos.containing(pos).equals(chunk),
                                    "%s placed for chunk %s picks %s, in chunk %s",
                                    name,
                                    chunk,
                                    pos.toShortString(),
                                    ChunkPos.containing(pos));
                        }
                        picked = next;
                    }
                    kept += picked.size();
                }
            }
            int fewest = entry.getValue();
            LOGGER.info("[the-claim] {} keeps {} positions in {} chunks", name, kept, SAMPLE_CHUNKS * SAMPLE_CHUNKS);
            expect(
                    helper,
                    kept >= fewest,
                    "%s keeps %s positions in %s chunks, expected at least %s, so its in-chunk check is all but vacuous",
                    name,
                    kept,
                    SAMPLE_CHUNKS * SAMPLE_CHUNKS,
                    fewest);
        }
        ClaimShaftFeature shaft = (ClaimShaftFeature) registry.getValueOrThrow(ResourceKey.create(
                        Registries.PLACED_FEATURE, Identifier.fromNamespaceAndPath("deepcharter", "claim_shafts")))
                .feature()
                .value();
        RandomSource random = RandomSource.create(1277);
        for (int i = 0; i < 4096; i++) {
            BlockPos origin = new BlockPos(random.nextInt(4096) - 2048, -180, random.nextInt(4096) - 2048);
            ChunkPos chunk = ChunkPos.containing(origin);
            BoundingBox bounds = shaft.plan(random, origin).bounds();
            expect(
                    helper,
                    ChunkPos.containing(new BlockPos(bounds.minX(), 0, bounds.minZ()))
                                    .equals(chunk)
                            && ChunkPos.containing(new BlockPos(bounds.maxX(), 0, bounds.maxZ()))
                                    .equals(chunk),
                    "a shaft placed in chunk %s reaches %s",
                    chunk,
                    bounds);
        }
        helper.succeed();
    }

    /** The stratum a block of layer rock is, or an ore's host; null for anything else. */
    private static @Nullable String stratumOf(BlockState state) {
        if (state.getBlock() instanceof OreBlock) {
            return state.getValue(OreBlock.HOST).getSerializedName();
        }
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return STRATA.contains(id)
                        && BuiltInRegistries.BLOCK
                                .getKey(state.getBlock())
                                .getNamespace()
                                .equals("deepcharter")
                ? id
                : null;
    }

    /**
     * The highest block of layer 1's rock in the column, under the open room at the top of the band; the search starts
     * under the surface band's floor, whose last rows may reach into the band.
     */
    private static int rockTop(ChunkAccess chunk, BlockPos column) {
        for (int y = LAYER.topExclusive() - 8; y >= LAYER.minY(); y--) {
            if (!chunk.getBlockState(column.atY(y)).isAir()) {
                return y;
            }
        }
        return LAYER.minY();
    }

    /** Every column of a square of {@code chunks} chunks from the sample's corner. */
    private static List<BlockPos> columns(int chunks) {
        List<BlockPos> out = new ArrayList<>();
        for (int x = 0; x < chunks * 16; x++) {
            for (int z = 0; z < chunks * 16; z++) {
                out.add(new BlockPos((SAMPLE_CHUNK << 4) + x, 0, (SAMPLE_CHUNK << 4) + z));
            }
        }
        return out;
    }

    private static Block block(String path) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("deepcharter", path));
    }

    private static String percent(double share) {
        return String.format(Locale.ROOT, "%.2f%%", 100 * share);
    }

    /** The chunks of a square of {@code chunks} chunks from the sample's corner. */
    private static List<FarChunks.At> sampleChunks(GameTestHelper helper, int chunks) {
        return FarChunks.rect(
                helper.getLevel(), SAMPLE_CHUNK, SAMPLE_CHUNK, SAMPLE_CHUNK + chunks - 1, SAMPLE_CHUNK + chunks - 1);
    }
}
