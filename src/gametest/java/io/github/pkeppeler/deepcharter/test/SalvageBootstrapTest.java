package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.ColonyTuning;
import io.github.pkeppeler.deepcharter.hangar.HangarData;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.BlockSurvey;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TerminalParts;
import io.github.pkeppeler.deepcharter.test.support.WorldData;

/**
 * Server GameTests for the bootstrap of #245 (art direction section 11): the colony's salvage crates lie within the yard radius and not
 * beyond it, fungal stalks grow only on the floors of craters and in caves, and a new player reaches the first crafting goals (tools, a
 * light, the first terminal repair) with the real recipes, starting from nothing but what crates and stalks drop. The surface runs in
 * {@code deepcharter:surface_sample}, a copy of the shipped overworld in the test mod's preset (see {@link SurfaceTerrainTest}).
 */
public class SalvageBootstrapTest {
	private static final Logger LOGGER = LoggerFactory.getLogger(SalvageBootstrapTest.class);
	private static final String SAMPLE = "deepcharter:surface_sample";
	private static final String YARD = "deepcharter:salvage_yard";
	private static final int MAX_TICKS = 12_000;
	/** The survey covers the chunks within this many of the origin, a little past the yard so that its edge shows. */
	private static final int SURVEY_CHUNKS = 12;
	/** A crate stands up to the 4 blocks of its patch's spread, and the biome that decides the yard is read in cells of 4, past the radius. */
	private static final int EDGE_SLACK = 8;
	private static final int BIOME_CELL = 4;
	/** The crates at least this far out are beyond the colony pad (ADR 0036: flat to 56 blocks), so the crates are not only at the colony. */
	private static final int PAD_RADIUS = 56;
	/** What a survey of the sample world found with its fixed seed; the tests allow half of it. */
	private static final int CRATES_BEYOND_THE_PAD = 160;
	private static final int STALK_COLUMNS = 40;
	/** The walk from a crate to the nearest stalk column, in blocks: the survey of the fixed seed found 167 and 281 (a crate near the edge of the survey has the stalks beyond it out of count). */
	private static final int MEDIAN_WALK_TO_A_STALK = 200;
	private static final int MOST_WALK_TO_A_STALK = 320;
	/** A plains block's top is no lower than 61 (ground 64 less the rolling plains' 2.6), so a stalk's base this low or lower is a crater's. */
	private static final int HIGHEST_STALK_BASE = 60;
	/** How many crates a new player must break to reach the first goals, and the whole repair chain. */
	private static final int CRATES_FOR_FIRST_GOALS = 12;
	private static final int CRATES_FOR_ALL_REPAIRS = 60;
	/** The iron of the first goals: the pump motor takes 3, the fuel valve 1 and the lantern 1 (nine nuggets from an ingot); copper: 2 and 2. */
	private static final int IRON_FOR_FIRST_GOALS = 5;
	private static final int COPPER_FOR_FIRST_GOALS = 4;

	/** The grids of the ten terminal parts (data/deepcharter/recipe), two rows of three. */
	private static final Map<String, String[]> PART_GRIDS = Map.of(
			"pump_motor", new String[] {"III", "CRC"}, "fuel_valve", new String[] {"CIC", " R "},
			"smelter_coil", new String[] {"CCC", "RIR"}, "crusher_gear", new String[] {"ICI", "III"}, "ore_feeder", new String[] {"IRI", "CCC"},
			"upgrade_board", new String[] {"RCR", "CRC"}, "socket_array", new String[] {"ICI", "ICI"},
			"welding_arm", new String[] {"IIC", "RI "}, "servo_unit", new String[] {"CRC", "III"}, "repair_circuit", new String[] {"RRR", "CIC"});

	/** What a wooden block burns for: the game's cooking time for wood blocks, 1.5 items to a smelt. */
	private static final int WOOD_BURN_TICKS = 300;

	private static Block block(String path) {
		return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
	}

	private static Item item(String path) {
		return BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
	}

