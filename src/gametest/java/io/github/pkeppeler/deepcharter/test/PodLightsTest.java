package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.RoomSeal;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodLightLedger;
import io.github.pkeppeler.deepcharter.pod.PodLightsTuning;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
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
	/** How far from a pod a test looks for light blocks. */
	private static final int LOOK = 6;
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

	@GameTest
	public void theLightFollowsThePodAndLeavesNoTrail(GameTestHelper helper) {
		PodEntity pod = litPod(helper, charter(helper), 2, 0);
		try {
			afterTick(pod);
			BlockPos first = onlyLight(helper, pod);
			for (int step = 1; step <= 4; step++) {
				pod.setPos(pod.getX() + 1, pod.getY(), pod.getZ());
				afterTick(pod);
				BlockPos now = onlyLight(helper, pod);
				if (now.equals(first)) {
					throw failure(helper, "after %d blocks the light is still at %s", step, first);
				}
			}
			if (ledgerEntries(pod).size() != 1) {
				throw failure(helper, "the ledger should hold the one light, holds %s", ledgerEntries(pod));
			}
			helper.succeed();
		} finally {
			pod.discard();
		}
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
			expectLights(helper, "a lit pod and a builder's light", pod, 9, 15);
			pod.discard();
			List<Integer> left = lightLevels(level, pod.blockPosition());
			if (!left.equals(List.of(15))) {
				throw failure(helper, "only the builder's level 15 light should be left, found levels %s", left);
			}
			helper.succeed();
		} finally {
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
			if (!ledgerEntriesIn(level, at, LOOK).isEmpty()) {
				throw failure(helper, "the pod put nothing down, yet the ledger holds %s", ledgerEntriesIn(level, at, LOOK));
			}
			helper.succeed();
		} finally {
			for (BlockPos cell : column) {
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
			if (!ledgerEntriesIn(level, at, LOOK).isEmpty()) {
				throw failure(helper, "the pod put nothing down, yet the ledger holds %s", ledgerEntriesIn(level, at, LOOK));
			}
			for (BlockPos cell : column) {
				if (!level.getBlockState(cell).is(Blocks.WATER)) {
					throw failure(helper, "the water at %s was replaced by %s", cell, level.getBlockState(cell));
				}
			}
			helper.succeed();
		} finally {
			for (BlockPos cell : column) {
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
			expectLights(helper, "after the sweep", pod, 9, 15);
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

	/** The light blocks within {@link #LOOK} of {@code pod}, as their levels, sorted. */
	private static List<Integer> lightLevels(ServerLevel level, BlockPos around) {
		return lightsNear(level, around, LOOK).stream()
				.map(pos -> level.getBlockState(pos).getValue(LightBlock.LEVEL)).sorted().toList();
	}

	private static void expectLights(GameTestHelper helper, String what, PodEntity pod, Integer... levels) {
		List<Integer> found = lightLevels((ServerLevel) pod.level(), pod.blockPosition());
		if (!found.equals(List.of(levels))) {
			throw failure(helper, "%s: expected light levels %s, found %s", what, List.of(levels), found);
		}
	}

	/** The pod's one light, which must be in the pod's own column and inside its height. */
	private static BlockPos onlyLight(GameTestHelper helper, PodEntity pod) {
		List<BlockPos> found = lightsNear((ServerLevel) pod.level(), pod.blockPosition(), LOOK);
		if (found.size() != 1) {
			throw failure(helper, "expected one light near the pod, found %s", found);
		}
		BlockPos light = found.getFirst();
		if (light.getX() != pod.blockPosition().getX() || light.getZ() != pod.blockPosition().getZ()
				|| light.getY() < Math.floor(pod.getBoundingBox().minY) || light.getY() > Math.floor(pod.getBoundingBox().maxY)) {
			throw failure(helper, "the light at %s is not inside the pod at %s", light, pod.getBoundingBox());
		}
		return light;
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
		return ledgerEntriesIn((ServerLevel) pod.level(), pod.blockPosition(), LOOK);
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
		RoomSeal.seal(level, column.offset(-1, 0, -1).atY(level.getMinY()), column.offset(1, 0, 1).atY(level.getMinY() + 10));
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int y = level.getMinY(); y <= level.getMinY() + 10; y++) {
					level.setBlock(column.offset(dx, 0, dz).atY(y), Blocks.AIR.defaultBlockState(), 3);
				}
			}
		}
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
