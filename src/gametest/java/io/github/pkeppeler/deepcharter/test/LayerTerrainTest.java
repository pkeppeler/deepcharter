package io.github.pkeppeler.deepcharter.test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.layer.gen.ZoneBiomeSource;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/** Server GameTests for #54: layer terrain, zones and biomes, and hand versus drill on deep rock. #121 adds lava damage to pods. */
public class LayerTerrainTest {
	private static final Logger LOGGER = LoggerFactory.getLogger(LayerTerrainTest.class);

	/** Far layer chunks generate on worker threads, so a pod's own {@code tickCount} is the clock, with a big budget. */
	private static final int MAX_TICKS = 20000;
	private static final Input SPRINT = new Input(false, false, false, false, false, false, true);

	/** The zone names of lore canon section 11, top to bottom. */
	private static final String[][] ZONES = {
			{"topsoil_claims", "stone_benches", "deep_claim"},
			{"upper_levels", "shift_change", "prospectors_run"}};

	/** Server ticks seen for each pod a lava test marks. Fabric events cannot be unregistered, so this listens once. */
	private static final Map<UUID, Integer> LAVA_TICKS = new ConcurrentHashMap<>();
	private static final int LAVA_TEST_TICKS = 20;
	private static final float EPSILON = 1e-3f;

	static {
		PodEvents.AFTER_TICK.register(pod -> LAVA_TICKS.computeIfPresent(pod.getUUID(), (id, ticks) -> ticks + 1));
	}

	@GameTest
	public void theCrustFloorIsIntact(GameTestHelper helper) {
		int rows = LayerTuning.DEFAULT.crustThickness();
		for (int layer = 1; layer <= 2; layer++) {
			ServerLevel level = layer(helper, layer);
			int floor = level.getMinY();
			for (int x = 700; x < 748; x++) {
				for (int z = 700; z < 748; z++) {
					for (int dy = 0; dy < rows; dy++) {
						expectBlock(helper, level, new BlockPos(x, floor + dy, z), LayerBlocks.BREACH_CRUST);
					}
					// Solid rock under the crust, so no cave can open onto it.
					for (int dy = rows; dy < rows + 4; dy++) {
						BlockPos pos = new BlockPos(x, floor + dy, z);
						if (level.getBlockState(pos).isAir() || level.getBlockState(pos).is(LayerBlocks.BREACH_CRUST)) {
							throw failure(helper, "layer_%d %s should be rock under the crust, found %s", layer, pos, level.getBlockState(pos));
						}
					}
				}
			}
		}
		helper.succeed();
	}