	/** The yard's outer radius, read from the shipped density function that picks it: a tuning value. */
	static int yardRadius() {
		return yardBound("max_exclusive");
	}

	/** The yard's inner radius: no crate stands nearer the colony, whose pad and graded margin it clears. */
	static int yardInnerRadius() {
		return yardBound("min_inclusive");
	}

	private static int yardBound(String key) {
		try (var stream = SalvageBootstrapTest.class.getResourceAsStream("/data/deepcharter/worldgen/density_function/surface/yard.json")) {
			JsonObject yard = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
			return yard.get(key).getAsInt();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The lowest block of each column of stalk blocks. */
	private static List<BlockPos> columnBases(List<BlockPos> stalks) {
		Set<BlockPos> all = new LinkedHashSet<>(stalks);
		return stalks.stream().filter(pos -> !all.contains(pos.below())).toList();
	}

	/** The id of the biome at a position, generating its chunk. */
	private static String biomeAt(ServerLevel level, BlockPos pos) {
		level.getChunkAt(pos);
		return level.getBiome(pos).unwrapKey().orElseThrow().identifier().toString();
	}

	private static double distance(BlockPos pos) {
		return Math.sqrt((double) pos.getX() * pos.getX() + (double) pos.getZ() * pos.getZ());
	}

	@GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
	public void cratesLieAroundTheColonyWithinTheYardRadiusAndNoFurther(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		int radius = yardRadius();
		List<BlockPos> crates = BlockSurvey.find(level, SURVEY_CHUNKS, block("salvage_crate"));
		List<BlockPos> beyondThePad = crates.stream().filter(pos -> distance(pos) >= PAD_RADIUS).toList();
		double farthest = crates.stream().mapToDouble(SalvageBootstrapTest::distance).max().orElse(0);
		int inner = yardInnerRadius();
		double nearestOutside = crates.stream().filter(pos -> distance(pos) >= radius + EDGE_SLACK).count();
		long insideTheRing = crates.stream().filter(pos -> distance(pos) < inner - EDGE_SLACK).count();
		int padCorner = (int) Math.ceil(ColonyTuning.DEFAULT.padSize() / 2.0 * Math.sqrt(2));
		if (inner - EDGE_SLACK < padCorner + ColonyTuning.DEFAULT.edgeMargin()) {
			throw helper.assertionException("the yard starts at %s, so a crate can stand %s from the centre: the pad's corner is at %s and its graded margin is %s more",
					inner, inner - EDGE_SLACK, padCorner, ColonyTuning.DEFAULT.edgeMargin());
		}
		if (insideTheRing > 0) {
			throw helper.assertionException("%s crates stand nearer than %s blocks to the colony, inside its pad or graded margin", insideTheRing, inner - EDGE_SLACK);
		}
		LOGGER.info("[salvage] yard radius {}: {} crates, {} beyond the pad, the farthest at {} blocks", radius, crates.size(), beyondThePad.size(), farthest);
		if (beyondThePad.size() < CRATES_BEYOND_THE_PAD / 2) {
			throw helper.assertionException("the yard holds %s crates beyond the colony pad, expected at least %s", beyondThePad.size(), CRATES_BEYOND_THE_PAD / 2);
		}
		if (nearestOutside > 0) {
			throw helper.assertionException("%s crates stand more than %s blocks past the yard radius %s; the farthest is at %s", (int) nearestOutside, EDGE_SLACK,
					radius, farthest);
		}
		if (farthest < radius - EDGE_SLACK * 2) {
			throw helper.assertionException("the crates stop at %s blocks, well inside the yard radius %s", farthest, radius);
		}
		helper.succeed();
	}

	/** The yard's edges are read in biome cells of 4 blocks, at the corner of each, so it is exact to a cell: a block a cell and a block inside is in, a cell outside is out. */
	@GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
	public void theYardEndsAtItsRadiusOnTheBlock(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		int radius = yardRadius();
		for (int[] direction : new int[][] {{1, 0}, {0, -1}, {-1, 0}}) {
			BlockPos inside = new BlockPos(direction[0] * (radius - BIOME_CELL - 1), 64, direction[1] * (radius - BIOME_CELL - 1));
			BlockPos outside = new BlockPos(direction[0] * (radius + BIOME_CELL), 64, direction[1] * (radius + BIOME_CELL));
			int inner = yardInnerRadius();
			BlockPos within = new BlockPos(direction[0] * (inner + 2 * BIOME_CELL), 64, direction[1] * (inner + 2 * BIOME_CELL));
			BlockPos without = new BlockPos(direction[0] * (inner - 2 * BIOME_CELL), 64, direction[1] * (inner - 2 * BIOME_CELL));
			if (!biomeAt(level, within).equals(YARD) || biomeAt(level, without).equals(YARD)) {
				throw helper.assertionException("the yard should begin between %s and %s", without.toShortString(), within.toShortString());
			}
			String insideBiome = biomeAt(level, inside);
			String outsideBiome = biomeAt(level, outside);
			if (!insideBiome.equals(YARD) || outsideBiome.equals(YARD)) {
				throw helper.assertionException("at %s the block %s is %s and the block %s is %s: the yard should hold the first and not the second",
						List.of(direction[0], direction[1]), inside.toShortString(), insideBiome, outside.toShortString(), outsideBiome);
			}
		}
		helper.succeed();
	}

	@GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
	public void stalksStandOnlyInCraters(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		List<BlockPos> stalks = BlockSurvey.find(level, SURVEY_CHUNKS, block("fungal_stalk"));
		List<BlockPos> bases = columnBases(stalks);
		LOGGER.info("[salvage] {} stalk blocks in {} columns", stalks.size(), bases.size());
		if (bases.size() < STALK_COLUMNS / 2) {
			throw helper.assertionException("the survey found %s stalk columns, expected at least %s", bases.size(), STALK_COLUMNS / 2);
		}
		for (BlockPos base : bases) {
			if (base.getY() > HIGHEST_STALK_BASE) {
				throw helper.assertionException("a stalk stands at %s, above the floor of any crater (%s)", base.toShortString(), HIGHEST_STALK_BASE);
			}
			if (level.getBlockState(base.below()).isAir() || level.getBlockState(base.below()).is(block("fungal_stalk"))) {
				throw helper.assertionException("the stalk at %s does not stand on the ground", base.toShortString());
			}
		}
		helper.succeed();
	}

	/** How far a player walks from a crate to the nearest stalk column: the wood is a walk from the salvage, not a trek. */
	@GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
	public void aStalkColumnIsAWalkFromEveryCrate(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		List<BlockPos> bases = columnBases(BlockSurvey.find(level, SURVEY_CHUNKS, block("fungal_stalk")));
		List<BlockPos> crates = BlockSurvey.find(level, SURVEY_CHUNKS, block("salvage_crate"));
		double[] nearest = crates.stream().mapToDouble(crate -> bases.stream().mapToDouble(base -> Math.sqrt(horizontalDistanceSqr(crate, base))).min().orElse(Double.MAX_VALUE))
				.sorted().toArray();
		double nearestToTheOrigin = bases.stream().mapToDouble(SalvageBootstrapTest::distance).min().orElse(Double.MAX_VALUE);
		LOGGER.info("[salvage] {} stalk columns, the nearest {} blocks from the colony's centre; from a crate to the nearest column: least {}, median {}, most {} blocks ({} crates)",
				bases.size(), (int) nearestToTheOrigin, (int) nearest[0], (int) nearest[nearest.length / 2], (int) nearest[nearest.length - 1], nearest.length);
		if (nearest[nearest.length / 2] > MEDIAN_WALK_TO_A_STALK || nearest[nearest.length - 1] > MOST_WALK_TO_A_STALK) {
			throw helper.assertionException("a stalk column is a median %s and at most %s blocks from a crate, over the %s and %s allowed",
					(int) nearest[nearest.length / 2], (int) nearest[nearest.length - 1], MEDIAN_WALK_TO_A_STALK, MOST_WALK_TO_A_STALK);
		}
		helper.succeed();
	}

	private static double horizontalDistanceSqr(BlockPos a, BlockPos b) {
		return (double) (a.getX() - b.getX()) * (a.getX() - b.getX()) + (double) (a.getZ() - b.getZ()) * (a.getZ() - b.getZ());
	}

	@GameTest(dimension = "deepcharter:layer_1", maxTicks = MAX_TICKS)
	public void stalksGrowOnTheFloorsOfLayerOneCaves(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		List<BlockPos> stalks = BlockSurvey.find(level, 6, block("fungal_stalk"));
		LOGGER.info("[salvage] layer 1: {} stalk blocks", stalks.size());
		if (stalks.isEmpty()) {
			throw helper.assertionException("the caves of layer 1 hold no fungal stalk");
		}
		for (BlockPos pos : stalks) {
			if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
					&& !level.getBlockState(pos.below()).is(block("fungal_stalk"))) {
				throw helper.assertionException("the stalk at %s does not stand on a floor", pos.toShortString());
			}
		}
		helper.succeed();
	}

	/** The items a block drops, from its real loot table with a fixed seed: the same drops on every run. */
	static List<ItemStack> drops(ServerLevel level, Block broken, long seed) {
		ResourceKey<LootTable> key = broken.getLootTable().orElseThrow();
		LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
		LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
				.withParameter(LootContextParams.BLOCK_STATE, broken.defaultBlockState()).withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
				.create(LootContextParamSets.BLOCK);
		return table.getRandomItems(params, seed);
	}

	/** What a player carries, by item: the crafting of the chain takes from it and puts back, so every step must be affordable. */
	private static final class Bag {
		private final ServerLevel level;
		private final Map<Item, Integer> held = new LinkedHashMap<>();

		Bag(ServerLevel level) {
			this.level = level;
		}

		void add(ItemStack stack) {
			if (stack.isEmpty()) {
				return;
			}
			held.merge(stack.getItem(), stack.getCount(), Integer::sum);
		}

		int count(Item what) {
			return held.getOrDefault(what, 0);
		}

		void take(Item what, int count, GameTestHelper helper, String forWhat) {
			if (count(what) < count) {
				throw helper.assertionException("%s needs %s %s and the bag holds %s", forWhat, count, what, count(what));
			}
			held.merge(what, -count, Integer::sum);
		}

		/** Crafts one go of a recipe: {@code cells} is the grid row by row, an empty string a hole, and the result is added to the bag. */
		ItemStack craft(GameTestHelper helper, String forWhat, int width, int height, Item... cells) {
			List<ItemStack> grid = new ArrayList<>();
			for (Item cell : cells) {
				if (cell != Items.AIR) {
					take(cell, 1, helper, forWhat);
				}
				grid.add(cell == Items.AIR ? ItemStack.EMPTY : new ItemStack(cell));
			}
			CraftingInput input = CraftingInput.of(width, height, grid);
			ItemStack result = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level)
					.map(holder -> holder.value().assemble(input)).orElseThrow(() -> helper.assertionException("%s: no recipe takes that grid", forWhat));
			add(result);
			return result;
		}

		/** Crafts a terminal part from the grid the handbook's recipe shows: I iron, C copper, R redstone. */
		void craftPart(GameTestHelper helper, String part) {
			String[] rows = PART_GRIDS.get(part);
			List<Item> cells = new ArrayList<>();
			for (String row : rows) {
				for (char cell : row.toCharArray()) {
					cells.add(cell == 'I' ? Items.IRON_INGOT : cell == 'C' ? Items.COPPER_INGOT : cell == 'R' ? Items.REDSTONE : Items.AIR);
				}
			}
			ItemStack made = craft(helper, "the " + part, 3, 2, cells.toArray(Item[]::new));
			if (made.getItem() != item(part)) {
				throw helper.assertionException("the grid of %s made %s", part, made);
			}
		}

		/** Melts one item in a furnace; the fuel burns from the bag, and the furnace is a real recipe lookup and a real fuel value. */
		void smelt(GameTestHelper helper, String forWhat, Item what, int times) {
			SingleRecipeInput input = new SingleRecipeInput(new ItemStack(what));
			var recipe = level.recipeAccess().getRecipeFor(RecipeType.SMELTING, input, level)
					.orElseThrow(() -> helper.assertionException("%s: no furnace recipe takes %s", forWhat, what));
			int needed = recipe.value().cookingTime() * times;
			while (needed > 0) {
				Item fuel = Stream.of(item("stalk_planks"), Items.OAK_PLANKS).filter(candidate -> count(candidate) > 0)
						.findFirst().orElseThrow(() -> helper.assertionException("%s: nothing left to burn", forWhat));
				if (!new ItemStack(fuel).has(DataComponents.COOKING_FUEL)) {
					throw helper.assertionException("%s does not burn in a furnace", fuel);
				}
				take(fuel, 1, helper, forWhat);
				needed -= WOOD_BURN_TICKS;
			}
			take(what, times, helper, forWhat);
			for (int i = 0; i < times; i++) {
				add(recipe.value().assemble(input));
			}
		}
	}

