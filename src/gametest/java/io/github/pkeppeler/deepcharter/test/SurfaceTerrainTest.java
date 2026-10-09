package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.ColonyBuilder;

/**
 * Server GameTests for the surface (#240): the generator makes regolith plains, craters, terraced mesas and basalt outcrops, with
 * no flora, fauna or water, and the colony pad is flat. The GameTest server bakes its overworld from a flat preset, so these run in
 * {@code deepcharter:surface_sample}, a copy of the shipped overworld in the test mod's preset.
 */
public class SurfaceTerrainTest {
	private static final String SAMPLE = "deepcharter:surface_sample";
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

	private static final Set<String> GROUND_BLOCKS = Set.of("minecraft:stone", "deepcharter:regolith", "deepcharter:regolith_packed",
			"deepcharter:regolith_rock");
	private static final Set<String> RARE_BLOCKS = Set.of("deepcharter:ochre_regolith", "deepcharter:basalt_outcrop");

	@GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
	public void theGroundHoldsOnlyRegolithRockAndBasalt(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Set<String> found = new TreeSet<>();
		for (int chunkX = SAMPLE_CHUNK_X - SAMPLE_CHUNK_RADIUS; chunkX <= SAMPLE_CHUNK_X + SAMPLE_CHUNK_RADIUS; chunkX++) {
			for (int chunkZ = SAMPLE_CHUNK_Z - SAMPLE_CHUNK_RADIUS; chunkZ <= SAMPLE_CHUNK_Z + SAMPLE_CHUNK_RADIUS; chunkZ++) {
				for (LevelChunkSection section : level.getChunk(chunkX, chunkZ).getSections()) {
					section.maybeHas(state -> {
						found.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
						return false;
					});
				}
			}
		}
		Set<String> allowed = new TreeSet<>(GROUND_BLOCKS);
		allowed.addAll(RARE_BLOCKS);
		allowed.add("minecraft:air");
		Set<String> unexpected = new TreeSet<>(found);
		unexpected.removeAll(allowed);
		if (!unexpected.isEmpty()) {
			throw fail(helper, "the surface holds blocks it should not: %s", unexpected);
		}
		Set<String> missing = new TreeSet<>(GROUND_BLOCKS);
		missing.removeAll(found);
		if (!missing.isEmpty()) {
			throw fail(helper, "the surface lacks %s; it holds %s", missing, found);
		}
		helper.succeed();
	}

