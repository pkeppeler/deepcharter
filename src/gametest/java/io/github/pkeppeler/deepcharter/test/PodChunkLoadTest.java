package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * A pod's tick path reads loaded chunks only (issue 194). A pod stands at the east edge of a chunk that is loaded alone, with a
 * lights part, so the lights listener, the lava probe, movement, towing and the drill all have a reason to look into the unloaded
 * chunk next door; the tests assert that chunk, and every other one around, is still unloaded after the pod has ticked. Each test has
 * its own site, far from every other test's blocks, in the overworld.
 */
public class PodChunkLoadTest {
	private static final int FLOOR_Y = 200;
	/** No neighbour updates: a block set at a chunk edge with them loads the chunk next door, which would load it before the test starts. */
	private static final int BUILD_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
	private static final int FIRST_CHUNK_X = 462;
	private static final int FIRST_CHUNK_Z = 475;
	/** Chunks between two tests' sites, so one test's loaded chunk is never another's neighbour. */
	private static final int SITE_SPACING = 4;
	/**
	 * Wall-clock time to wait for the lone chunk to generate; a test that passes ends at once. Not a tick count: the test server
	 * ticks as fast as it can, so a tick budget is only a few seconds, which a busy CI runner can spend before one far chunk is made.
	 */
	private static final int LOAD_BUDGET_SECONDS = 120;
	/** Pause per server tick while the chunk generates, so the generation threads get the time and a tick count bounds the wait too. */
	private static final int LOAD_POLL_MILLIS = 5;
	/** Ticks that fit in {@link #LOAD_BUDGET_SECONDS} at one tick per {@link #LOAD_POLL_MILLIS}, with room to spare, so the budget fails a test first. */
	private static final int LOAD_BUDGET_TICKS = LOAD_BUDGET_SECONDS * 1000 / LOAD_POLL_MILLIS * 2;
	/** Pod ticks: more than the lights listener, the lava probe and the cable need to run twice over. */
	private static final int POD_TICKS = 60;
	/** Pod ticks for the drill to start boring its first slab of stone. */
	private static final int DRILL_TICKS = 300;
	/** Pod ticks for the drill to bore through a slab of stone and out the gas pocket in it. */
	private static final int GAS_DRILL_TICKS = 400;
	private static final Input SPRINT = new Input(false, false, false, false, false, false, true);

	/** A chunk of the overworld, with a spot at its east edge where a pod's box and bore cross into the chunk next door. */
	private record Site(int slot) {
		ChunkPos chunk() {
			return new ChunkPos(FIRST_CHUNK_X + slot * SITE_SPACING, FIRST_CHUNK_Z);
		}

		int minX() {
			return chunk().getMinBlockX();
		}

		int minZ() {
			return chunk().getMinBlockZ();
		}

		Vec3 edge() {
			return new Vec3(minX() + 15.9, FLOOR_Y + 1, minZ() + 8.5);
		}

		Vec3 middle() {
			return new Vec3(minX() + 8.5, FLOOR_Y + 1, minZ() + 8.5);
		}
	}

	@GameTest(maxTicks = LOAD_BUDGET_TICKS + POD_TICKS + 100)
	public void aLitPodTickingAtAChunkEdgeLoadsNoNeighbour(GameTestHelper helper) {
		Site site = new Site(0);
		ServerLevel level = helper.getLevel();
		tickAtTheEdge(helper, level, site, POD_TICKS, () -> litPod(helper, level, site.edge()), () -> {
		});
	}

	@GameTest(maxTicks = LOAD_BUDGET_TICKS + POD_TICKS + 100)
	public void aTowedPodAtAChunkEdgeLoadsNoNeighbour(GameTestHelper helper) {
		Site site = new Site(1);
		ServerLevel level = helper.getLevel();
		tickAtTheEdge(helper, level, site, POD_TICKS, () -> {
			PodEntity towed = litPod(helper, level, site.edge());
			PodTowing.attach(litPod(helper, level, site.middle()), towed);
			return towed;
		}, () -> {
		});
	}

