package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.mojang.serialization.Dynamic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodLightLedger;
import io.github.pkeppeler.deepcharter.pod.PodLightsTuning;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Server GameTests for {@link io.github.pkeppeler.deepcharter.pod.PodLights}. The one listener here acts only on pods whose
 * UUID a test has put in {@link #DARK}.
 */
public class PodLightsTest {
	private static final Logger LOGGER = LoggerFactory.getLogger(PodLightsTest.class);
	private static final int FLOOR_Y = 1;
	private static final int FLOOR_RADIUS = 3;
	private static final Vec3 SPAWN = new Vec3(FLOOR_RADIUS + 0.5, FLOOR_Y + 1, FLOOR_RADIUS + 0.5);
	/** How far from a pod a test looks for light blocks where nothing else stands: the far columns of the chunk and layer tests. */
	private static final int LOOK = 6;
	/**
	 * How far from its own pod, or from a light it placed itself, a test looks on the shared floor grid. The tests of a run start
	 * together in a row of structures that their pod grids overlap, so a pod of another test can stand a few blocks away.
	 */
	private static final int OWN = 1;
	private static final int SLOT_SPACING = 10;
	private static final int CROSSING_TICKS = 200;
	/** Ticks that cover two sweeps of every dimension, with a margin. */
	private static final int TWO_SWEEPS = 2 * PodLightsTuning.DEFAULT.sweepIntervalTicks() + 5;
	private static final int COST_WARMUP_TICKS = 100;
	private static final int COST_TICKS = 300;
	/** Two moving pods may spend no more than this in a server tick (10% of the 50 ms budget); loose enough for a GC or JIT pause on CI. */
	private static final long COST_BUDGET_NANOS = 5_000_000L;
	private static final AtomicInteger CHARTERS = new AtomicInteger();
	private static final Set<UUID> DARK = ConcurrentHashMap.newKeySet();

	static {
		PodEvents.IS_POWERED.register(pod -> !DARK.contains(pod.getUUID()));
	}

	@GameTest
	public void aLightsPartLightsThePodsCellAtThePartsLevel(GameTestHelper helper) {
		CharterId charter = charter(helper);
		PodEntity tierOne = litPod(helper, charter, 1, 0);
		PodEntity capped = litPod(helper, charter, 4, 1);
		PodEntity bare = podOnFloor(helper, 2);
		PodEntity voided = podOnFloor(helper, 3);
		PodComponents.register(voided, charter(helper));
		PodComponents.install(voided, ComponentItems.mint(helper.getLevel().getServer(), ComponentTrack.LIGHTS, 2, charter));
		try {
			for (PodEntity pod : List.of(tierOne, capped, bare, voided)) {
				afterTick(pod);
			}
			expectLights(helper, "a tier 1 part is level 6", tierOne, 6);
			expectLights(helper, "a tier 4 part on a chassis that takes tier 2 is level 9", capped, 9);
			expectLights(helper, "a pod with no lights part", bare);
			expectLights(helper, "a part of another charter is void", voided);
			helper.succeed();
		} finally {
			discardAll(tierOne, capped, bare, voided);
		}
	}

	/**
	 * Looks only along the pod's path, see {@link #OWN}. Waits for the settled state, because a
	 * release that cannot change its block yet (an unloaded neighbour chunk) leaves the old light for the next sweep.
	 */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS)
	public void theLightFollowsThePodAndLeavesNoTrail(GameTestHelper helper) {
		PodEntity pod = litPod(helper, charter(helper), 2, 0);
		ServerLevel level = (ServerLevel) pod.level();
		BlockPos start = pod.blockPosition();
		FarChunks.Deadline deadline = FarChunks.deadline();
		int[] step = {0};
		BlockPos[] before = {null};
		helper.onEachTick(() -> {
			try {
				List<BlockPos> lights = lightsOnPath(level, start, pod);
				boolean settled = lights.size() == 1 && isInPod(lights.getFirst(), pod);
				deadline.await(helper, level, settled, () -> String.format(
						"after %d blocks the pod at %s should hold one light inside it, but lights on its path were %s and ledger entries %s",
						step[0], pod.blockPosition(), lights, ledgerOnPath(level, start, pod)));
				if (!settled) {
					return;
				}
				BlockPos now = lights.getFirst();
				if (now.equals(before[0])) {
					throw failure(helper, "after %d blocks the light is still at %s", step[0], now);
				}
				before[0] = now;
				if (step[0] < 4) {
					step[0]++;
					pod.setPos(pod.getX() + 1, pod.getY(), pod.getZ());
					return;
				}
				Set<GlobalPos> ledger = ledgerOnPath(level, start, pod);
				if (ledger.size() != 1) {
					throw failure(helper, "the ledger should hold the one light, holds %s", ledger);
				}
				pod.discard();
				helper.succeed();
			} catch (RuntimeException e) {
				pod.discard();
				throw e;
			}
		});
	}

	@GameTest
	public void aStrandedOrWreckedPodIsDarkAndItsLightReturnsWhenItIsNot(GameTestHelper helper) {
		PodEntity stranded = litPod(helper, charter(helper), 2, 0);
		PodEntity wrecked = litPod(helper, charter(helper), 2, 1);
		try {
			afterTick(stranded);
			afterTick(wrecked);
			expectLights(helper, "a working pod", stranded, 9);
			stranded.setStranded(true);
			afterTick(stranded);
			expectLights(helper, "a stranded pod", stranded);
			stranded.setStranded(false);
			afterTick(stranded);
			expectLights(helper, "a pod that is no longer stranded", stranded, 9);

			wrecked.damageHull(wrecked.maxHull());
			afterTick(wrecked);
			expectLights(helper, "a wreck", wrecked);
			if (!ledgerEntries(wrecked).isEmpty()) {
				throw failure(helper, "a wreck's light is still in the ledger: %s", ledgerEntries(wrecked));
			}
			helper.succeed();
		} finally {
			discardAll(stranded, wrecked);
		}
	}

	@GameTest
	public void removingThePodRemovesItsLightAndSparesAForeignOne(GameTestHelper helper) {
		PodEntity pod = litPod(helper, charter(helper), 2, 0);
		ServerLevel level = helper.getLevel();
		BlockPos foreign = BlockPos.containing(pod.position()).offset(4, 0, 4);
		level.setBlock(foreign, light(15), 3);
		try {
			afterTick(pod);
			expectLights(helper, "a lit pod and a builder's light", pod, List.of(foreign), 9, 15);
			pod.discard();
			List<Integer> left = lightLevels(level, pod.blockPosition(), foreign);
			if (!left.equals(List.of(15))) {
				throw failure(helper, "only the builder's level 15 light should be left, found levels %s", left);
			}
			helper.succeed();
		} finally {
			// room-carver: removes a light this test placed itself
			level.setBlock(foreign, Blocks.AIR.defaultBlockState(), 3);
			pod.discard();
		}
	}

	@GameTest
	public void neverReplacesABlockOrTakesAForeignLight(GameTestHelper helper) {
		PodEntity pod = litPod(helper, charter(helper), 2, 0);
		ServerLevel level = helper.getLevel();
		BlockPos at = pod.blockPosition();
		List<BlockPos> column = new ArrayList<>();
		for (int dy = -1; dy <= 3; dy++) {
			column.add(at.above(dy));
		}
		try {
			// Solid rock around the pod's whole column: nothing to light, and nothing may be replaced.
			for (BlockPos cell : column) {
				level.setBlock(cell, Blocks.STONE.defaultBlockState(), 3);
			}
			afterTick(pod);
			for (BlockPos cell : column) {
				if (!level.getBlockState(cell).is(Blocks.STONE)) {
					throw failure(helper, "the pod replaced the stone at %s with %s", cell, level.getBlockState(cell));
				}
			}
			// A builder's light block sits where the pod would put its own: the pod leaves it, and leaves it after it is gone.
			BlockPos builders = at.above(1);
			level.setBlock(builders, light(15), 3);
			afterTick(pod);
			pod.discard();
			if (!level.getBlockState(builders).is(Blocks.LIGHT) || level.getBlockState(builders).getValue(LightBlock.LEVEL) != 15) {
				throw failure(helper, "the builder's light at %s was changed to %s", builders, level.getBlockState(builders));
			}
			if (!ledgerEntriesIn(level, at, OWN).isEmpty()) {
				throw failure(helper, "the pod put nothing down, yet the ledger holds %s", ledgerEntriesIn(level, at, OWN));
			}
			helper.succeed();
		} finally {
			for (BlockPos cell : column) {
				// room-carver: removes a light this test placed itself
				level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
			}
			pod.discard();
		}
	}

	@GameTest
	public void aPodInAFloodedColumnStaysDark(GameTestHelper helper) {
		PodEntity pod = litPod(helper, charter(helper), 2, 0);
		ServerLevel level = helper.getLevel();
		BlockPos at = pod.blockPosition();
		List<BlockPos> column = new ArrayList<>();
		for (int dy = 0; dy <= 3; dy++) {
			column.add(at.above(dy));
		}
		try {
			for (BlockPos cell : column) {
				level.setBlock(cell, Blocks.WATER.defaultBlockState(), 3);
			}
			afterTick(pod);
			expectLights(helper, "a pod in water", pod);
			if (!ledgerEntriesIn(level, at, OWN).isEmpty()) {
				throw failure(helper, "the pod put nothing down, yet the ledger holds %s", ledgerEntriesIn(level, at, OWN));
			}
			for (BlockPos cell : column) {
				if (!level.getBlockState(cell).is(Blocks.WATER)) {
					throw failure(helper, "the water at %s was replaced by %s", cell, level.getBlockState(cell));
				}
			}
			helper.succeed();
		} finally {
			for (BlockPos cell : column) {
				// room-carver: removes a light this test placed itself
				level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
			}
			pod.discard();
		}
	}

	@GameTest(maxTicks = 100)
	public void aLedgerEntryNoPodHoldsIsSweptAndNothingElseIs(GameTestHelper helper) {
		PodEntity pod = litPod(helper, charter(helper), 2, 0);
		ServerLevel level = helper.getLevel();
		BlockPos stale = pod.blockPosition().offset(-4, 0, -4);
		BlockPos foreign = pod.blockPosition().offset(4, 0, -4);
		afterTick(pod);
		// What a crash leaves: a light block in a saved chunk, its ledger entry saved with it, and no pod holding it.
		PodLightLedger.get(level.getServer()).record(GlobalPos.of(level.dimension(), stale));
		level.setBlock(stale, light(12), 3);
		level.setBlock(foreign, light(15), 3);
		helper.succeedWhen(() -> {
			if (!level.getBlockState(stale).isAir()) {
				throw failure(helper, "the stale light at %s is still there", stale);
			}
			if (PodLightLedger.get(level.getServer()).entries().contains(GlobalPos.of(level.dimension(), stale))) {
				throw failure(helper, "the swept light is still in the ledger");
			}
			expectLights(helper, "after the sweep", pod, List.of(foreign), 9, 15);
			// room-carver: removes a light this test placed itself
			level.setBlock(foreign, Blocks.AIR.defaultBlockState(), 3);
			pod.discard();
		});
	}

	@GameTest
	public void aLedgerOfAnotherVersionIsKeptAndRefusesToChange(GameTestHelper helper) {
		CompoundTag future = new CompoundTag();
		future.putInt("version", 99);
		future.putString("added-in-v99", "kept");
		PodLightLedger ledger = PodLightLedger.CODEC.parse(new Dynamic<>(NbtOps.INSTANCE, future)).getOrThrow();
		if (ledger.isReadable()) {
			throw failure(helper, "version 99 should load as unreadable");
		}
		Object written = PodLightLedger.CODEC.encodeStart(NbtOps.INSTANCE, ledger).getOrThrow();
		if (!future.equals(written)) {
			throw failure(helper, "the unreadable ledger should be written back as it was, got %s", written);
		}
		try {
			ledger.record(GlobalPos.of(helper.getLevel().dimension(), BlockPos.ZERO));
			throw failure(helper, "recording into an unreadable ledger must fail loud");
		} catch (IllegalStateException expected) {
			helper.succeed();
		}
	}

	/** The chunk goes away with a lit pod in it, and comes back with the pod unpowered, so the only way the light goes is cleanup. */
	@GameTest(maxTicks = 2 * FarChunks.AWAIT_BUDGET_TICKS + 1200)
	public void unloadingThePodsChunkLeavesNoLight(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = new BlockPos(3040, 64, 3040);
		int chunkX = origin.getX() >> 4;
		int chunkZ = origin.getZ() >> 4;
		CharterId charter = charter(helper);
		PodEntity[] pod = {null};
		int[] phase = {0};
		int[] since = {0};
		FarChunks.Deadline[] unloadBy = {null};
		helper.onEachTick(() -> {
			if (pod[0] == null) {
				return;
			}
			switch (phase[0]) {
				case 0 -> {
					if (!lightsNear(level, origin, LOOK).isEmpty()) {
						level.setChunkForced(chunkX, chunkZ, false);
						unloadBy[0] = FarChunks.deadline();
						phase[0] = 1;
					}
				}
				case 1 -> {
					if (unloadBy[0].awaitUnloaded(helper, level, chunkX, chunkZ)) {
						DARK.add(pod[0].getUUID());
						level.setChunkForced(chunkX, chunkZ, true);
						phase[0] = 2;
					}
				}
				case 2 -> {
					if (level.getEntity(pod[0].getUUID()) instanceof PodEntity) {
						since[0]++;
					}
					if (since[0] >= TWO_SWEEPS) {
						PodEntity back = (PodEntity) level.getEntity(pod[0].getUUID());
						List<BlockPos> left = lightsNear(level, origin, LOOK);
						if (!left.isEmpty() || !ledgerEntriesIn(level, origin, LOOK).isEmpty()) {
							throw failure(helper, "after the chunk came back, light blocks %s and ledger %s were left", left, ledgerEntriesIn(level, origin, LOOK));
						}
						back.discard();
						helper.succeed();
					}
				}
				default -> throw new IllegalStateException("phase " + phase[0]);
			}
		});
		FarChunks.awaitEntityTicking(helper, level, origin, () -> {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					level.setBlock(origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
				}
			}
			PodEntity made = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
			made.setPos(Vec3.atBottomCenterOf(origin));
			level.addFreshEntity(made);
			PodComponents.register(made, charter);
			PodComponents.install(made, ComponentItems.mint(level.getServer(), ComponentTrack.LIGHTS, 2, charter));
			pod[0] = made;
		});
	}

	/** A piloted pod falls out of layer_1 through an open shaft; the instant it has arrived, layer_1 must hold no light of it. */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
	public void crossingABreachLeavesNoLightBehind(GameTestHelper helper) {
		double x = 3500.5;
		double z = 3500.5;
		ServerLevel one = layer(helper, 1);
		ServerLevel two = layer(helper, 2);
		openShaft(one, x, z);
		MockPlayer mock = MockPlayers.join(helper, "lights-crossing");
		mock.teleportTo(one, new Vec3(x, 8, z), 0, 0);
		PodEntity[] pod = {null};
		boolean[] checked = {false};
		FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(x, 8, z), () -> {
			pod[0] = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
			pod[0].setPos(x, 8, z);
			one.addFreshEntity(pod[0]);
			MinecraftServer server = one.getServer();
			if (Charters.found(server, mock.player().getUUID(), "Lights crossing " + CHARTERS.incrementAndGet()).isPresent()) {
				throw failure(helper, "the charter was refused");
			}
			CharterId charter = Charters.charterOfOrThrow(server, mock.player().getUUID()).orElseThrow().id();
			PodComponents.register(pod[0], charter);
			PodComponents.install(pod[0], ComponentItems.mint(server, ComponentTrack.LIGHTS, 2, charter));
			if (!mock.player().startRiding(pod[0], true, false)) {
				throw failure(helper, "the mock could not board the pod");
			}
		});
		helper.succeedWhen(() -> {
			if (pod[0] == null || !(mock.player().getVehicle() instanceof PodEntity arrived) || arrived.level() != two) {
				throw failure(helper, "waiting for the pod to cross");
			}
			if (!checked[0]) {
				checked[0] = true;
				List<BlockPos> left = lightsInBox(one, BlockPos.containing(x, one.getMinY(), z), 4, one.getMinY(), 20);
				if (!left.isEmpty() || !ledgerEntriesIn(one, BlockPos.containing(x, 8, z), 40).isEmpty()) {
					helper.fail(Component.literal(String.format("the pod crossed but layer_1 still holds lights %s, ledger %s",
							left, ledgerEntriesIn(one, BlockPos.containing(x, 8, z), 40))));
				}
			}
			if (lightsNear(two, arrived.blockPosition(), LOOK).size() != 1) {
				throw failure(helper, "the arrived pod should have lit its new cell, lights %s", lightsNear(two, arrived.blockPosition(), LOOK));
			}
		});
	}

	/**
	 * Measures what two pods cost a server tick: the time of the pods' AFTER_TICK hook, for two pods with a lights part that
	 * stand still, two that move a block every tick (the worst case), and two with no part (what the other listeners cost).
	 * The numbers go to the log; the test fails only past {@link #COST_BUDGET_NANOS}.
	 */
	@GameTest(maxTicks = 3 * (COST_WARMUP_TICKS + COST_TICKS) + 40)
	public void twoPodsCostLittleOfATick(GameTestHelper helper) {
		CharterId charter = charter(helper);
		PodEntity[][] stages = {
				{litPod(helper, charter, 2, 0), litPod(helper, charter, 2, 1)},
				{litPod(helper, charter, 2, 2), litPod(helper, charter, 2, 3)},
				{podOnFloor(helper, 4), podOnFloor(helper, 5)}};
		int ticksPerStage = COST_WARMUP_TICKS + COST_TICKS;
		long[] total = new long[stages.length];
		helper.onEachTick(() -> {
			long tick = helper.getTick();
			int stage = (int) (tick / ticksPerStage);
			if (stage >= stages.length) {
				return;
			}
			if (stage == 1) {
				for (PodEntity pod : stages[stage]) {
					pod.setPos(pod.getX() + (tick % 2 == 0 ? 1 : -1), pod.getY(), pod.getZ());
				}
			}
			long start = System.nanoTime();
			for (PodEntity pod : stages[stage]) {
				afterTick(pod);
			}
			long spent = System.nanoTime() - start;
			if (tick % ticksPerStage >= COST_WARMUP_TICKS) {
				total[stage] += spent;
			}
			if (tick == stages.length * ticksPerStage - 1) {
				LOGGER.info("pod lights tick cost, 2 pods, mean ns per server tick over {} ticks: standing lit {}, moving lit {}, no lights part {}",
						COST_TICKS, total[0] / COST_TICKS, total[1] / COST_TICKS, total[2] / COST_TICKS);
				for (PodEntity[] pods : stages) {
					discardAll(pods);
				}
				if (total[1] / COST_TICKS > COST_BUDGET_NANOS) {
					throw failure(helper, "two moving lit pods cost %d ns a tick, over the %d ns budget", total[1] / COST_TICKS, COST_BUDGET_NANOS);
				}
				helper.succeed();
			}
		});
	}

	/** A pod on its own floor in a grid of floors {@link #SLOT_SPACING} apart, so that one test can keep several pods apart. */
	private static PodEntity podOnFloor(GameTestHelper helper, int slot) {
		int dx = slot % 3 * SLOT_SPACING;
		int dz = slot / 3 * SLOT_SPACING;
		for (int x = 0; x <= 2 * FLOOR_RADIUS; x++) {
			for (int z = 0; z <= 2 * FLOOR_RADIUS; z++) {
				helper.setBlock(new BlockPos(dx + x, FLOOR_Y, dz + z), Blocks.STONE);
			}
		}
		return helper.spawn(PodRegistry.POD, SPAWN.add(dx, 0, dz));
	}

	private static PodEntity litPod(GameTestHelper helper, CharterId charter, int tier, int slot) {
		PodEntity pod = podOnFloor(helper, slot);
		PodComponents.register(pod, charter);
		PodComponents.install(pod, ComponentItems.mint(helper.getLevel().getServer(), ComponentTrack.LIGHTS, tier, charter));
		return pod;
	}

	/** The hook the server tick fires for the pod, which is all that {@link io.github.pkeppeler.deepcharter.pod.PodLights} listens to. */
	private static void afterTick(PodEntity pod) {
		PodEvents.AFTER_TICK.invoker().afterTick(pod);
	}

	private static void discardAll(PodEntity... pods) {
		for (PodEntity pod : pods) {
			pod.discard();
		}
	}

	private static BlockState light(int level) {
		return Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, level);
	}

	private static CharterId charter(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		UUID founder = UUID.randomUUID();
		Optional<?> refusal = Charters.found(server, founder, "Lights " + CHARTERS.incrementAndGet());
		if (refusal.isPresent()) {
			throw failure(helper, "a charter step was refused: %s", refusal.get());
		}
		return Charters.charterOfOrThrow(server, founder).orElseThrow().id();
	}

	/** The light blocks within {@link #OWN} of any of {@code centers}, as their levels, sorted. */
	private static List<Integer> lightLevels(ServerLevel level, BlockPos... centers) {
		Set<BlockPos> found = new HashSet<>();
		for (BlockPos center : centers) {
			found.addAll(lightsNear(level, center, OWN));
		}
		return found.stream().map(pos -> level.getBlockState(pos).getValue(LightBlock.LEVEL)).sorted().toList();
	}

	/** The levels of the lights next to the pod, as {@link #lightLevels} reads them; a pod of another test is out of reach. */
	private static void expectLights(GameTestHelper helper, String what, PodEntity pod, Integer... levels) {
		expectLights(helper, what, pod, List.of(), levels);
	}

	/** As above, and also the lights next to {@code alsoAt}: the ones the test placed itself. */
	private static void expectLights(GameTestHelper helper, String what, PodEntity pod, List<BlockPos> alsoAt, Integer... levels) {
		List<BlockPos> centers = new ArrayList<>(alsoAt);
		centers.add(pod.blockPosition());
		List<Integer> found = lightLevels((ServerLevel) pod.level(), centers.toArray(BlockPos[]::new));
		if (!found.equals(List.of(levels))) {
			throw failure(helper, "%s: expected light levels %s, found %s", what, List.of(levels), found);
		}
	}

	/** The light blocks within a block of the pod's path from {@code start} to where it is now. */
	private static List<BlockPos> lightsOnPath(ServerLevel level, BlockPos start, PodEntity pod) {
		BlockPos now = pod.blockPosition();
		List<BlockPos> found = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(start.getX() - 1, now.getY() - 2, now.getZ() - 1, now.getX() + 1, now.getY() + 2, now.getZ() + 1)) {
			if (level.getBlockState(pos).is(Blocks.LIGHT)) {
				found.add(pos.immutable());
			}
		}
		return found;
	}

	private static Set<GlobalPos> ledgerOnPath(ServerLevel level, BlockPos start, PodEntity pod) {
		BlockPos now = pod.blockPosition();
		return PodLightLedger.get(level.getServer()).entries().stream()
				.filter(entry -> entry.dimension().equals(level.dimension())
						&& entry.pos().getX() >= start.getX() - 1 && entry.pos().getX() <= now.getX() + 1
						&& Math.abs(entry.pos().getZ() - now.getZ()) <= 1
						&& Math.abs(entry.pos().getY() - now.getY()) <= 2)
				.collect(Collectors.toUnmodifiableSet());
	}

	/** True when {@code light} is in the pod's own column and inside its height. */
	private static boolean isInPod(BlockPos light, PodEntity pod) {
		return light.getX() == pod.blockPosition().getX() && light.getZ() == pod.blockPosition().getZ()
				&& light.getY() >= Math.floor(pod.getBoundingBox().minY) && light.getY() <= Math.floor(pod.getBoundingBox().maxY);
	}

	private static List<BlockPos> lightsNear(ServerLevel level, BlockPos around, int radius) {
		return lightsInBox(level, around, radius, around.getY() - radius, around.getY() + radius);
	}

	private static List<BlockPos> lightsInBox(ServerLevel level, BlockPos around, int radius, int minY, int maxY) {
		List<BlockPos> found = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(around.getX() - radius, minY, around.getZ() - radius,
				around.getX() + radius, maxY, around.getZ() + radius)) {
			if (level.getBlockState(pos).is(Blocks.LIGHT)) {
				found.add(pos.immutable());
			}
		}
		return found;
	}

	private static Set<GlobalPos> ledgerEntries(PodEntity pod) {
		return ledgerEntriesIn((ServerLevel) pod.level(), pod.blockPosition(), OWN);
	}

	private static Set<GlobalPos> ledgerEntriesIn(ServerLevel level, BlockPos around, int radius) {
		return PodLightLedger.get(level.getServer()).entries().stream()
				.filter(entry -> entry.dimension().equals(level.dimension())
						&& Math.abs(entry.pos().getX() - around.getX()) <= radius
						&& Math.abs(entry.pos().getZ() - around.getZ()) <= radius
						&& Math.abs(entry.pos().getY() - around.getY()) <= radius)
				.collect(Collectors.toUnmodifiableSet());
	}

	/** Clear a 3x3 shaft through the floor of {@code level}, wide enough for the pod's 1.9-block hull. */
	private static void openShaft(ServerLevel level, double x, double z) {
		BlockPos column = BlockPos.containing(x, 0, z);
		RoomCarver.carve(level, column.offset(-1, 0, -1).atY(level.getMinY()), column.offset(1, 0, 1).atY(level.getMinY() + 10),
				Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
	}

	private static ServerLevel layer(GameTestHelper helper, int layer) {
		ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(layer));
		if (level == null) {
			throw failure(helper, "dimension %s did not load", LayerChain.dimension(layer));
		}
		return level;
	}

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}
}
