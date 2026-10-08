package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;

import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;

/** Throwaway server measurements for issue 185: a 21x21 square of new columns in a test dimension of height 256, 2048 or 4064. */
public class TallSpikeServerTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("TallSpike");
	private static final int RADIUS = 10;
	private static final int MAX_TICKS = 40000;

	@GameTest(maxTicks = MAX_TICKS)
	public void tall256(GameTestHelper helper) {
		measure(helper, 256);
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void tall2048(GameTestHelper helper) {
		measure(helper, 2048);
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void tall4064(GameTestHelper helper) {
		measure(helper, 4064);
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void tallskyon(GameTestHelper helper) {
		measure(helper, 2352, "tall_spike_surface");
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void tallskyoff(GameTestHelper helper) {
		measure(helper, 2352, "tall_spike_surface_nosky");
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void tallvanilla(GameTestHelper helper) {
		measure(helper, 384, "tall_spike_vanilla");
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void tallsea(GameTestHelper helper) {
		measure(helper, 2352, "tall_spike_surface_sea");
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void tall3v1a(GameTestHelper helper) {
		measure(helper, 2352, "tall_spike3_v1a");
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void tall3v1b(GameTestHelper helper) {
		measure(helper, 2352, "tall_spike3_v1b");
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void tall3v1c(GameTestHelper helper) {
		measure(helper, 2352, "tall_spike3_v1c");
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void tall3v1d(GameTestHelper helper) {
		measure(helper, 2352, "tall_spike3_v1d");
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void tall3v1e(GameTestHelper helper) {
		measure(helper, 2352, "tall_spike3_v1e");
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void tall3own(GameTestHelper helper) {
		measure(helper, 2352, "tall_spike3_own");
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void tall3ownb(GameTestHelper helper) {
		measure(helper, 2352, "tall_spike3_own2");
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void tall3band(GameTestHelper helper) {
		measure(helper, 2352, "tall_spike3_band");
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void tall3lazy(GameTestHelper helper) {
		measure(helper, 2352, "tall_spike3_lazy");
	}

	/** Phase 3: fill 256-block layer bands later, in columns that were generated with a stone placeholder below the surface band. */
	@GameTest(maxTicks = MAX_TICKS)
	public void tall3latefill(GameTestHelper helper) {
		ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", "tall_spike3_lazy"));
		ServerLevel level = helper.getLevel().getServer().getLevel(key);
		if (level == null) {
			throw helper.assertionException("no dimension " + key);
		}
		for (int x = -RADIUS; x <= RADIUS; x++) {
			for (int z = -RADIUS; z <= RADIUS; z++) {
				level.setChunkForced(x, z, true);
			}
		}
		LateFill fill = new LateFill(level);
		helper.onEachTick(() -> {
			if (fill.step()) {
				helper.succeed();
			}
		});
	}

	/** The late fill as a state machine over ticks, because the light engine needs the server thread to finish its tasks. */
	private static final class LateFill {
		private static final String[] BANDS = {"a", "b", "c"};
		private final ServerLevel level;
		private final java.util.List<LevelChunk> chunks = new java.util.ArrayList<>();
		private java.util.List<java.util.concurrent.CompletableFuture<net.minecraft.world.level.chunk.ChunkAccess>> pending = new java.util.ArrayList<>();
		private int band;
		private int stage = -1;
		private long heapBefore;
		private long cpu0;
		private long t0;
		private long cpu1;
		private long t1;
		private long cpu2;
		private long t2;

		LateFill(ServerLevel level) {
			this.level = level;
		}

		/** Returns true when all bands are done. */
		boolean step() {
			if (stage == -1) {
				for (int x = -RADIUS; x <= RADIUS; x++) {
					for (int z = -RADIUS; z <= RADIUS; z++) {
						LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
						if (chunk == null) {
							return false;
						}
						chunks.add(chunk);
					}
				}
				heapBefore = usedHeap();
				stage = 0;
			}
			if (stage == 0) {
				var settings = level.registryAccess().lookupOrThrow(Registries.NOISE_SETTINGS)
						.getOrThrow(ResourceKey.create(Registries.NOISE_SETTINGS, Identifier.fromNamespaceAndPath("deepcharter", "tall_spike3_band256_" + BANDS[band])));
				var biomes = level.getChunkSource().getGenerator().getBiomeSource();
				var generator = new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(biomes, settings);
				cpu0 = processCpu();
				t0 = System.nanoTime();
				pending.clear();
				for (LevelChunk chunk : chunks) {
					pending.add(generator.buildTerrain(chunk, net.minecraft.world.level.levelgen.blending.Blender.empty(), level.getChunkSource().randomState(),
							level.structureManager(), level.getBiomeManager(), null, biomes.possibleBiomes()));
				}
				stage = 1;
				return false;
			}
			if (stage == 1) {
				if (!pending.stream().allMatch(java.util.concurrent.CompletableFuture::isDone)) {
					return false;
				}
				pending.forEach(java.util.concurrent.CompletableFuture::join);
				cpu1 = processCpu();
				t1 = System.nanoTime();
				pending.clear();
				var light = level.getChunkSource().getLightEngine();
				for (LevelChunk chunk : chunks) {
					chunk.initializeLightSources();
					pending.add(light.lightChunk(chunk, false));
				}
				stage = 2;
				return false;
			}
			if (stage == 2) {
				if (!pending.stream().allMatch(java.util.concurrent.CompletableFuture::isDone)) {
					return false;
				}
				cpu2 = processCpu();
				t2 = System.nanoTime();
				int columns = chunks.size();
				long raw = 0;
				long deflated = 0;
				for (LevelChunk chunk : chunks) {
					RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
					ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buf, new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
					byte[] bytes = new byte[buf.readableBytes()];
					buf.readBytes(bytes);
					raw += bytes.length;
					deflated += deflate(bytes);
				}
				long cpu3 = processCpu();
				long t3 = System.nanoTime();
				LOGGER.info("TALLSPIKE-LATEFILL band={} fillCpuMsPerColumn={} fillWallMsPerColumn={} lightCpuMsPerColumn={} lightWallMsPerColumn={} encodeCpuMsPerColumn={} encodeWallMsPerColumn={} packetRawPerColumn={} packetDeflatedPerColumn={}",
						BANDS[band], String.format("%.1f", (cpu1 - cpu0) / 1e6 / columns), String.format("%.2f", (t1 - t0) / 1e6 / columns),
						String.format("%.2f", (cpu2 - cpu1) / 1e6 / columns), String.format("%.2f", (t2 - t1) / 1e6 / columns),
						String.format("%.2f", (cpu3 - cpu2) / 1e6 / columns), String.format("%.2f", (t3 - t2) / 1e6 / columns), raw / columns, deflated / columns);
				band++;
				if (band == BANDS.length) {
					long heapAfter = usedHeap();
					LOGGER.info("TALLSPIKE-LATEFILL heapBeforeMB={} heapAfterMB={} (three bands filled)", heapBefore >> 20, heapAfter >> 20);
					return true;
				}
				stage = 0;
			}
			return false;
		}
	}

	private static void measure(GameTestHelper helper, int height) {
		measure(helper, height, "tall_spike_" + height);
	}

	private static java.util.Map<String, Long> threadCpuByGroup() {
		java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
		java.util.Map<String, Long> groups = new java.util.TreeMap<>();
		for (java.lang.management.ThreadInfo info : bean.getThreadInfo(bean.getAllThreadIds())) {
			if (info != null) {
				long cpu = bean.getThreadCpuTime(info.getThreadId());
				if (cpu > 0) {
					groups.merge(info.getThreadName().replaceAll("[0-9]+", "#"), cpu, Long::sum);
				}
			}
		}
		return groups;
	}

	private static void measure(GameTestHelper helper, int height, String name) {
		ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", name));
		ServerLevel level = helper.getLevel().getServer().getLevel(key);
		if (level == null) {
			throw helper.assertionException("no dimension " + key);
		}
		long before = usedHeap();
		java.util.Map<String, Long> threadsStart = threadCpuByGroup();
		long cpuStart = processCpu();
		long start = System.nanoTime();
		for (int x = -RADIUS; x <= RADIUS; x++) {
			for (int z = -RADIUS; z <= RADIUS; z++) {
				level.setChunkForced(x, z, true);
			}
		}
		int columns = (2 * RADIUS + 1) * (2 * RADIUS + 1);
		boolean[] done = {false};
		helper.onEachTick(() -> {
			if (done[0]) {
				return;
			}
			for (int x = -RADIUS; x <= RADIUS; x++) {
				for (int z = -RADIUS; z <= RADIUS; z++) {
					if (level.getChunkSource().getChunkNow(x, z) == null) {
						return;
					}
				}
			}
			done[0] = true;
			double genMs = (System.nanoTime() - start) / 1e6;
			double cpuMs = (processCpu() - cpuStart) / 1e6;
			StringBuilder threads = new StringBuilder();
			threadCpuByGroup().forEach((group, cpu) -> {
				long ms = (cpu - threadsStart.getOrDefault(group, 0L)) / 1_000_000;
				if (ms >= 50) {
					threads.append(String.format("[%s=%.1f] ", group, (double) ms / columns));
				}
			});
			LOGGER.info("TALLSPIKE-THREADS name={} cpuMsPerColumn by thread group: {}", name, threads);
			double load = java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
			long after = usedHeap();
			long[] bandAir = new long[32];
			long[] bandAll = new long[32];
			long airBlocks = 0;
			long sampledBlocks = 0;
			long raw = 0;
			long deflated = 0;
			for (int x = -RADIUS; x <= RADIUS; x++) {
				for (int z = -RADIUS; z <= RADIUS; z++) {
					LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
					for (int index = 0; index < chunk.getSectionsCount(); index += 4) {
						var section = chunk.getSection(index);
						for (int bx = 0; bx < 16; bx++) {
							for (int by = 0; by < 16; by++) {
								for (int bz = 0; bz < 16; bz++) {
									sampledBlocks++;
									int band = (index * 16 + by) / 256;
									bandAll[band]++;
									if (section.getBlockState(bx, by, bz).isAir()) {
										airBlocks++;
										bandAir[band]++;
									}
								}
							}
						}
					}
					RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
					ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buf, new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
					byte[] bytes = new byte[buf.readableBytes()];
					buf.readBytes(bytes);
					raw += bytes.length;
					deflated += deflate(bytes);
				}
			}
			StringBuilder bands = new StringBuilder();
			for (int b = 0; b < 32; b++) {
				if (bandAll[b] > 0) {
					bands.append(String.format("[%d..%d)=%.3f ", level.getMinY() + b * 256, level.getMinY() + b * 256 + 256, (double) bandAir[b] / bandAll[b]));
				}
			}
			LOGGER.info("TALLSPIKE-BANDS height={} air by 256-block band from the bottom: {}", height, bands);
			level.getServer().saveAllChunks(false, true, false);
			long region = regionBytes(level, name);
			LOGGER.info("TALLSPIKE-SERVER name={} seed={} maxHeapMB={} height={} columns={} genMs={} msPerColumn={} cpuMsPerColumn={} loadAvg={} heapBeforeMB={} heapAfterMB={} heapDeltaMB={} regionBytes={} packetRawPerColumn={} packetDeflatedPerColumn={} airFraction={}",
					name, level.getSeed(), Runtime.getRuntime().maxMemory() >> 20, height, columns, Math.round(genMs), String.format("%.2f", genMs / columns), String.format("%.1f", cpuMs / columns), String.format("%.1f", load), before >> 20, after >> 20, (after - before) >> 20,
					region, raw / columns, deflated / columns, String.format("%.3f", (double) airBlocks / sampledBlocks));
			helper.succeed();
		});
	}

	private static long processCpu() {
		return ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
	}

	private static long usedHeap() {
		for (int i = 0; i < 3; i++) {
			System.gc();
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		Runtime runtime = Runtime.getRuntime();
		return runtime.totalMemory() - runtime.freeMemory();
	}

	private static int deflate(byte[] bytes) {
		Deflater deflater = new Deflater();
		deflater.setInput(bytes);
		deflater.finish();
		byte[] out = new byte[8192];
		int total = 0;
		while (!deflater.finished()) {
			total += deflater.deflate(out);
		}
		deflater.end();
		return total;
	}

	private static long regionBytes(ServerLevel level, String name) {
		Path root = level.getServer().getWorldPath(LevelResource.ROOT);
		try (var files = Files.walk(root)) {
			return files.filter(p -> p.toString().endsWith(".mca") && p.toString().contains(name)).mapToLong(p -> {
				try {
					return Files.size(p);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}).sum();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