	@GameTest(maxTicks = LOAD_BUDGET_TICKS + DRILL_TICKS + 100)
	public void aDrillingPodAtAChunkEdgeLoadsNoNeighbour(GameTestHelper helper) {
		Site site = new Site(2);
		ServerLevel level = helper.getLevel();
		MockPlayer pilot = MockPlayers.join(helper, "edge-pilot");
		tickAtTheEdge(helper, level, site, DRILL_TICKS, () -> {
			// Aligned to its 2 x 2 bore, whose east column is the edge column, so the drill has no reason to slide the pod and its pilot over the edge.
			return sprintingPod(helper, level, pilot, new Vec3(site.minX() + 15.0, FLOOR_Y + 1, site.minZ() + 9.0));
		}, () -> {
			BlockPos edgeCell = new BlockPos(site.minX() + 15, FLOOR_Y, site.minZ() + 9);
			if (!level.getBlockState(edgeCell).is(Blocks.STONE)) {
				throw helper.assertionException("the drill should wait to bore the edge cell " + edgeCell.toShortString()
						+ " until the chunk next to it is loaded, it left " + level.getBlockState(edgeCell));
			}
		});
	}

	/**
	 * A gas pocket drilled out beside the edge: the blast reaches 1 block, so it covers the rock in the edge column too, which it
	 * leaves alone because clearing it would load the chunk next door. The rock inside the chunk goes as before.
	 */
	@GameTest(maxTicks = LOAD_BUDGET_TICKS + GAS_DRILL_TICKS + 100)
	public void aGasBlastAtAChunkEdgeLoadsNoNeighbour(GameTestHelper helper) {
		Site site = new Site(4);
		ServerLevel level = helper.getLevel();
		MockPlayer pilot = MockPlayers.join(helper, "gas-pilot");
		int pocketX = site.minX() + 14;
		int pocketZ = site.minZ() + 9;
		BlockPos pocket = new BlockPos(pocketX, FLOOR_Y, pocketZ);
		tickAtTheEdge(helper, level, site, GAS_DRILL_TICKS, () -> {
			level.setBlock(pocket, HazardBlocks.GAS_POCKET.defaultBlockState(), BUILD_FLAGS);
			return sprintingPod(helper, level, pilot, new Vec3(pocketX, FLOOR_Y + 1, pocketZ));
		}, () -> {
			if (!level.getBlockState(pocket).isAir()) {
				throw helper.assertionException("the pod should have drilled out the gas pocket, it left " + level.getBlockState(pocket));
			}
			if (!level.getBlockState(pocket.east()).is(Blocks.STONE)) {
				throw helper.assertionException("the blast should leave the rock in the edge column, it left " + level.getBlockState(pocket.east()));
			}
			if (!level.getBlockState(pocket.below()).isAir()) {
				throw helper.assertionException("the blast should clear the rock under the pocket, it left " + level.getBlockState(pocket.below()));
			}
		});
	}