	// The motion-blocking heightmaps read the vanilla block tag minecraft:blocks_motion_no_leaves, not a block's collision (#350).
	@GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
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
				throw fail(helper, "%s at %d %d: WORLD_SURFACE %d, MOTION_BLOCKING %d, MOTION_BLOCKING_NO_LEAVES %d, expected all equal",
						column.getKey(), at.getX(), at.getZ(), surface, blocking, blockingNoLeaves);
			}
		}
		helper.succeed();
	}

	@GameTest
	public void everyDeepCharterBlockWithCollisionBlocksMotionInTheHeightmaps(GameTestHelper helper) {
		Set<String> missing = new TreeSet<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			Identifier id = BuiltInRegistries.BLOCK.getKey(block);
			boolean collides = block.getStateDefinition().getPossibleStates().stream()
					.anyMatch(state -> !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty());
			if (id.getNamespace().equals(DeepCharter.MOD_ID) && collides && !block.defaultBlockState().is(BlockTags.BLOCKS_MOTION_NO_LEAVES)) {
				missing.add(id.toString());
			}
		}
		if (!missing.isEmpty()) {
			throw fail(helper, "these blocks have collision but the heightmaps skip them; add them to src/main/resources/data/minecraft/tags/block/blocks_motion_no_leaves.json: %s", missing);
		}
		helper.succeed();
	}

	@GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
	public void everyBiomeHasItsOwnTopBlock(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		expectTopBlock(helper, level, "deepcharter:regolith_plains", 0, "deepcharter:regolith");
		expectTopBlock(helper, level, "deepcharter:mesa_country", 0, "deepcharter:ochre_regolith");
		expectTopBlock(helper, level, "deepcharter:basalt_field", BASALT_MIN_RISE, "deepcharter:basalt_outcrop");
		helper.succeed();
	}

	@GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
	public void noCreatureSpawnsInTheSurfaceBiomesButMonstersStay(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		for (String biome : Arrays.asList("deepcharter:regolith_plains", "deepcharter:mesa_country", "deepcharter:basalt_field")) {
			BlockPos column = surveyColumn(helper, level, biome, 0);
			level.getChunkAt(column);
			for (MobCategory category : MobCategory.values()) {
				boolean empty = generator.getMobsAt(level, level.structureManager(), category, column).isEmpty();
				if (empty == (category == MobCategory.MONSTER)) {
					throw fail(helper, "%s at %s: category %s is %s", biome, column.toShortString(), category,
							empty ? "empty, and a night should bring monsters" : "not empty");
				}
			}
		}
		helper.succeed();
	}

	@GameTest
	public void aFreshWorldHasNoWanderingTraders(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		if (server.getGameRules().get(GameRules.SPAWN_WANDERING_TRADERS)) {
			throw fail(helper, "the wandering trader spawns in a fresh world: its spawner ignores the biomes, so the rule must be off");
		}
		helper.succeed();
	}

	@GameTest
	public void aPlayerWhoTurnsWanderingTradersBackOnKeepsThem(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		server.getGameRules().set(GameRules.SPAWN_WANDERING_TRADERS, true, server);
		try {
			if (ColonyBuilder.buildIfNeeded(server) || !server.getGameRules().get(GameRules.SPAWN_WANDERING_TRADERS)) {
				throw fail(helper, "a start of a world that has its colony changed the wandering trader rule the player set");
			}
		} finally {
			server.getGameRules().set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
		}
		helper.succeed();
	}

	@GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
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
			throw fail(helper, "craters are %.1f%% of the columns, expected %.1f%% to %.1f%%", craterShare * 100, CRATER_SHARE * 50, CRATER_SHARE * 150);
		}
		if (mesaShare < MESA_SHARE * 0.5 || mesaShare > MESA_SHARE * 1.5) {
			throw fail(helper, "mesas are %.1f%% of the columns, expected %.1f%% to %.1f%%", mesaShare * 100, MESA_SHARE * 50, MESA_SHARE * 150);
		}
		if (lowest > -5 || highest < 9) {
			throw fail(helper, "the land runs from %d to %d blocks about the pad: expected a crater at least 5 deep and a mesa at least 9 high", lowest, highest);
		}
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		RandomState random = level.getChunkSource().randomState();
		int floor = height(generator, level, random, PIT_X, PIT_Z);
		int rim = Integer.MIN_VALUE;
		for (int step = 0; step < 8; step++) {
			double angle = step * Math.PI / 4;
			rim = Math.max(rim, height(generator, level, random, PIT_X + (int) Math.round(PIT_RIM_RADIUS * Math.cos(angle)),
					PIT_Z + (int) Math.round(PIT_RIM_RADIUS * Math.sin(angle))));
		}
		if (rim - floor < PIT_DEPTH) {
			throw fail(helper, "the great pit at %d %d has its floor at %d and its rim at %d, expected the rim %d higher", PIT_X, PIT_Z, floor, rim, PIT_DEPTH);
		}
		helper.succeed();
	}

	@GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
	public void theColonyPadIsFlatAndTheLandAroundItHasNoSheerCliffs(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		RandomState random = level.getChunkSource().randomState();
		for (int x = -CLIFF_CHECK_RADIUS; x <= CLIFF_CHECK_RADIUS; x++) {
			for (int z = -CLIFF_CHECK_RADIUS; z <= CLIFF_CHECK_RADIUS; z++) {
				int height = height(generator, level, random, x, z);
				if (Math.hypot(x, z) <= PAD_CHECK_RADIUS && height != PAD_GROUND) {
					throw fail(helper, "the pad is not flat: ground at %d %d is %d, expected %d", x, z, height, PAD_GROUND);
				}
				double distance = Math.hypot(x, z);
				if (distance <= CLIFF_CHECK_RADIUS - 1) {
					int step = Math.max(Math.abs(height - height(generator, level, random, x + 1, z)),
							Math.abs(height - height(generator, level, random, x, z + 1)));
					int allowed = distance <= GENTLE_RADIUS ? GENTLE_STEP : BIGGEST_STEP;
					if (step > allowed) {
						throw fail(helper, "a cliff of %d blocks at %d %d, %d out from the pad's middle, the biggest step allowed there is %d",
								step, x, z, Math.round(distance), allowed);
					}
				}
			}
		}
		helper.succeed();
	}

	@GameTest
	public void everySurfaceBlockHasTwoToFourWeightedVariants(GameTestHelper helper) throws IOException {
		for (String id : Arrays.asList("regolith", "regolith_packed", "ochre_regolith", "regolith_rock", "basalt_outcrop")) {
			JsonArray variants = read(helper, "/assets/deepcharter/blockstates/" + id + ".json").getAsJsonObject()
					.getAsJsonObject("variants").getAsJsonArray("");
			Set<String> models = new TreeSet<>();
			for (JsonElement variant : variants) {
				models.add(variant.getAsJsonObject().get("model").getAsString());
				if (variant.getAsJsonObject().get("weight").getAsInt() < 1) {
					throw fail(helper, "%s has a variant with no weight", id);
				}
			}
			if (variants.size() < 2 || variants.size() > 4 || models.size() != variants.size()) {
				throw fail(helper, "%s has %d variants over %d models, expected 2 to 4 distinct ones", id, variants.size(), models.size());
			}
		}
		helper.succeed();
	}

	// The GameTest server bakes its overworld from the flat preset, so the surface is tried in a copy of the shipped overworld.
	@GameTest
	public void theSampleDimensionMatchesTheShippedOverworld(GameTestHelper helper) throws IOException {
		JsonElement shipped = read(helper, "/data/minecraft/dimension/overworld.json");
		JsonObject preset = read(helper, "/data/minecraft/worldgen/world_preset/flat_all_dimensions.json").getAsJsonObject();
		if (!shipped.equals(preset.getAsJsonObject("dimensions").get(SAMPLE))) {
			throw fail(helper, "flat_all_dimensions preset entry %s differs from the shipped overworld", SAMPLE);
		}
		helper.succeed();
	}

	private static void expectTopBlock(GameTestHelper helper, ServerLevel level, String biome, int minimumRise, String expected) {
		BlockPos column = surveyColumn(helper, level, biome, minimumRise);
		LevelChunk chunk = level.getChunkAt(column);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(column.getX(), level.getMaxY(), column.getZ());
		while (chunk.getBlockState(pos).isAir()) {
			pos.move(Direction.DOWN);
		}
		BlockState state = chunk.getBlockState(pos);
		String actual = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		if (!actual.equals(expected)) {
			throw fail(helper, "the top block at %d %d in %s is %s, expected %s", column.getX(), column.getZ(), biome, actual, expected);
		}
	}

	/**
	 * A column whose biome, and the biome 8 blocks to each side of it, is {@code biome}, and whose ground stands at least
	 * {@code minimumRise} blocks above the pad: the middle of a stretch of it.
	 */
	private static BlockPos surveyColumn(GameTestHelper helper, ServerLevel level, String biome, int minimumRise) {
		RandomState random = level.getChunkSource().randomState();
		BiomeResolver resolver = level.getChunkSource().getGenerator().getBiomeSource().createCachingResolver(random);
		int[] around = {-8, 0, 8};
		for (int x = -SURVEY_SPAN / 2; x < SURVEY_SPAN / 2; x += SURVEY_STEP * 2) {
			for (int z = -SURVEY_SPAN / 2; z < SURVEY_SPAN / 2; z += SURVEY_STEP * 2) {
				boolean inside = true;
				for (int dx : around) {
					for (int dz : around) {
						inside &= biomeAt(resolver, x + dx, z + dz).equals(biome);
					}
				}
				if (inside && height(level.getChunkSource().getGenerator(), level, random, x, z) >= PAD_GROUND + minimumRise) {
					return new BlockPos(x, PAD_GROUND, z);
				}
			}
		}
		throw fail(helper, "no stretch of %s in the %d blocks around the origin", biome, SURVEY_SPAN);
	}

	/** A column in a crater: its ground is at least 2 blocks below the pad. */
	private static BlockPos craterColumn(GameTestHelper helper, ChunkGenerator generator, ServerLevel level, RandomState random) {
		for (int x = -SURVEY_SPAN / 2; x < SURVEY_SPAN / 2; x += SURVEY_STEP) {
			for (int z = -SURVEY_SPAN / 2; z < SURVEY_SPAN / 2; z += SURVEY_STEP) {
				if (height(generator, level, random, x, z) <= PAD_GROUND - 2) {
					return new BlockPos(x, PAD_GROUND, z);
				}
			}
		}
		throw fail(helper, "no crater in the %d blocks around the origin", SURVEY_SPAN);
	}

	private static String biomeAt(BiomeResolver resolver, int x, int z) {
		Holder<Biome> holder = resolver.getNoiseBiome(x >> 2, PAD_GROUND >> 2, z >> 2);
		Optional<String> key = holder.unwrapKey().map(resourceKey -> resourceKey.identifier().toString());
		return key.orElseThrow(() -> new IllegalStateException("a surface biome with no key: " + holder));
	}

	private static int[] survey(ServerLevel level) {
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		RandomState random = level.getChunkSource().randomState();
		int side = SURVEY_SPAN / SURVEY_STEP;
		int[] heights = new int[side * side];
		for (int i = 0; i < side; i++) {
			for (int j = 0; j < side; j++) {
				heights[i * side + j] = height(generator, level, random, -SURVEY_SPAN / 2 + i * SURVEY_STEP, -SURVEY_SPAN / 2 + j * SURVEY_STEP);
			}
		}
		return heights;
	}

	private static int height(ChunkGenerator generator, ServerLevel level, RandomState random, int x, int z) {
		return generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random);
	}

	private static RuntimeException fail(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	private static JsonElement read(GameTestHelper helper, String resource) throws IOException {
		var stream = SurfaceTerrainTest.class.getResourceAsStream(resource);
		if (stream == null) {
			throw fail(helper, "missing resource %s", resource);
		}
		try (Reader reader = new InputStreamReader(stream)) {
			return JsonParser.parseReader(reader);
		}
	}
}