	/**
	 * The first crafting goals from salvage and stalks only. A new player has no tree, no ore and no tool: three stalks, some regolith
	 * rock from the ground, and the drops of {@value #CRATES_FOR_FIRST_GOALS} crates (each from its real loot table, a fixed seed). They craft
	 * with the game's real recipes: planks, sticks, a crafting table, the stone tools, a furnace, charcoal, a torch, a lantern, and the two
	 * parts of the fuel pump, then put the parts in the real terminal. The handbook's chapter 1 completes on the way, and so does the
	 * first repair. Nothing in the bag comes from a vanilla tree or ore.
	 */
	@GameTest(maxTicks = 200)
	public void aNewPlayerReachesTheFirstCraftingGoalsFromSalvageAndStalksAlone(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel level = helper.getLevel();
		WorldData.swap(server).with(RepairState.TYPE, new RepairState()).with(HangarData.TYPE, new HangarData()).run(() -> {
			MockPlayer mock = MockPlayers.join(helper, "Salvager");
			try {
				HandbookChaptersOneToFiveTest.foundCharter(helper, server, mock);
				ServerPlayer player = mock.player();
				Bag bag = new Bag(level);
				for (int stalk = 0; stalk < 3; stalk++) {
					drops(level, block("fungal_stalk"), stalk + 1).forEach(bag::add);
				}
				for (int rock = 0; rock < 20; rock++) {
					drops(level, block("regolith_rock"), rock + 1).forEach(bag::add);
				}
				for (int crate = 0; crate < CRATES_FOR_FIRST_GOALS; crate++) {
					drops(level, block("salvage_crate"), crate + 1).forEach(bag::add);
				}
				Set<String> done = new LinkedHashSet<>();
				List<String> chapterOne = HandbookChaptersOneToFiveTest.PINNED.get("deepcharter:welcome");

				// Wood: a crate's planks complete the first directive on their own.
				HandbookChaptersOneToFiveTest.give(player, Items.OAK_PLANKS);
				done.add(chapterOne.get(0));
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "a crate's planks", List.of(player), done);

				requireTagged(helper, bag.craft(helper, "pressing a stalk", 1, 1, item("fungal_stalk")), ItemTags.PLANKS, "pressed stalk");
				bag.craft(helper, "pressing a second stalk", 1, 1, item("fungal_stalk"));
				bag.craft(helper, "sticks", 1, 2, item("stalk_planks"), item("stalk_planks"));
				bag.craft(helper, "more sticks", 1, 2, item("stalk_planks"), item("stalk_planks"));
				bag.craft(helper, "a crafting table", 2, 2, item("stalk_planks"), item("stalk_planks"), item("stalk_planks"), item("stalk_planks"));
				HandbookChaptersOneToFiveTest.give(player, Items.CRAFTING_TABLE);
				done.add(chapterOne.get(1));

				Item rock = item("regolith_rock");
				Item stick = Items.STICK;
				bag.craft(helper, "a stone pickaxe", 3, 3, rock, rock, rock, Items.AIR, stick, Items.AIR, Items.AIR, stick, Items.AIR);
				bag.craft(helper, "a stone axe", 2, 3, rock, rock, rock, stick, Items.AIR, stick);
				bag.craft(helper, "a stone shovel", 1, 3, rock, stick, stick);
				bag.craft(helper, "a stone sword", 1, 3, rock, rock, stick);
				bag.craft(helper, "a stone hoe", 2, 3, rock, rock, Items.AIR, stick, Items.AIR, stick);
				for (Item tool : List.of(Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL, Items.STONE_SWORD, Items.STONE_HOE)) {
					if (bag.count(tool) != 1) {
						throw helper.assertionException("the stone tools did not all come out of the bench: %s is %s", tool, bag.count(tool));
					}
				}
				HandbookChaptersOneToFiveTest.give(player, Items.STONE_PICKAXE);
				done.add(chapterOne.get(2));

				bag.craft(helper, "a furnace", 3, 3, rock, rock, rock, rock, Items.AIR, rock, rock, rock, rock);

				// Scrap: the crates' metal, melted down. The first goals ask for 5 iron and 4 copper.
				HandbookChaptersOneToFiveTest.give(player, item("scrap_iron"));
				done.add(chapterOne.get(3));
				bag.smelt(helper, "melting scrap iron", item("scrap_iron"), IRON_FOR_FIRST_GOALS);
				bag.smelt(helper, "melting scrap copper", item("scrap_copper"), COPPER_FOR_FIRST_GOALS);
				HandbookChaptersOneToFiveTest.give(player, Items.IRON_INGOT);
				done.add(chapterOne.get(4));
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "chapter 1 from salvage and stalks", List.of(player), done);

				// Light: a stalk, burnt to charcoal, makes a torch; the iron makes a lantern round it.
				bag.smelt(helper, "charring a stalk", item("fungal_stalk"), 1);
				if (bag.count(Items.CHARCOAL) != 1) {
					throw helper.assertionException("a charred stalk should be one charcoal, the bag holds %s", bag.count(Items.CHARCOAL));
				}
				bag.craft(helper, "a torch", 1, 2, Items.CHARCOAL, stick);
				bag.craft(helper, "iron nuggets", 1, 1, Items.IRON_INGOT);
				bag.craft(helper, "a lantern", 3, 3, Items.IRON_NUGGET, Items.IRON_NUGGET, Items.IRON_NUGGET, Items.IRON_NUGGET, Items.TORCH, Items.IRON_NUGGET,
						Items.IRON_NUGGET, Items.IRON_NUGGET, Items.IRON_NUGGET);

				// The first terminal: its parts from the real recipes, in the real terminal.
				bag.craftPart(helper, "pump_motor");
				bag.craftPart(helper, "fuel_valve");
				BlockPos terminal = helper.absolutePos(new BlockPos(0, 1, 0));
				HandbookChaptersOneToFiveTest.stand(helper, mock, terminal);
				helper.getLevel().setBlock(terminal, TerminalTypes.FUEL_PUMP.block().defaultBlockState(), 3);
				for (Item part : TerminalTypes.FUEL_PUMP.parts()) {
					bag.take(part, 1, helper, "the fuel pump");
					player.getInventory().add(new ItemStack(part));
					HandbookChaptersOneToFiveTest.expectDone(helper, TerminalParts.insert(player, terminal, part), "putting " + part + " into the fuel pump");
				}
				done.add(HandbookChaptersOneToFiveTest.directive("back_online", "repair_fuel_pump"));
				HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "the first terminal", List.of(player), done);
				helper.succeed();
			} finally {
				mock.leave();
			}
		});
	}

	/**
	 * Every terminal's parts can be made from crates alone: the whole repair chain, not only the first terminal, is within reach of the
	 * {@value #CRATES_FOR_ALL_REPAIRS} crates of a bag, so no ore is needed at the surface.
	 */
	@GameTest(maxTicks = 200)
	public void theWholeRepairChainIsWithinReachOfTheCratesAlone(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Bag bag = new Bag(level);
		for (int crate = 0; crate < CRATES_FOR_ALL_REPAIRS; crate++) {
			drops(level, block("salvage_crate"), crate + 1).forEach(bag::add);
		}
		LOGGER.info("[salvage] {} crates hold {}", CRATES_FOR_ALL_REPAIRS, bag.held);
		bag.smelt(helper, "melting scrap iron", item("scrap_iron"), bag.count(item("scrap_iron")));
		bag.smelt(helper, "melting scrap copper", item("scrap_copper"), bag.count(item("scrap_copper")));
		for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, TerminalTypes.UPGRADE_TERMINAL, TerminalTypes.REPAIR_STATION)) {
			for (Item part : type.parts()) {
				bag.craftPart(helper, BuiltInRegistries.ITEM.getKey(part).getPath());
			}
		}
		helper.succeed();
	}

	private static void requireTagged(GameTestHelper helper, ItemStack stack, TagKey<Item> tag, String what) {
		if (!stack.is(tag)) {
			throw helper.assertionException("%s should be in %s, it is %s", what, tag.location(), stack);
		}
	}

	/** The step of generation at which the features of #245 are placed (vegetal decoration): its index in a biome's list of steps. */
	private static final int VEGETAL_STEP = 9;
	private static final Map<String, List<String>> VEGETAL_FEATURES = Map.of(
			"regolith_plains", List.of("deepcharter:fungal_stalks"), "mesa_country", List.of("deepcharter:fungal_stalks"),
			"basalt_field", List.of("deepcharter:fungal_stalks"), "salvage_yard", List.of("deepcharter:salvage_litter", "deepcharter:fungal_stalks"),
			"topsoil_claims", List.of("deepcharter:fungal_stalks_cave"));

	/** A world's chunks, loot and saved progress name these ids, so a rename is a decision (ADR 0047): each is pinned by its literal. */
	@GameTest
	public void theIdsThatWorldsAndLootNameArePinned(GameTestHelper helper) {
		for (String path : List.of("salvage_crate", "fungal_stalk", "stalk_planks")) {
			Identifier id = Identifier.fromNamespaceAndPath("deepcharter", path);
			if (!BuiltInRegistries.BLOCK.containsKey(id) || !BuiltInRegistries.ITEM.containsKey(id)) {
				throw helper.assertionException("%s should be a block and an item", id);
			}
		}
		for (String path : List.of("scrap_iron", "scrap_copper", "salvage_cloth")) {
			if (!BuiltInRegistries.ITEM.containsKey(Identifier.fromNamespaceAndPath("deepcharter", path))) {
				throw helper.assertionException("deepcharter:%s should be an item", path);
			}
		}
		var biomes = helper.getLevel().registryAccess().lookupOrThrow(Registries.BIOME);
		VEGETAL_FEATURES.forEach((biome, expected) -> {
			var steps = biomes.getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("deepcharter", biome))).value()
					.getGenerationSettings().features();
			List<String> listed = steps.get(VEGETAL_STEP).stream().map(feature -> feature.unwrapKey().orElseThrow().identifier().toString()).toList();
			if (!listed.equals(expected)) {
				throw helper.assertionException("%s should place %s at step %s, it places %s", biome, expected, VEGETAL_STEP, listed);
			}
		});
		helper.succeed();
	}

	@GameTest
	public void aStalkAloneCompletesTheFirstDirectiveOfTheHandbook(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer mock = MockPlayers.join(helper, "Woodcutter");
		try {
			HandbookChaptersOneToFiveTest.foundCharter(helper, server, mock);
			HandbookChaptersOneToFiveTest.give(mock.player(), item("stalk_planks"));
			HandbookChaptersOneToFiveTest.expectCompleted(helper, server, "a stalk plank", List.of(mock.player()),
					Set.of(HandbookChaptersOneToFiveTest.directive("welcome", "gather_wood")));
			helper.succeed();
		} finally {
			mock.leave();
		}
	}
}
