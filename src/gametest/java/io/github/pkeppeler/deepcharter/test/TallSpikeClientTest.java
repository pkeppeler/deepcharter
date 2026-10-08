package io.github.pkeppeler.deepcharter.test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import io.github.pkeppeler.deepcharter.layer.RoomSeal;

/**
 * Throwaway client measurements for issue 185, run when TALLSPIKE_HEIGHT is 256, 2048 or 4064: arrive mid-depth in the test
 * dimension at render distance 10, time the view building, then average FPS over 30 s with the camera turning once.
 */
public class TallSpikeClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("TallSpike");
	private static final int RADIUS = 10;
	private static final int STABLE_TICKS = 5;
	private static final int FPS_SECONDS = 30;

	@Override
	public void runTest(ClientGameTestContext context) {
		String env = System.getenv("TALLSPIKE_HEIGHT");
		if (env == null) {
			return;
		}
		int height = Integer.parseInt(env);
		int minY = height == 256 ? 0 : -2032;
		String name = System.getenv("TALLSPIKE_DIM") != null && !System.getenv("TALLSPIKE_DIM").isEmpty() ?System.getenv("TALLSPIKE_DIM") : "tall_spike_" + height;
		boolean onSurface = "surface".equals(System.getenv("TALLSPIKE_SPOT"));
		ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", name));
		context.runOnClient(client -> {
			client.options.renderDistance().set(RADIUS);
			client.options.simulationDistance().set(RADIUS);
			client.options.framerateLimit().set(260);
			client.options.enableVsync().set(false);
			// Minecraft caps an idle window at 30 FPS after 60 s without input, and a long server load alone triggers it.
			client.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
		});
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitFor(client -> client.player != null && client.level != null);
			context.waitTicks(40);
			// 1. The server loads the 21x21 square first, so the arrival below times the client's work and not worldgen.
			double[] spot = new double[3];
			long baseline = usedHeap();
			long genStart = System.nanoTime();
			singleplayer.getServer().runOnServer(server -> {
				ServerLevel level = server.getLevel(key);
				for (int x = -RADIUS; x <= RADIUS; x++) {
					for (int z = -RADIUS; z <= RADIUS; z++) {
						level.setChunkForced(x, z, true);
					}
				}
			});
			ServerLevel[] holder = new ServerLevel[1];
			singleplayer.getServer().runOnServer(server -> holder[0] = server.getLevel(key));
			ServerLevel level = holder[0];
			int safety = 0;
			while (!singleplayer.getServer().computeOnServer(server -> loaded(level)) && safety++ < 20000) {
				context.waitTick();
			}
			double genMs = (System.nanoTime() - genStart) / 1e6;
			long serverOnly = usedHeap();
			LOGGER.info("TALLSPIKE-CLIENT height={} serverSquareLoadedMs={} heapBaselineMB={} heapServerLoadedMB={}", height, Math.round(genMs), baseline >> 20, serverOnly >> 20);

			// 2. A cave spot near mid-depth: air at the feet and four above, rock under.
			int midY = minY + height / 2;
			singleplayer.getServer().runOnServer(server -> {
				BlockPos found = null;
				if (onSurface) {
					// Standing on the vanilla surface at the origin column.
					found = new BlockPos(0, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0), 0);
				}
				if (System.getenv("TALLSPIKE_HALL") != null) {
					// The same 33x16x33 hall at mid-depth in every dimension, so the visible geometry is equal across heights.
					BlockPos low = new BlockPos(-16, midY, -16);
					BlockPos high = new BlockPos(16, midY + 15, 16);
					RoomSeal.seal(level, low, high);
					for (BlockPos pos : BlockPos.betweenClosed(low, high)) {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
					}
					found = new BlockPos(0, midY, 0);
				}
				search:
				for (int r = 0; r < 150 && found == null && !onSurface; r += 2) {
					for (int dx = -r; dx <= r; dx += 2) {
						for (int dz = -r; dz <= r; dz += 2) {
							if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
								continue;
							}
							for (int dy = -96; dy <= 96; dy++) {
								BlockPos pos = new BlockPos(dx, midY + dy, dz);
								if (open(level, pos)) {
									found = pos;
									break search;
								}
							}
						}
					}
				}
				if (found == null) {
					found = new BlockPos(0, midY, 0);
					RoomSeal.seal(level, found.offset(-2, 0, -2), found.offset(2, 3, 2));
					for (BlockPos pos : BlockPos.betweenClosed(found.offset(-2, 0, -2), found.offset(2, 3, 2))) {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
					}
					LOGGER.info("TALLSPIKE-CLIENT height={} no cave within range, carved a 5x4x5 room", height);
				}
				spot[0] = found.getX() + 0.5;
				spot[1] = found.getY();
				spot[2] = found.getZ() + 0.5;
			});
			LOGGER.info("TALLSPIKE-CLIENT name={} height={} onSurface={} spot={},{},{} midY={}", name, height, onSurface, spot[0], spot[1], spot[2], midY);

			// 3. Arrive and time the build.
			long cpu0 = processCpu();
			long t0 = System.nanoTime();
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.setPermanentlyInvulnerable(true);
				player.teleportTo(level, spot[0], spot[1], spot[2], Set.of(), 0, 0, true);
			});
			long dimensionAt = -1;
			long screenGoneAt = -1;
			long builtAt = -1;
			int stable = 0;
			long buildCpuMs = -1;
			List<String> trace = new ArrayList<>();
			for (int tick = 0; tick < 6000 && builtAt < 0; tick++) {
				context.waitTick();
				long now = System.nanoTime();
				int[] state = context.computeOnClient(client -> {
					boolean there = client.level != null && client.level.dimension().equals(key);
					boolean noScreen = client.gui.screen() == null;
					var renderer = client.levelRenderer;
					boolean all = renderer.hasRenderedAllSections();
					var dispatcher = renderer.sectionRenderDispatcher();
					int queue = dispatcher == null ? -1 : dispatcher.getCompileQueueSize();
					return new int[] {there ? 1 : 0, noScreen ? 1 : 0, all ? 1 : 0, queue, renderer.visibleSections().size()};
				});
				if (state[0] == 1 && dimensionAt < 0) {
					dimensionAt = now;
				}
				if (state[0] == 1 && state[1] == 1 && screenGoneAt < 0) {
					screenGoneAt = now;
				}
				if (tick % 10 == 0) {
					trace.add(String.format("t+%dms there=%d noScreen=%d allRendered=%d queue=%d visible=%d", (now - t0) / 1_000_000, state[0], state[1], state[2], state[3], state[4]));
				}
				boolean ready = state[0] == 1 && state[1] == 1 && state[2] == 1 && state[3] == 0;
				stable = ready ? stable + 1 : 0;
				if (stable == STABLE_TICKS) {
					buildCpuMs = (processCpu() - cpu0) / 1_000_000;
					builtAt = now - (long) (STABLE_TICKS - 1) * 50_000_000L;
				}
			}
			trace.forEach(line -> LOGGER.info("TALLSPIKE-TRACE height={} {}", height, line));
			if (builtAt < 0) {
				throw new AssertionError("view never built");
			}
			LOGGER.info("TALLSPIKE-CLIENT height={} dimensionSwitchedMs={} loadingScreenGoneMs={} viewBuiltMs={} buildProcessCpuMs={}", height,
					(dimensionAt - t0) / 1_000_000, (screenGoneAt - t0) / 1_000_000, (builtAt - t0) / 1_000_000, buildCpuMs);
			context.waitTicks(40);
			long total = usedHeap();
			int visible = context.computeOnClient(client -> client.levelRenderer.visibleSections().size());
			LOGGER.info("TALLSPIKE-CLIENT height={} heapViewBuiltMB={} clientEstimateMB={} visibleSections={}", height, total >> 20, (total - serverOnly) >> 20, visible);

			if (System.getenv("TALLSPIKE_NOFPS") != null) {
				return;
			}
			// 4. FPS over 30 s, camera turning once.
			List<Integer> samples = new ArrayList<>();
			List<Double> mspt = new ArrayList<>();
			long renderCpu0 = threadCpu("Render thread");
			long serverCpu0 = threadCpu("Server thread");
			long procCpu0 = processCpu();
			long win0 = System.nanoTime();
			double loadBefore = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
			int ticks = FPS_SECONDS * 20;
			for (int tick = 0; tick < ticks; tick++) {
				float yaw = 360f * tick / ticks;
				context.runOnClient(client -> client.player.setYRot(yaw));
				context.waitTick();
				if (tick % 10 == 9) {
					samples.add(context.computeOnClient(Minecraft::getFps));
					mspt.add(singleplayer.getServer().computeOnServer(server -> server.getAverageTickTimeNanos() / 1e6));
				}
			}
			double winS = (System.nanoTime() - win0) / 1e9;
			long renderCpu = threadCpu("Render thread") - renderCpu0;
			long serverCpu = threadCpu("Server thread") - serverCpu0;
			long procCpu = processCpu() - procCpu0;
			double average = samples.stream().mapToInt(Integer::intValue).average().orElse(0);
			int min = samples.stream().mapToInt(Integer::intValue).min().orElse(0);
			LOGGER.info("TALLSPIKE-CLIENT height={} fpsAvg={} fpsMin={} samples={} msptAvg={} loadAvgBefore={} loadAvgAfter={} cpus={} windowS={} renderCpuMsPerFrame={} serverCpuMsPerTick={} processCpuCores={} heapAfterFpsMB={}", height,
					String.format("%.1f", average), min, samples.size(), String.format("%.1f", mspt.stream().mapToDouble(Double::doubleValue).average().orElse(0)),
					String.format("%.1f", loadBefore), String.format("%.1f", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage()),
					Runtime.getRuntime().availableProcessors(), String.format("%.1f", winS),
					String.format("%.2f", renderCpu / 1e6 / Math.max(1, average * winS)), String.format("%.2f", serverCpu / 1e6 / (winS * 20)),
					String.format("%.2f", procCpu / 1e9 / winS), usedHeap() >> 20);
		}
	}

	private static long processCpu() {
		return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
	}

	private static long threadCpu(String name) {
		java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
		for (java.lang.management.ThreadInfo info : bean.getThreadInfo(bean.getAllThreadIds())) {
			if (info != null && info.getThreadName().equals(name)) {
				return bean.getThreadCpuTime(info.getThreadId());
			}
		}
		return 0;
	}

	private static boolean loaded(ServerLevel level) {
		for (int x = -RADIUS; x <= RADIUS; x++) {
			for (int z = -RADIUS; z <= RADIUS; z++) {
				if (level.getChunkSource().getChunkNow(x, z) == null) {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean open(ServerLevel level, BlockPos pos) {
		if (level.getBlockState(pos.below()).isAir() || !level.getBlockState(pos.below()).getFluidState().isEmpty()) {
			return false;
		}
		for (int dy = 0; dy < 4; dy++) {
			BlockState state = level.getBlockState(pos.above(dy));
			if (!state.isAir()) {
				return false;
			}
		}
		return true;
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
}