	/**
	 * The pod's own chunk is unloaded and its tick hook is fired anyway: no listener may load it, or any chunk next to it. The pod
	 * has a lights part, so the lights listener has a block to place, and a rider, so the handbook's scan has a reason to read around.
	 */
	@GameTest(maxTicks = POD_TICKS + 100)
	public void firingTheTickHookInAnUnloadedChunkLoadsNothing(GameTestHelper helper) {
		Site site = new Site(3);
		ServerLevel level = helper.getLevel();
		MockPlayer rider = MockPlayers.join(helper, "unloaded-rider");
		PodEntity pod = litPod(helper, level, Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2))));
		try {
			if (!rider.player().startRiding(pod, true, false)) {
				throw helper.assertionException("the rider could not mount the pod");
			}
			pod.setPos(site.edge());
			expectUnloaded(helper, level, site, true, "before the hook");
			for (int tick = 0; tick < POD_TICKS; tick++) {
				PodEvents.AFTER_TICK.invoker().afterTick(pod);
				expectUnloaded(helper, level, site, true, "after hook " + tick);
			}
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	/**
	 * Loads the site's chunk with a ticket of radius 0, so no neighbour loads, and builds the floor. Then {@code spawn} puts the
	 * pod to test in it, which is ticked {@code ticks} times, really and through the fired hook, with a check after each that nothing
	 * around the site has loaded. {@code check} runs after the last tick, then the test ends. Whatever happens, the pods at the site
	 * are discarded and the ticket is removed. Call from the test method: the one tick callback is registered here, because
	 * vanilla's GameTest loop crashes on one registered later.
	 */
	private static void tickAtTheEdge(GameTestHelper helper, ServerLevel level, Site site, int ticks, Supplier<PodEntity> spawn, Runnable check) {
		ChunkPos home = site.chunk();
		level.getChunkSource().addTicketWithRadius(TicketType.FORCED, home, 0);
		PodEntity[] pod = {null};
		int[] ticked = {0};
		long deadline = System.nanoTime() + LOAD_BUDGET_SECONDS * 1_000_000_000L;
		helper.onEachTick(() -> {
			boolean finished = false;
			try {
				if (pod[0] == null) {
					if (level.getChunkSource().getChunkNow(home.x(), home.z()) == null) {
						waitForTheChunk(helper, site, deadline);
						return;
					}
					expectUnloaded(helper, level, site, false, "once the chunk loaded");
					buildFloor(level, site);
					expectUnloaded(helper, level, site, false, "once the floor was built");
					pod[0] = spawn.get();
					return;
				}
				pod[0].tick();
				PodEvents.AFTER_TICK.invoker().afterTick(pod[0]);
				ticked[0]++;
				expectUnloaded(helper, level, site, false, "after pod tick " + ticked[0]);
				if (ticked[0] == ticks) {
					check.run();
					finished = true;
				}
			} catch (Throwable failure) {
				finished = true;
				throw failure;
			} finally {
				if (finished) {
					discardPods(level, site);
					level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, home, 0);
				}
			}
			helper.succeed();
		});
	}

	/** Sleeps a moment so the generation threads get the time, and fails once the wall-clock budget for the chunk has run out. */
	private static void waitForTheChunk(GameTestHelper helper, Site site, long deadline) {
		if (System.nanoTime() - deadline > 0) {
			throw helper.assertionException("chunk " + site.chunk() + " was still unloaded " + LOAD_BUDGET_SECONDS
					+ " s after its forced ticket was added, and the test was waiting for it to load");
		}
		try {
			Thread.sleep(LOAD_POLL_MILLIS);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw helper.assertionException("interrupted while waiting for chunk " + site.chunk() + " to load");
		}
	}

	/** Discards every pod standing in the site's chunk, the one under test and any that tow or ride it. */
	private static void discardPods(ServerLevel level, Site site) {
		AABB column = new AABB(site.minX() - 2, level.getMinY(), site.minZ() - 2, site.minX() + 18, level.getMaxY(), site.minZ() + 18);
		for (PodEntity pod : level.getEntitiesOfClass(PodEntity.class, column)) {
			pod.discard();
		}
	}

	private static void buildFloor(ServerLevel level, Site site) {
		for (int x = site.minX(); x < site.minX() + 16; x++) {
			for (int z = site.minZ(); z < site.minZ() + 16; z++) {
				level.setBlock(new BlockPos(x, FLOOR_Y, z), Blocks.STONE.defaultBlockState(), BUILD_FLAGS);
				level.setBlock(new BlockPos(x, FLOOR_Y - 1, z), Blocks.STONE.defaultBlockState(), BUILD_FLAGS);
				for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 6; y++) {
					level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), BUILD_FLAGS);
				}
			}
		}
	}

	/** A lit pod at {@code at} with {@code pilot} aboard, sprinting. */
	private static PodEntity sprintingPod(GameTestHelper helper, ServerLevel level, MockPlayer pilot, Vec3 at) {
		PodEntity pod = litPod(helper, level, at);
		if (!pilot.player().startRiding(pod, true, false)) {
			throw helper.assertionException("the pilot could not mount the pod");
		}
		pilot.setInput(SPRINT);
		return pod;
	}

	/** A pod with a lights part, standing at {@code at}. */
	private static PodEntity litPod(GameTestHelper helper, ServerLevel level, Vec3 at) {
		MinecraftServer server = level.getServer();
		UUID founder = UUID.randomUUID();
		Optional<?> refusal = Charters.found(server, founder, "Chunk" + founder.toString().substring(0, 8));
		if (refusal.isPresent()) {
			throw helper.assertionException("founding a charter was refused: " + refusal.get());
		}
		CharterId charter = Charters.charterOfOrThrow(server, founder).orElseThrow().id();
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(at);
		level.addFreshEntity(pod);
		PodComponents.register(pod, charter);
		PodComponents.install(pod, ComponentItems.mint(server, ComponentTrack.LIGHTS, 2, charter));
		return pod;
	}

	/** Fails if any chunk in the 3 x 3 around the site's is loaded, or, with {@code includingOwn} false, any but its own. */
	private static void expectUnloaded(GameTestHelper helper, ServerLevel level, Site site, boolean includingOwn, String when) {
		List<ChunkPos> loaded = new ArrayList<>();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				ChunkPos chunk = new ChunkPos(site.chunk().x() + dx, site.chunk().z() + dz);
				boolean own = dx == 0 && dz == 0;
				if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null && (includingOwn || !own)) {
					loaded.add(chunk);
				}
			}
		}
		if (!loaded.isEmpty()) {
			throw helper.assertionException("chunks " + loaded + " were loaded " + when);
		}
	}
}
