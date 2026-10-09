package io.github.pkeppeler.deepcharter.test;

import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Measures what generating the surface costs a new column of the tall campaign world (ADR 0029): CPU and heap per column of a
 * 2352 block world (the surface above the layers) against the layers alone at 2048, whose ratio the ADR bars at 1.15. By default it
 * generates a few columns of each world and checks they exist. With the number of rounds in the environment, for example
 * {@code DEEPCHARTER_SURFACE_COST=3 tools/gametest.sh 'surface_cost_test*'}, it generates {@value #MEASURED_RADIUS} chunks to each side of
 * a fresh centre per world and round (after a warm-up), and prints the lines that start with {@code [surface-cost]}: per world the
 * median over the rounds, and the ratio of each surface world to the layers-only world with the same biome source.
 *
 * <p>The worlds are test dimensions in the test mod's preset. With the layers' zone biomes: {@code tall_baseline} (layers only) and
 * {@code tall_surface} (our surface over the layers). With the surface's own biome source over the whole height, which costs a noise
 * lookup per quart cell and has no zone features: {@code tall_baseline_biomes} and {@code tall_surface_biomes}.
 */
public class SurfaceCostTest {
	private static final Logger LOGGER = LoggerFactory.getLogger(SurfaceCostTest.class);
	static final String ROUNDS_ENV = "DEEPCHARTER_SURFACE_COST";
	private static final String REQUESTED_ROUNDS = System.getenv(ROUNDS_ENV);
	private static final int SMOKE_RADIUS = 1;
	private static final int MEASURED_RADIUS = 8;
	private static final int WARM_UP_RADIUS = 4;
	/** Chunk rows between two regions, so that no two measurements share a column. */
	private static final int REGION_SPACING_CHUNKS = 64;
	private static final int COOL_DOWN_TICKS = 100;
	private static final List<String> WORLDS = List.of("tall_baseline", "tall_surface", "tall_baseline_biomes", "tall_surface_biomes");
	/** Each surface world, and the layers-only world it is compared with. */
	private static final Map<String, String> BASELINES = Map.of("tall_surface", "tall_baseline", "tall_surface_biomes", "tall_baseline_biomes");
	/** A region counts as unloaded when this few chunks of a world are left, such as those around a spawn. */
	private static final int UNLOADED_CHUNKS = 30;
	private static final int MAX_TICKS = 200_000;

	@GameTest(maxTicks = MAX_TICKS)
	public void everyTallWorldGeneratesColumns(GameTestHelper helper) {
		int rounds = REQUESTED_ROUNDS == null ? 0 : Integer.parseInt(REQUESTED_ROUNDS);
		ArrayDeque<Run> queue = new ArrayDeque<>();
		if (rounds == 0) {
			WORLDS.forEach(world -> queue.add(new Run(world, SMOKE_RADIUS, 0, false)));
		} else {
			WORLDS.forEach(world -> queue.add(new Run(world, WARM_UP_RADIUS, 0, false)));
			for (int round = 1; round <= rounds; round++) {
				for (String world : round % 2 == 1 ? WORLDS : WORLDS.reversed()) {
					queue.add(new Run(world, MEASURED_RADIUS, round, true));
				}
			}
		}
		Map<String, List<Measurement>> results = new LinkedHashMap<>();
		Run[] current = {null};
		int[] cooldown = {0};
		helper.onEachTick(() -> {
			if (cooldown[0] > 0) {
				cooldown[0]--;
				return;
			}
			if (current[0] == null && !everyWorldIsUnloaded(helper)) {
				return;
			}
			if (current[0] == null) {
				if (queue.isEmpty()) {
					return;
				}
				current[0] = queue.poll();
				current[0].begin(helper);
			}
			if (!current[0].isGenerated()) {
				return;
			}
			Measurement measurement = current[0].finish();
			if (current[0].measured) {
				results.computeIfAbsent(current[0].world, world -> new ArrayList<>()).add(measurement);
			}
			current[0] = null;
			cooldown[0] = COOL_DOWN_TICKS;
			if (queue.isEmpty()) {
				if (rounds > 0) {
					report(results);
				}
				helper.succeed();
			}
		});
	}

	private static void report(Map<String, List<Measurement>> results) {
		LOGGER.info("[surface-cost] {} columns per world and round, the median of {} rounds; ADR 0029 bar: 1.15x the layers-only world",
				columnsOf(MEASURED_RADIUS), results.get(WORLDS.getFirst()).size());
		results.forEach((world, all) -> {
			Measurement m = median(all);
			String against = BASELINES.containsKey(world) ? "; vs " + BASELINES.get(world) + ": CPU "
					+ format(m.cpuMsPerColumn / median(results.get(BASELINES.get(world))).cpuMsPerColumn) + "x, heap "
					+ format(m.heapMb / median(results.get(BASELINES.get(world))).heapMb) + "x" : "";
			LOGGER.info("[surface-cost] {}: {} CPU ms per column (rounds {}), {} MB heap (rounds {}), {} wall ms per column{}",
					world, format(m.cpuMsPerColumn), all.stream().map(a -> format(a.cpuMsPerColumn)).toList(), Math.round(m.heapMb),
					all.stream().map(a -> Math.round(a.heapMb)).toList(), format(m.wallMsPerColumn), against);
		});
	}

	private static Measurement median(List<Measurement> all) {
		return new Measurement(medianOf(all.stream().mapToDouble(m -> m.cpuMsPerColumn).toArray()),
				medianOf(all.stream().mapToDouble(m -> m.heapMb).toArray()),
				medianOf(all.stream().mapToDouble(m -> m.wallMsPerColumn).toArray()));
	}

	/** The heap of a region is measured with the one before it gone, so a run waits until the worlds hold nothing of it. */
	private static boolean everyWorldIsUnloaded(GameTestHelper helper) {
		for (String world : WORLDS) {
			ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", world)));
			if (level != null && level.getChunkSource().getLoadedChunksCount() > UNLOADED_CHUNKS) {
				return false;
			}
		}
		return true;
	}

	private static double medianOf(double[] values) {
		double[] sorted = values.clone();
		Arrays.sort(sorted);
		return sorted[sorted.length / 2];
	}

	private static String format(double value) {
		return String.format("%.2f", value);
	}

	private static int columnsOf(int radius) {
		return (2 * radius + 1) * (2 * radius + 1);
	}

	private static long processCpuNanos() {
		return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
	}

	/** Heap in use after a collection, so that garbage of earlier work is not counted. */
	private static long usedHeap() {
		for (int i = 0; i < 3; i++) {
			System.gc();
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted while measuring heap", e);
			}
		}
		Runtime runtime = Runtime.getRuntime();
		return runtime.totalMemory() - runtime.freeMemory();
	}

	private record Measurement(double cpuMsPerColumn, double heapMb, double wallMsPerColumn) {
	}

	/** The columns of one world around a centre of its own: forced, then waited for tick by tick. */
	private static final class Run {
		private final String world;
		private final int radius;
		private final int round;
		private final boolean measured;
		private ServerLevel level;
		private int centreChunk;
		private long heapBefore;
		private long cpuBefore;
		private long wallBefore;

		Run(String world, int radius, int round, boolean measured) {
			this.world = world;
			this.radius = radius;
			this.round = round;
			this.measured = measured;
		}

		void begin(GameTestHelper helper) {
			ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", world));
			level = helper.getLevel().getServer().getLevel(key);
			if (level == null) {
				throw helper.assertionException(Component.literal("no dimension " + key.identifier()));
			}
			centreChunk = (round + 1) * REGION_SPACING_CHUNKS;
			heapBefore = usedHeap();
			cpuBefore = processCpuNanos();
			wallBefore = System.nanoTime();
			forEachChunk(chunk -> level.setChunkForced(chunk[0], chunk[1], true));
		}

		boolean isGenerated() {
			boolean[] all = {true};
			forEachChunk(chunk -> all[0] &= level.getChunkSource().getChunkNow(chunk[0], chunk[1]) != null);
			return all[0];
		}

		Measurement finish() {
			double columns = columnsOf(radius);
			double cpuMs = (processCpuNanos() - cpuBefore) / 1e6;
			double wallMs = (System.nanoTime() - wallBefore) / 1e6;
			double heapMb = (usedHeap() - heapBefore) / (1024.0 * 1024.0);
			forEachChunk(chunk -> level.setChunkForced(chunk[0], chunk[1], false));
			return new Measurement(cpuMs / columns, heapMb, wallMs / columns);
		}

		private void forEachChunk(java.util.function.Consumer<int[]> action) {
			for (int x = -radius; x <= radius; x++) {
				for (int z = -radius; z <= radius; z++) {
					action.accept(new int[] {centreChunk + x, centreChunk + z});
				}
			}
		}
	}
}
