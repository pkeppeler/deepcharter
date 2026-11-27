package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.ColonyBuilder;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Server GameTests for the surface (#240): the generator makes regolith plains, craters, terraced mesas and basalt outcrops, with
 * no flora, fauna or water, and the colony pad is flat. The GameTest server bakes its overworld from a flat preset, so these run in
 * the surface band of {@code deepcharter:campaign_sample}, the test pack's copy of the shipped overworld (#803).
 */
public class SurfaceTerrainTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final String FULL = "deepcharter:campaign_full";
    /**
     * The top of the depth that campaign_sample leaves as air: two sections (32 rows) of the shipped rock stay under the last layer's
     * floor. A literal, because it is data in campaign_sample.json; a test pins it to the layers.
     */
    private static final int EMPTY_BELOW = -800;

    private static final int MAX_TICKS = 6000;
    /** The ground of the colony pad: the first block above it is free. */
    private static final int PAD_GROUND = 64;
    /** The pad is flattened out to a radius of 56 blocks; the check keeps clear of the ramp at the edge. */
    private static final int PAD_CHECK_RADIUS = 50;
    /** Out to this radius the land is still mostly the pad's: neighbouring columns differ by 2 blocks at most. */
    private static final int GENTLE_RADIUS = 80;

    private static final int GENTLE_STEP = 2;
    /** Out to this radius the land is on its way to the natural relief: a mesa terrace is 4 blocks and a rock knob up to 7. */
    private static final int CLIFF_CHECK_RADIUS = 110;

    private static final int BIGGEST_STEP = 5;
    /** A square of ground with no air cut into it. */
    private static final int SAMPLE_CHUNK_X = 10;

    private static final int SAMPLE_CHUNK_Z = 10;
    private static final int SAMPLE_CHUNK_RADIUS = 6;
    /** The great pit, south of the pad, in sight of the square. */
    private static final int PIT_X = 24;

    private static final int PIT_Z = 150;
    /** The rim stands this much higher than the floor, and this far from the middle. */
    private static final int PIT_DEPTH = 20;

    private static final int PIT_RIM_RADIUS = 59;
    /** Basalt shows on the knobs, so the column to look at stands this high; the rule paints basalt from 2 blocks above the pad up. */
    private static final int BASALT_MIN_RISE = 4;
    /**
     * What the survey found with the GameTest world's seed, which is fixed at 0 (every run reads the same columns): the share of columns
     * 2 or more blocks below the pad (craters, the pit's floor) and 4 or more above it (mesas, rims and knobs). A test allows 50% either way.
     */
    private static final double CRATER_SHARE = 0.0235;

    private static final double MESA_SHARE = 0.307;
    private static final int SURVEY_SPAN = 2048;
    private static final int SURVEY_STEP = 8;

    /** The base height of each column asked for, by {@code x << 32 | z}: see {@link #height}. */
    private static final Map<Long, Integer> HEIGHTS = new ConcurrentHashMap<>();

    /** The surface band has no vanilla stone: its rock is the regolith's own, down to the band's floor. */
    private static final Set<String> GROUND_BLOCKS =
            Set.of("deepcharter:regolith", "deepcharter:regolith_packed", "deepcharter:regolith_rock");

    private static final Set<String> RARE_BLOCKS = Set.of("deepcharter:ochre_regolith", "deepcharter:basalt_outcrop");
    /** Placed on the ground by the surface features (#245), not by the material rule. */
    private static final Set<String> FEATURE_BLOCKS = Set.of("deepcharter:salvage_crate", "deepcharter:fungal_stalk");

    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theGroundHoldsOnlyRegolithRockAndBasalt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LayerChain.Band surface = TestLayers.band(level, TestLayers.SURFACE);
        Set<String> found = new TreeSet<>();
        for (int chunkX = SAMPLE_CHUNK_X - SAMPLE_CHUNK_RADIUS;
                chunkX <= SAMPLE_CHUNK_X + SAMPLE_CHUNK_RADIUS;
                chunkX++) {
            for (int chunkZ = SAMPLE_CHUNK_Z - SAMPLE_CHUNK_RADIUS;
                    chunkZ <= SAMPLE_CHUNK_Z + SAMPLE_CHUNK_RADIUS;
                    chunkZ++) {
                // As far as the features, not to a full chunk: a tall world's full chunks cost seconds each, and a 13
                // x 13
                // square of them held the server tick (and the deadlines of the tests beside this one) for a minute.
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FEATURES);
                LevelChunkSection[] sections = chunk.getSections();
                for (int index = 0; index < sections.length; index++) {
                    int sectionMinY = chunk.getSectionYFromSectionIndex(index) << 4;
                    // The campaign level holds the layers' rock under the surface band; only the band's sections are
                    // the surface's.
                    if (sectionMinY < surface.minY() || sectionMinY + 15 > surface.maxY()) {
                        continue;
                    }
                    // Counted from the blocks, not from the palette: a palette keeps the states a section once held,
                    // such as the campaign's default rock that the surface rule then replaced.
                    sections[index]
                            .getStates()
                            .count((state, count) -> found.add(BuiltInRegistries.BLOCK
                                    .getKey(state.getBlock())
                                    .toString()));
                }
            }
        }
        Set<String> allowed = new TreeSet<>(GROUND_BLOCKS);
        allowed.addAll(RARE_BLOCKS);
        allowed.addAll(FEATURE_BLOCKS);
        allowed.add("minecraft:air");
        Set<String> unexpected = new TreeSet<>(found);
        unexpected.removeAll(allowed);
        if (!unexpected.isEmpty()) {
            throw failure(
                    helper,
                    "the surface holds blocks it should not: %s, e.g. at %s",
                    unexpected,
                    firstOf(level, surface, unexpected));
        }
        Set<String> missing = new TreeSet<>(GROUND_BLOCKS);
        missing.removeAll(found);
        if (!missing.isEmpty()) {
            throw failure(helper, "the surface lacks %s; it holds %s", missing, found);
        }
        helper.succeed();
    }

    /** Where the first of each block of {@code wanted} stands in the sample's chunks, within the surface band. */
    private static Map<String, String> firstOf(ServerLevel level, LayerChain.Band surface, Set<String> wanted) {
        Map<String, String> first = new LinkedHashMap<>();
        for (int chunkX = SAMPLE_CHUNK_X - SAMPLE_CHUNK_RADIUS;
                chunkX <= SAMPLE_CHUNK_X + SAMPLE_CHUNK_RADIUS;
                chunkX++) {
            for (int chunkZ = SAMPLE_CHUNK_Z - SAMPLE_CHUNK_RADIUS;
                    chunkZ <= SAMPLE_CHUNK_Z + SAMPLE_CHUNK_RADIUS;
                    chunkZ++) {
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = surface.minY(); y <= surface.maxY(); y++) {
                            BlockPos pos = new BlockPos((chunkX << 4) + x, y, (chunkZ << 4) + z);
                            String id = BuiltInRegistries.BLOCK
                                    .getKey(chunk.getBlockState(pos).getBlock())
                                    .toString();
                            if (wanted.contains(id)) {
                                first.putIfAbsent(id, pos.toShortString());
                            }
                        }
                    }
                }
            }
        }
        return first;
    }

    // The motion-blocking heightmaps read the vanilla block tag minecraft:blocks_motion_no_leaves, not a block's
    // collision (#350).
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theMotionBlockingHeightmapsAgreeWithTheWorldSurface(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState random = level.getChunkSource().randomState();
        Map<String, BlockPos> columns = new LinkedHashMap<>();
        columns.put("plains", surveyColumn(helper, level, "deepcharter:regolith_plains", 0));
        columns.put("mesa top", surveyColumn(helper, level, "deepcharter:mesa_country", 0));
        columns.put("basalt", surveyColumn(helper, level, "deepcharter:basalt_field", BASALT_MIN_RISE));
        columns.put("crater floor", craterColumn(helper, generator, level, random));
        columns.put("colony plateau", new BlockPos(0, PAD_GROUND, 0));
        columns.put("colony plateau edge", new BlockPos(PAD_CHECK_RADIUS, PAD_GROUND, -PAD_CHECK_RADIUS));
        columns.put("west of the plateau", new BlockPos(-60, PAD_GROUND, 0));
        for (Map.Entry<String, BlockPos> column : columns.entrySet()) {
            BlockPos at = column.getValue();
            level.getChunk(at.getX() >> 4, at.getZ() >> 4);
            int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, at.getX(), at.getZ());
            int blocking = level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ());
            int blockingNoLeaves = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
            if (blocking != surface || blockingNoLeaves != surface) {
                throw failure(
                        helper,
                        "%s at %d %d: WORLD_SURFACE %d, MOTION_BLOCKING %d, MOTION_BLOCKING_NO_LEAVES %d, expected all"
                                + " equal",
                        column.getKey(),
                        at.getX(),
                        at.getZ(),
                        surface,
                        blocking,
                        blockingNoLeaves);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void everyDeepCharterBlockWithCollisionBlocksMotionInTheHeightmaps(GameTestHelper helper) {
        Set<String> missing = new TreeSet<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            if (!id.getNamespace().equals(DeepCharter.MOD_ID)) {
                continue;
            }
            boolean collides = block.getStateDefinition().getPossibleStates().stream()
                    .anyMatch(state -> !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                            .isEmpty());
            if (collides && !block.defaultBlockState().is(BlockTags.BLOCKS_MOTION_NO_LEAVES)) {
                missing.add(id.toString());
            }
        }
        if (!missing.isEmpty()) {
            throw failure(
                    helper,
                    "these blocks have collision but the heightmaps skip them; add them to"
                            + " src/main/resources/data/minecraft/tags/block/blocks_motion_no_leaves.json: %s",
                    missing);
        }
        helper.succeed();
    }

    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void everyBiomeHasItsOwnTopBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        expectTopBlock(helper, level, "deepcharter:regolith_plains", 0, "deepcharter:regolith");
        expectTopBlock(helper, level, "deepcharter:mesa_country", 0, "deepcharter:ochre_regolith");
        expectTopBlock(helper, level, "deepcharter:basalt_field", BASALT_MIN_RISE, "deepcharter:basalt_outcrop");
        helper.succeed();
    }

    /** The surface stays empty (#915, ADR 0036): no category of any surface biome lists a creature, monsters included. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void noCreatureSpawnsInTheSurfaceBiomes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        for (String biome : Arrays.asList(
                "deepcharter:regolith_plains",
                "deepcharter:mesa_country",
                "deepcharter:basalt_field",
                "deepcharter:salvage_yard")) {
            BlockPos column = surveyColumn(helper, level, biome, 0);
            level.getChunkAt(column);
            for (MobCategory category : MobCategory.values()) {
                int listed = generator
                        .getMobsAt(level, level.structureManager(), category, column)
                        .unwrap()
                        .size();
                if (listed != 0) {
                    throw failure(
                            helper,
                            "%s at %s: category %s lists %d creatures",
                            biome,
                            column.toShortString(),
                            category,
                            listed);
                }
            }
        }
        helper.succeed();
    }

    @GameTest
    public void aFreshWorldHasNoWanderingTraders(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        if (server.getGameRules().get(GameRules.SPAWN_WANDERING_TRADERS)) {
            throw failure(
                    helper,
                    "the wandering trader spawns in a fresh world: its spawner ignores the biomes, so the rule must be"
                            + " off");
        }
        helper.succeed();
    }

    /** The spawners that ignore a biome's list are off in a fresh world; {@code spawn_monsters} stays on, or the Retained would stop too. */
    @GameTest
    public void aFreshWorldHasNoPatrolsPhantomsOrWardensButKeepsMonsterSpawning(GameTestHelper helper) {
        GameRules rules = helper.getLevel().getServer().getGameRules();
        if (rules.get(GameRules.SPAWN_PATROLS)
                || rules.get(GameRules.SPAWN_PHANTOMS)
                || rules.get(GameRules.SPAWN_WARDENS)) {
            throw failure(helper, "a fresh world spawns patrols, phantoms or wardens: no biome list reaches them");
        }
        if (!rules.get(GameRules.SPAWN_MONSTERS)) {
            throw failure(helper, "spawn_monsters is off in a fresh world, and the Retained would not spawn either");
        }
        helper.succeed();
    }

    @GameTest
    public void aPlayerWhoTurnsWanderingTradersBackOnKeepsThem(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        server.getGameRules().set(GameRules.SPAWN_WANDERING_TRADERS, true, server);
        try {
            if (ColonyBuilder.buildIfNeeded(server) || !server.getGameRules().get(GameRules.SPAWN_WANDERING_TRADERS)) {
                throw failure(
                        helper,
                        "a start of a world that has its colony changed the wandering trader rule the player set");
            }
        } finally {
            server.getGameRules().set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
        }
        helper.succeed();
    }

    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void cratersMesasAndTheGreatPitAreOnTheLand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int[] heights = survey(level);
        int craters = 0;
        int mesas = 0;
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int height : heights) {
            int rise = height - PAD_GROUND;
            craters += rise <= -2 ? 1 : 0;
            mesas += rise >= 4 ? 1 : 0;
            lowest = Math.min(lowest, rise);
            highest = Math.max(highest, rise);
        }
        double craterShare = (double) craters / heights.length;
        double mesaShare = (double) mesas / heights.length;
        if (craterShare < CRATER_SHARE * 0.5 || craterShare > CRATER_SHARE * 1.5) {
            throw failure(
                    helper,
                    "craters are %.1f%% of the columns, expected %.1f%% to %.1f%%",
                    craterShare * 100,
                    CRATER_SHARE * 50,
                    CRATER_SHARE * 150);
        }
        if (mesaShare < MESA_SHARE * 0.5 || mesaShare > MESA_SHARE * 1.5) {
            throw failure(
                    helper,
                    "mesas are %.1f%% of the columns, expected %.1f%% to %.1f%%",
                    mesaShare * 100,
                    MESA_SHARE * 50,
                    MESA_SHARE * 150);
        }
        if (lowest > -5 || highest < 9) {
            throw failure(
                    helper,
                    "the land runs from %d to %d blocks about the pad: expected a crater at least 5 deep and a mesa at"
                            + " least 9 high",
                    lowest,
                    highest);
        }
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState random = level.getChunkSource().randomState();
        int floor = height(generator, level, random, PIT_X, PIT_Z);
        int rim = Integer.MIN_VALUE;
        for (int step = 0; step < 8; step++) {
            double angle = step * Math.PI / 4;
            rim = Math.max(
                    rim,
                    height(
                            generator,
                            level,
                            random,
                            PIT_X + (int) Math.round(PIT_RIM_RADIUS * Math.cos(angle)),
                            PIT_Z + (int) Math.round(PIT_RIM_RADIUS * Math.sin(angle))));
        }
        if (rim - floor < PIT_DEPTH) {
            throw failure(
                    helper,
                    "the great pit at %d %d has its floor at %d and its rim at %d, expected the rim %d higher",
                    PIT_X,
                    PIT_Z,
                    floor,
                    rim,
                    PIT_DEPTH);
        }
        helper.succeed();
    }

    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theColonyPadIsFlatAndTheLandAroundItHasNoSheerCliffs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState random = level.getChunkSource().randomState();
        // Each column is asked once (a column is the neighbour of two others): a height query in the tall world costs
        // a quarter of a millisecond, and this test holds the server tick while it runs.
        int side = 2 * CLIFF_CHECK_RADIUS + 2;
        int[][] heights = new int[side][side];
        // A row at a time on the common pool: a height query reads no level state, and the server thread holds the
        // batch while it waits.
        IntStream.range(0, side).parallel().forEach(i -> {
            for (int j = 0; j < side; j++) {
                heights[i][j] = height(generator, level, random, i - CLIFF_CHECK_RADIUS, j - CLIFF_CHECK_RADIUS);
            }
        });
        for (int x = -CLIFF_CHECK_RADIUS; x <= CLIFF_CHECK_RADIUS; x++) {
            for (int z = -CLIFF_CHECK_RADIUS; z <= CLIFF_CHECK_RADIUS; z++) {
                int height = heights[x + CLIFF_CHECK_RADIUS][z + CLIFF_CHECK_RADIUS];
                if (Math.hypot(x, z) <= PAD_CHECK_RADIUS && height != PAD_GROUND) {
                    throw failure(
                            helper,
                            "the pad is not flat: ground at %d %d is %d, expected %d",
                            x,
                            z,
                            height,
                            PAD_GROUND);
                }
                double distance = Math.hypot(x, z);
                if (distance <= CLIFF_CHECK_RADIUS - 1) {
                    int step = Math.max(
                            Math.abs(height - heights[x + 1 + CLIFF_CHECK_RADIUS][z + CLIFF_CHECK_RADIUS]),
                            Math.abs(height - heights[x + CLIFF_CHECK_RADIUS][z + 1 + CLIFF_CHECK_RADIUS]));
                    int allowed = distance <= GENTLE_RADIUS ? GENTLE_STEP : BIGGEST_STEP;
                    if (step > allowed) {
                        throw failure(
                                helper,
                                "a cliff of %d blocks at %d %d, %d out from the pad's middle, the biggest step allowed"
                                        + " there is %d",
                                step,
                                x,
                                z,
                                Math.round(distance),
                                allowed);
                    }
                }
            }
        }
        helper.succeed();
    }

    @GameTest
    public void everySurfaceBlockHasTwoToFourWeightedVariants(GameTestHelper helper) throws IOException {
        for (String id :
                Arrays.asList("regolith", "regolith_packed", "ochre_regolith", "regolith_rock", "basalt_outcrop")) {
            JsonArray variants = read(helper, "/assets/deepcharter/blockstates/" + id + ".json")
                    .getAsJsonObject()
                    .getAsJsonObject("variants")
                    .getAsJsonArray("");
            Set<String> models = new TreeSet<>();
            for (JsonElement variant : variants) {
                models.add(variant.getAsJsonObject().get("model").getAsString());
                if (variant.getAsJsonObject().get("weight").getAsInt() < 1) {
                    throw failure(helper, "%s has a variant with no weight", id);
                }
            }
            if (variants.size() < 2 || variants.size() > 4 || models.size() != variants.size()) {
                throw failure(
                        helper,
                        "%s has %d variants over %d models, expected 2 to 4 distinct ones",
                        id,
                        variants.size(),
                        models.size());
            }
        }
        helper.succeed();
    }

    // The GameTest server bakes its overworld from the flat preset, so the surface is tried in the test pack's copies
    // of the shipped overworld: campaign_full is an exact copy, and campaign_sample, where most tests run, differs
    // from it in one place (below). This keeps both copies, in the pack and in the preset, equal to the
    // shipped overworld.
    @GameTest
    public void theSampleDimensionsMatchTheShippedOverworld(GameTestHelper helper) throws IOException {
        JsonElement shipped = read(helper, "/data/minecraft/dimension/overworld.json");
        JsonObject presetDimensions = read(helper, "/data/minecraft/worldgen/world_preset/flat_all_dimensions.json")
                .getAsJsonObject()
                .getAsJsonObject("dimensions");
        if (!shipped.equals(presetDimensions.get(FULL))) {
            throw failure(helper, "flat_all_dimensions preset entry %s differs from the shipped overworld", FULL);
        }
        if (!shipped.equals(read(helper, "/data/deepcharter/dimension/campaign_full.json"))) {
            throw failure(helper, "the test pack's campaign_full differs from the shipped overworld");
        }
        // campaign_sample is the same world with its own noise settings, and nothing else.
        for (JsonElement sample : List.of(
                presetDimensions.get(SAMPLE), read(helper, "/data/deepcharter/dimension/campaign_sample.json"))) {
            JsonObject withShippedSettings = sample.deepCopy().getAsJsonObject();
            JsonObject generator = withShippedSettings.getAsJsonObject("generator");
            expect(
                    helper,
                    generator.get("settings").getAsString().equals("deepcharter:campaign_sample"),
                    "campaign_sample names the noise settings of the test pack");
            generator.addProperty("settings", "deepcharter:campaign");
            expect(helper, shipped.equals(withShippedSettings), "campaign_sample differs from the shipped overworld");
        }
        helper.succeed();
    }

    /**
     * The one place the test pack's campaign_sample noise settings differ from the shipped ones: below {@link #EMPTY_BELOW} the density
     * is air, so a chunk is not filled with 1500 rows of rock that no layer test reads (#823). Taking that one term out gives the shipped
     * settings back exactly, so the copy cannot drift from them.
     */
    @GameTest
    public void theSampleNoiseSettingsAreTheShippedOnesWithAnEmptyUnbuiltDepth(GameTestHelper helper)
            throws IOException {
        JsonObject shipped = read(helper, "/data/deepcharter/worldgen/noise_settings/campaign.json")
                .getAsJsonObject();
        JsonObject sample = read(helper, "/data/deepcharter/worldgen/noise_settings/campaign_sample.json")
                .getAsJsonObject();
        JsonObject choice = sample;
        for (String step : List.of("noise_router", "final_density", "left", "input", "input", "left")) {
            JsonElement next = choice.get(step);
            if (next == null || !next.isJsonObject()) {
                throw failure(
                        helper,
                        "campaign_sample.json's density was restructured: no object at \"%s\" on the path to the"
                                + " unbuilt depth",
                        step);
            }
            choice = next.getAsJsonObject();
        }
        JsonObject empty = choice.getAsJsonObject("when_in_range");
        expect(
                helper,
                empty.get("type").getAsString().equals("minecraft:range_choice")
                        && empty.get("input").getAsString().equals("minecraft:y")
                        && empty.get("min_inclusive").getAsInt() == CampaignBands.UNBUILT.minY()
                        && empty.get("max_exclusive").getAsInt() == EMPTY_BELOW
                        && empty.get("when_in_range").getAsDouble() == -1.0,
                "campaign_sample's density is not air from %d to %d",
                CampaignBands.UNBUILT.minY(),
                EMPTY_BELOW);
        choice.add("when_in_range", empty.get("when_out_of_range"));
        expect(helper, shipped.equals(sample), "campaign_sample's noise settings differ from the shipped ones");
        // The rock kept under the last layer's floor is what the unbuilt band keeps of the shipped rock.
        int lastLayerFloor = CampaignBands.layer(CampaignBands.lastLayer()).minY();
        expect(
                helper,
                EMPTY_BELOW == lastLayerFloor - 32,
                "EMPTY_BELOW is %d but must be 32 rows under the last layer's floor (%d): a layer was added, so move"
                        + " EMPTY_BELOW and campaign_sample.json's max_exclusive down to match",
                EMPTY_BELOW,
                lastLayerFloor - 32);
        helper.succeed();
    }

    private static void expectTopBlock(
            GameTestHelper helper, ServerLevel level, String biome, int minimumRise, String expected) {
        BlockPos column = surveyColumn(helper, level, biome, minimumRise);
        LevelChunk chunk = level.getChunkAt(column);
        // level-height: the level's top is the surface band's top, and the scan stops at the first ground
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(column.getX(), level.getMaxY(), column.getZ());
        while (chunk.getBlockState(pos).isAir()) {
            pos.move(Direction.DOWN);
        }
        BlockState state = chunk.getBlockState(pos);
        String actual = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (!actual.equals(expected)) {
            throw failure(
                    helper,
                    "the top block at %d %d in %s is %s, expected %s",
                    column.getX(),
                    column.getZ(),
                    biome,
                    actual,
                    expected);
        }
    }

    /**
     * A column whose biome, and the biome 8 blocks to each side of it, is {@code biome}, and whose ground stands at least
     * {@code minimumRise} blocks above the pad: the middle of a stretch of it.
     */
    private static BlockPos surveyColumn(GameTestHelper helper, ServerLevel level, String biome, int minimumRise) {
        RandomState random = level.getChunkSource().randomState();
        BiomeResolver resolver =
                level.getChunkSource().getGenerator().getBiomeSource().createCachingResolver(random);
        int[] around = {-8, 0, 8};
        for (int x = -SURVEY_SPAN / 2; x < SURVEY_SPAN / 2; x += SURVEY_STEP * 2) {
            for (int z = -SURVEY_SPAN / 2; z < SURVEY_SPAN / 2; z += SURVEY_STEP * 2) {
                boolean inside = true;
                for (int dx : around) {
                    for (int dz : around) {
                        inside &= biomeAt(resolver, x + dx, z + dz).equals(biome);
                    }
                }
                if (inside
                        && height(level.getChunkSource().getGenerator(), level, random, x, z)
                                >= PAD_GROUND + minimumRise) {
                    return new BlockPos(x, PAD_GROUND, z);
                }
            }
        }
        throw failure(helper, "no stretch of %s in the %d blocks around the origin", biome, SURVEY_SPAN);
    }

    /** A column in a crater: its ground is at least 2 blocks below the pad. */
    private static BlockPos craterColumn(
            GameTestHelper helper, ChunkGenerator generator, ServerLevel level, RandomState random) {
        for (int x = -SURVEY_SPAN / 2; x < SURVEY_SPAN / 2; x += SURVEY_STEP) {
            for (int z = -SURVEY_SPAN / 2; z < SURVEY_SPAN / 2; z += SURVEY_STEP) {
                if (height(generator, level, random, x, z) <= PAD_GROUND - 2) {
                    return new BlockPos(x, PAD_GROUND, z);
                }
            }
        }
        throw failure(helper, "no crater in the %d blocks around the origin", SURVEY_SPAN);
    }

    private static String biomeAt(BiomeResolver resolver, int x, int z) {
        Holder<Biome> holder = resolver.getNoiseBiome(x >> 2, PAD_GROUND >> 2, z >> 2);
        Optional<String> key =
                holder.unwrapKey().map(resourceKey -> resourceKey.identifier().toString());
        return key.orElseThrow(() -> new IllegalStateException("a surface biome with no key: " + holder));
    }

    private static int[] survey(ServerLevel level) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState random = level.getChunkSource().randomState();
        int side = SURVEY_SPAN / SURVEY_STEP;
        int[] heights = new int[side * side];
        IntStream.range(0, side).parallel().forEach(i -> {
            for (int j = 0; j < side; j++) {
                heights[i * side + j] = height(
                        generator,
                        level,
                        random,
                        -SURVEY_SPAN / 2 + i * SURVEY_STEP,
                        -SURVEY_SPAN / 2 + j * SURVEY_STEP);
            }
        });
        return heights;
    }

    private static int height(ChunkGenerator generator, ServerLevel level, RandomState random, int x, int z) {
        // One world, one seed: a column's height is the same in every test of this class, and the tests ask for the
        // same columns (the survey grid, the crater search, the pad), at a quarter of a millisecond each.
        return HEIGHTS.computeIfAbsent(
                (long) x << 32 | (z & 0xffffffffL),
                column -> generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random));
    }

    private static JsonElement read(GameTestHelper helper, String resource) throws IOException {
        var stream = SurfaceTerrainTest.class.getResourceAsStream(resource);
        if (stream == null) {
            throw failure(helper, "missing resource %s", resource);
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        }
    }
}
