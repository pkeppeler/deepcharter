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

	private static void measure(GameTestHelper helper, int height) {
		String name = "tall_spike_" + height;
		ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", name));
		ServerLevel level = helper.getLevel().getServer().getLevel(key);
		if (level == null) {
			throw helper.assertionException("no dimension " + key);
		}
		long before = usedHeap();
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
			LOGGER.info("TALLSPIKE-SERVER maxHeapMB={} height={} columns={} genMs={} msPerColumn={} cpuMsPerColumn={} loadAvg={} heapBeforeMB={} heapAfterMB={} heapDeltaMB={} regionBytes={} packetRawPerColumn={} packetDeflatedPerColumn={} airFraction={}",
					Runtime.getRuntime().maxMemory() >> 20, height, columns, Math.round(genMs), String.format("%.2f", genMs / columns), String.format("%.1f", cpuMs / columns), String.format("%.1f", load), before >> 20, after >> 20, (after - before) >> 20,
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