	@GameTest
	public void terrainIsNoiseWithCaves(GameTestHelper helper) {
		for (int layer = 1; layer <= 2; layer++) {
			ServerLevel level = layer(helper, layer);
			int lowest = Integer.MAX_VALUE;
			int highest = Integer.MIN_VALUE;
			int caveCells = 0;
			for (int x = 900; x < 964; x += 2) {
				for (int z = 900; z < 964; z += 2) {
					level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL);
					int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
					lowest = Math.min(lowest, top);
					highest = Math.max(highest, top);
					for (int y = level.getMinY() + 12; y < top - 8; y++) {
						if (level.getBlockState(new BlockPos(x, y, z)).isAir()) {
							caveCells++;
						}
					}
				}
			}
			if (highest - lowest < 6) {
				throw failure(helper, "layer_%d terrain is flat: surface y %d to %d", layer, lowest, highest);
			}
			if (caveCells == 0) {
				throw failure(helper, "layer_%d has no caves in a 64 x 64 sample", layer);
			}
		}
		helper.succeed();
	}

	@GameTest
	public void thereAreNoNaturalSpawns(GameTestHelper helper) {
		for (int layer = 1; layer <= 2; layer++) {
			ServerLevel level = layer(helper, layer);
			for (int zone = 0; zone < 3; zone++) {
				BlockPos pos = new BlockPos(0, zoneMiddle(level, zone), 0);
				level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL);
				for (MobCategory category : MobCategory.values()) {
					var mobs = level.getChunkSource().getGenerator().getMobsAt(level, level.structureManager(), category, pos);
					if (!mobs.isEmpty()) {
						throw failure(helper, "layer_%d zone %d spawns %s mobs: %s", layer, zone, category, mobs);
					}
				}
			}
		}
		helper.succeed();
	}

	@GameTest
	public void zoneBoundsAreRight(GameTestHelper helper) {
		// Zone 0 is the top third. Layer 1 is 192 tall: thirds at 64 and 128. Layer 2 is 256 tall: 85.33 and 170.67.
		expectZones(helper, layer(helper, 1), new int[] {0, 63, 64, 127, 128, 191}, new int[] {2, 2, 1, 1, 0, 0});
		expectZones(helper, layer(helper, 2), new int[] {0, 85, 86, 170, 171, 255}, new int[] {2, 2, 1, 1, 0, 0});
		if (Zones.of(helper.getLevel().getServer().overworld(), 63).isPresent()) {
			throw failure(helper, "the surface has a zone");
		}
		for (int layer = 1; layer <= 2; layer++) {
			ServerLevel level = layer(helper, layer);
			for (int bad : new int[] {level.getMinY() - 1, level.getMaxY() + 1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
				if (Zones.of(level, bad).isPresent()) {
					throw failure(helper, "layer_%d y=%d is outside the layer and should have no zone", layer, bad);
				}
			}
		}
		helper.succeed();
	}

	/** A layer past the named ones has no zone, and never throws: callers pass any player Y every tick. */
	@GameTest
	public void anUnnamedLayerHasNoZone(GameTestHelper helper) {
		if (Zones.of(3, 0, 192, 100).isPresent() || Zones.of(0, 0, 192, 100).isPresent()) {
			throw failure(helper, "a layer without names has a zone");
		}
		if (Zones.of(1, 0, 192, 100).isEmpty()) {
			throw failure(helper, "layer 1 has no zone at y=100");
		}
		helper.succeed();
	}

	/** The names must cover every layer in the chain; this is the start-up check, run here as well. */
	@GameTest
	public void zoneNamesCoverEveryLayerInTheChain(GameTestHelper helper) {
		Zones.requireNamesFor(LayerChain.count(helper.getLevel().registryAccess()));
		try {
			Zones.requireNamesFor(LayerChain.count(helper.getLevel().registryAccess()) + 1);
		} catch (IllegalStateException expected) {
			helper.succeed();
			return;
		}
		throw failure(helper, "a chain longer than the named layers passed the check");
	}

	@GameTest
	public void eachZoneHasItsOwnBiomeNamedInTheLore(GameTestHelper helper) {
		for (int layer = 1; layer <= 2; layer++) {
			ServerLevel level = layer(helper, layer);
			for (int zone = 0; zone < 3; zone++) {
				Identifier expected = Identifier.fromNamespaceAndPath("deepcharter", ZONES[layer - 1][zone]);
				Zones.Zone found = Zones.of(level, zoneMiddle(level, zone)).orElseThrow();
				if (!found.id().equals(expected) || found.layer() != layer || found.index() != zone) {
					throw failure(helper, "layer_%d zone %d is %s, expected %s", layer, zone, found, expected);
				}
				BlockPos pos = new BlockPos(0, zoneMiddle(level, zone), 0);
				level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL);
				Identifier biome = level.getBiome(pos).unwrapKey().orElseThrow().identifier();
				if (!biome.equals(expected)) {
					throw failure(helper, "layer_%d zone %d has biome %s, expected %s", layer, zone, biome, expected);
				}
			}
		}
		helper.succeed();
	}

	/** Below layer 1 rock is out of reach of hands (SPEC section 9); layer 1 is not. */
	@GameTest
	public void handsCannotBreakDeepRock(GameTestHelper helper) {
		MockPlayer mock = MockPlayers.join(helper, "terrain-hands");
		ServerLevel one = layer(helper, 1);
		ServerLevel two = layer(helper, 2);
		BlockPos pos = new BlockPos(1700, 80, 1700);
		mock.teleportTo(two, Vec3.atBottomCenterOf(pos.above(4)), 0, 0);
		mock.player().setGameMode(GameType.SURVIVAL);
		two.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
		two.setBlock(pos.east(), Blocks.DIRT.defaultBlockState(), 3);
		if (mock.player().gameMode.destroyBlock(pos) || !two.getBlockState(pos).is(Blocks.STONE)) {
			throw failure(helper, "a survival player broke rock by hand in layer_2");
		}
		if (!mock.player().gameMode.destroyBlock(pos.east())) {
			throw failure(helper, "the guard stopped dirt, which is not rock");
		}
		two.setBlock(pos.west(), Blocks.IRON_ORE.defaultBlockState(), 3);
		if (mock.player().gameMode.destroyBlock(pos.west()) || !two.getBlockState(pos.west()).is(Blocks.IRON_ORE)) {
			throw failure(helper, "a survival player broke iron ore by hand in layer_2");
		}
		mock.player().setGameMode(GameType.ADVENTURE);
		if (mock.player().gameMode.destroyBlock(pos) || !two.getBlockState(pos).is(Blocks.STONE)) {
			throw failure(helper, "an adventure player broke rock by hand in layer_2");
		}
		mock.player().setGameMode(GameType.CREATIVE);
		if (!mock.player().gameMode.destroyBlock(pos)) {
			throw failure(helper, "a creative player could not break rock");
		}
		mock.teleportTo(one, Vec3.atBottomCenterOf(pos.above(4)), 0, 0);
		mock.player().setGameMode(GameType.SURVIVAL);
		one.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
		if (!mock.player().gameMode.destroyBlock(pos)) {
			throw failure(helper, "a survival player could not break rock by hand in layer_1");
		}
		helper.succeed();
	}

	/** The attack callback runs on the client too, so a refused swing starts no crack animation. */
	@GameTest
	public void startingToBreakDeepRockByHandIsRefused(GameTestHelper helper) {
		MockPlayer mock = MockPlayers.join(helper, "terrain-attack");
		ServerLevel one = layer(helper, 1);
		ServerLevel two = layer(helper, 2);
		BlockPos pos = new BlockPos(1750, 80, 1750);
		two.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
		one.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
		two.setBlock(pos.east(), Blocks.DIRT.defaultBlockState(), 3);
		mock.teleportTo(two, Vec3.atBottomCenterOf(pos.above(4)), 0, 0);
		mock.player().setGameMode(GameType.SURVIVAL);
		expectAttack(helper, mock, two, pos, InteractionResult.FAIL);
		expectAttack(helper, mock, two, pos.east(), InteractionResult.PASS);
		mock.player().setGameMode(GameType.CREATIVE);
		expectAttack(helper, mock, two, pos, InteractionResult.PASS);
		mock.teleportTo(one, Vec3.atBottomCenterOf(pos.above(4)), 0, 0);
		mock.player().setGameMode(GameType.SURVIVAL);
		expectAttack(helper, mock, one, pos, InteractionResult.PASS);
		helper.succeed();
	}

	private static void expectAttack(GameTestHelper helper, MockPlayer mock, ServerLevel level, BlockPos pos, InteractionResult expected) {
		InteractionResult result = AttackBlockCallback.EVENT.invoker().interact(mock.player(), level, InteractionHand.MAIN_HAND, pos, Direction.UP);
		if (result != expected) {
			throw failure(helper, "attacking %s in %s gave %s, expected %s", pos, level.dimension().identifier(), result, expected);
		}
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void aPodDrillBreaksDeepRock(GameTestHelper helper) {
		ServerLevel two = layer(helper, 2);
		int x = 1800;
		int z = 1800;
		int floor = 80;
		RoomCarver.carve(two, x - 4, x + 3, floor - 6, floor - 1, z - 4, z + 3, Blocks.STONE);
		RoomCarver.carve(two, x - 4, x + 3, floor, floor + 10, z - 4, z + 3, Blocks.AIR);
		MockPlayer pilot = MockPlayers.join(helper, "terrain-drill");
		Vec3 at = new Vec3(x, floor, z);
		pilot.teleportTo(two, at, 0f, 0f);
		PodEntity pod = PodRegistry.POD.create(two, EntitySpawnReason.COMMAND);
		pod.setPos(at);
		two.addFreshEntity(pod);
		if (!pilot.player().startRiding(pod)) {
			throw failure(helper, "the pilot could not mount the pod");
		}
		pilot.setInput(SPRINT);
		helper.onEachTick(() -> {
			if (two.getBlockState(new BlockPos(x - 1, floor - 1, z - 1)).isAir()) {
				pilot.releaseInput();
				pod.discard();
				helper.succeed();
			}
		});
	}

	/** The generator's own copies of the layer's range must equal the dimension type's, or zones and terrain drift apart. */
	@GameTest
	public void generatorMatchesTheDimensionType(GameTestHelper helper) {
		for (int layer = 1; layer <= 2; layer++) {
			ServerLevel level = layer(helper, layer);
			if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
				throw failure(helper, "layer_%d is not generated by noise", layer);
			}
			var noise = generator.generatorSettings().value().noiseSettings();
			if (noise.minY() != level.getMinY() || noise.height() != level.getHeight()) {
				throw failure(helper, "layer_%d noise range %d+%d differs from the dimension's %d+%d",
						layer, noise.minY(), noise.height(), level.getMinY(), level.getHeight());
			}
			if (!(generator.getBiomeSource() instanceof ZoneBiomeSource zones)
					|| zones.minY() != level.getMinY() || zones.height() != level.getHeight()) {
				throw failure(helper, "layer_%d biome source does not use the dimension's range", layer);
			}
		}
		helper.succeed();
	}

	/** Logs how long fresh far chunks take, which is the cost the noise generators add. */
	@GameTest
	public void chunkGenerationTimeIsLogged(GameTestHelper helper) {
		int side = 4;
		for (int layer = 1; layer <= 2; layer++) {
			ServerLevel level = layer(helper, layer);
			int base = 100_000 + layer * 1000;
			long start = System.nanoTime();
			for (int cx = base; cx < base + side; cx++) {
				for (int cz = base; cz < base + side; cz++) {
					level.getChunk(cx, cz, ChunkStatus.FULL);
				}
			}
			double millis = (System.nanoTime() - start) / 1e6;
			double perChunk = millis / (side * side);
			LOGGER.info("Chunk generation time, layer_{}: {} chunks in {} ms ({} ms per chunk)",
					layer, side * side, Math.round(millis), String.format("%.1f", perChunk));
			if (perChunk > 5000) {
				throw failure(helper, "layer_%d takes %.0f ms per chunk to generate", layer, perChunk);
			}
		}
		helper.succeed();
	}

	private static void expectZones(GameTestHelper helper, ServerLevel level, int[] ys, int[] expected) {
		for (int i = 0; i < ys.length; i++) {
			Zones.Zone zone = Zones.of(level, ys[i]).orElseThrow();
			if (zone.index() != expected[i]) {
				throw failure(helper, "%s y=%d is in zone %d, expected %d", level.dimension().identifier(), ys[i], zone.index(), expected[i]);
			}
		}
	}

	/** The middle Y of a zone (0 is the top third), away from the edges, where the 4-block biome cells cannot disagree with it. */
	private static int zoneMiddle(ServerLevel level, int zone) {
		return level.getMinY() + level.getHeight() * (5 - 2 * zone) / 6;
	}

	/** Lays a stone floor and puts a pod on it at {@code podX} (relative), marked so {@link #LAVA_TICKS} counts its ticks. */
	private static PodEntity lavaPod(GameTestHelper helper, double podX) {
		for (int x = 0; x <= 8; x++) {
			for (int z = 0; z <= 6; z++) {
				helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
			}
		}
		PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(podX, 2, 3.5));
		LAVA_TICKS.put(pod.getUUID(), 0);
		return pod;
	}

	/** Runs {@code check} after {@link #LAVA_TEST_TICKS} ticks, then removes the pod and lava; the test passes if {@code check} does not throw. */
	private static void afterLavaTicks(GameTestHelper helper, PodEntity pod, BlockPos lava, Runnable check) {
		helper.runAfterDelay(LAVA_TEST_TICKS, () -> {
			try {
				check.run();
			} finally {
				LAVA_TICKS.remove(pod.getUUID());
				pod.discard();
				// room-carver: removes a block this test placed itself (its lava) in the overworld test structure, not layer rock
				helper.setBlock(lava, Blocks.AIR);
			}
			helper.succeed();
		});
	}

	private static void expectLavaHull(GameTestHelper helper, PodEntity pod, float perTick) {
		int ticks = LAVA_TICKS.get(pod.getUUID());
		float expected = pod.maxHull() - ticks * perTick;
		if (ticks < LAVA_TEST_TICKS || Math.abs(pod.hull() - expected) > EPSILON) {
			throw failure(helper, "after %d ticks the hull should be %s, found %s", ticks, expected, pod.hull());
		}
	}

	@GameTest(maxTicks = LAVA_TEST_TICKS + 20)
	public void lavaThePodIsInLowersTheHullAtTheTunedRate(GameTestHelper helper) {
		PodEntity pod = lavaPod(helper, 3.5);
		BlockPos lava = new BlockPos(3, 2, 3);
		helper.setBlock(lava, Blocks.LAVA);
		afterLavaTicks(helper, pod, lava, () -> expectLavaHull(helper, pod, LayerTuning.DEFAULT.lavaHullPerSecond() / 20f));
	}

	@GameTest(maxTicks = LAVA_TEST_TICKS + 20)
	public void lavaOnlyTouchingThePodLowersTheHullToo(GameTestHelper helper) {
		// The pod is 1.9 wide, so with its centre at 4.05 its east face is at x 5.0, where the lava block starts.
		PodEntity pod = lavaPod(helper, 4.05);
		BlockPos lava = new BlockPos(5, 2, 3);
		helper.setBlock(lava, Blocks.LAVA);
		afterLavaTicks(helper, pod, lava, () -> expectLavaHull(helper, pod, LayerTuning.DEFAULT.lavaHullPerSecond() / 20f));
	}

	@GameTest(maxTicks = LAVA_TEST_TICKS + 20)
	public void aPodClearOfLavaTakesNoDamage(GameTestHelper helper) {
		// The pod's east face is at x 4.45 and the lava starts at x 6.
		PodEntity pod = lavaPod(helper, 3.5);
		BlockPos lava = new BlockPos(6, 2, 3);
		helper.setBlock(lava, Blocks.LAVA);
		afterLavaTicks(helper, pod, lava, () -> expectLavaHull(helper, pod, 0f));
	}

	@GameTest(maxTicks = LAVA_TEST_TICKS + 20)
	public void lavaCanTakeTheLastHullAndWreckThePod(GameTestHelper helper) {
		PodEntity pod = lavaPod(helper, 3.5);
		pod.setHull(1f);
		BlockPos lava = new BlockPos(3, 2, 3);
		helper.setBlock(lava, Blocks.LAVA);
		afterLavaTicks(helper, pod, lava, () -> {
			if (!Wrecks.isWreck(pod) || pod.hull() != 0f) {
				throw failure(helper, "the lava should have taken the last hull and wrecked the pod, hull is %s", pod.hull());
			}
		});
	}

	@GameTest(maxTicks = LAVA_TEST_TICKS + 20)
	public void aRadiatorCutsTheLavaDamage(GameTestHelper helper) {
		PodEntity pod = lavaPod(helper, 3.5);
		CharterId owner = CharterId.random();
		PodComponents.register(pod, owner);
		PodComponents.install(pod, ComponentItems.mint(helper.getLevel().getServer(), ComponentTrack.RADIATOR, 2, owner));
		BlockPos lava = new BlockPos(3, 2, 3);
		helper.setBlock(lava, Blocks.LAVA);
		// A tier 2 radiator takes 0.75 of the stock damage (UpgradeTuning).
		afterLavaTicks(helper, pod, lava, () -> expectLavaHull(helper, pod, LayerTuning.DEFAULT.lavaHullPerSecond() / 20f * 0.75f));
	}

	@GameTest(maxTicks = LAVA_TEST_TICKS + 20)
	public void aRadiatorAboveTheChassisTierCapWorksAtTheCap(GameTestHelper helper) {
		PodEntity pod = lavaPod(helper, 3.5);
		CharterId owner = CharterId.random();
		PodComponents.register(pod, owner);
		PodComponents.install(pod, ComponentItems.mint(helper.getLevel().getServer(), ComponentTrack.RADIATOR, 3, owner));
		BlockPos lava = new BlockPos(3, 2, 3);
		helper.setBlock(lava, Blocks.LAVA);
		// The Mole caps components at tier 2, so a tier 3 radiator still takes 0.75.
		afterLavaTicks(helper, pod, lava, () -> expectLavaHull(helper, pod, LayerTuning.DEFAULT.lavaHullPerSecond() / 20f * 0.75f));
	}

	private static void expectBlock(GameTestHelper helper, ServerLevel level, BlockPos pos, Block block) {
		if (!level.getBlockState(pos).is(block)) {
			throw failure(helper, "%s %s should be %s, found %s", level.dimension().identifier(), pos, block, level.getBlockState(pos));
		}
	}

	private static void box(ServerLevel level, int x1, int x2, int y1, int y2, int z1, int z2, Block block) {
		for (int x = x1; x <= x2; x++) {
			for (int y = y1; y <= y2; y++) {
				for (int z = z1; z <= z2; z++) {
					level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 2);
				}
			}
		}
	}

	// assertionException(String, Object...) leaves the placeholders unfilled in the report.
	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	private static ServerLevel layer(GameTestHelper helper, int layer) {
		ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(layer));
		if (level == null) {
			throw failure(helper, "dimension %s did not load", LayerChain.dimension(layer));
		}
		return level;
	}
}
