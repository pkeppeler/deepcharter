package io.github.pkeppeler.deepcharter.test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodFuelItems;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/**
 * Server GameTests for #60: stats overrides change what a pod does, the hull has a maximum, and fuel items come
 * from data. An override is keyed by pod, so that a test's pod is the only one it touches.
 */
public class PodStatsTest {
	private static final int FLOOR_Y = 1;
	private static final int FLOOR_RADIUS = 3;
	private static final float SOUTH = 0f;
	private static final Input FORWARD = new Input(true, false, false, false, false, false, false);
	private static final Input JUMP = new Input(false, false, false, false, true, false, false);
	private static final Input SPRINT = new Input(false, false, false, false, false, false, true);
	/** Layer 1 at these columns is the test's own: PodDrillTest uses x 3000 to 3704. */
	private static final int Z = 3000;
	private static final int FAR_MARGIN_TICKS = 1000;
	private static final int FAR_TICKS = FarChunks.AWAIT_BUDGET_TICKS + FAR_MARGIN_TICKS;

	private static final Map<UUID, UnaryOperator<PodStats>> OVERRIDES = new ConcurrentHashMap<>();
	private static final Map<UUID, Integer> DEPLETED = new ConcurrentHashMap<>();

	static {
		PodStats.MODIFY.register((pod, stats) -> OVERRIDES.getOrDefault(pod.getUUID(), UnaryOperator.identity()).apply(stats));
		PodEvents.HULL_DEPLETED.register(pod -> DEPLETED.merge(pod.getUUID(), 1, Integer::sum));
	}

	private static PodEntity spawnOnFloor(GameTestHelper helper, UnaryOperator<PodStats> override) {
		return spawnOnFloor(helper, 0, override);
	}

	private static PodEntity spawnOnFloor(GameTestHelper helper, double height, UnaryOperator<PodStats> override) {
		fillFloor(helper, Blocks.STONE);
		PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(FLOOR_RADIUS + 0.5, FLOOR_Y + 1 + height, FLOOR_RADIUS + 0.5));
		OVERRIDES.put(pod.getUUID(), override);
		return pod;
	}

	private static MockPlayer seatPilot(GameTestHelper helper, PodEntity pod) {
		MockPlayer pilot = MockPlayers.join(helper, "stats-pilot");
		pilot.teleportTo(helper.getLevel(), pod.position(), SOUTH, 0f);
		if (!pilot.player().startRiding(pod)) {
			throw helper.assertionException("the pilot could not mount the pod");
		}
		return pilot;
	}

	private static void fillFloor(GameTestHelper helper, Block block) {
		for (int x = -FLOOR_RADIUS; x <= FLOOR_RADIUS; x++) {
			for (int z = -FLOOR_RADIUS; z <= FLOOR_RADIUS; z++) {
				helper.setBlock(new BlockPos(x + FLOOR_RADIUS, FLOOR_Y, z + FLOOR_RADIUS), block);
			}
		}
	}

	private static void cleanUp(GameTestHelper helper, PodEntity pod, MockPlayer pilot) {
		if (pilot != null) {
			pilot.leave();
		}
		OVERRIDES.remove(pod.getUUID());
		pod.discard();
		fillFloor(helper, Blocks.AIR);
	}

	private static float percentPerTick(float litresPerSecond, float tankLitres) {
		return litresPerSecond / 20f / tankLitres * 100f;
	}

	@GameTest
	public void noOverrideLeavesTheTuningAsItWas(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		try {
			PodStats stats = PodStats.of(pod);
			PodTuning tuning = PodTuning.DEFAULT;
			if (!stats.equals(PodStats.base()) || stats.horizontalSpeed() != tuning.movement().horizontalSpeed()
					|| stats.enginePower() != tuning.movement().enginePower() || stats.cargoSlots() != tuning.cargo().slots()
					|| stats.maxHull() != tuning.shell().fullHull() || stats.tankLitres() != tuning.fuel().tankLitres()) {
				throw helper.assertionException("a pod nothing modifies should have the tuning's stats, got %s", stats);
			}
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	@GameTest(maxTicks = 60)
	public void speedOverrideChangesHowFarTheTreadsDrive(GameTestHelper helper) {
		float speed = PodTuning.DEFAULT.movement().horizontalSpeed() * 2f;
		PodEntity pod = spawnOnFloor(helper, stats -> stats.withHorizontalSpeed(speed));
		MockPlayer pilot = seatPilot(helper, pod);
		Vec3 start = pod.position();
		pilot.setInput(FORWARD);
		int ticks = 10;
		helper.runAfterDelay(ticks, () -> {
			try {
				double moved = pod.position().subtract(start).horizontalDistance();
				double expected = speed * ticks;
				if (Math.abs(moved - expected) > expected * 0.15) {
					throw helper.assertionException("at double speed the pod should drive about %s blocks in %s ticks, drove %s", expected, ticks, moved);
				}
				helper.succeed();
			} finally {
				cleanUp(helper, pod, pilot);
			}
		});
	}

	@GameTest(maxTicks = 60)
	public void liftOverrideLetsAPodThatCannotLiftClimb(GameTestHelper helper) {
		float enginePower = PodTuning.DEFAULT.movement().enginePower();
		// Loaded to the stock engine's power the rotor has no lift (PodMovementTest); a stronger engine has some.
		PodEntity pod = spawnOnFloor(helper, stats -> stats.withEnginePower(enginePower * 10f));
		pod.setCargoMass(enginePower);
		MockPlayer pilot = seatPilot(helper, pod);
		double startY = pod.getY();
		pilot.setInput(JUMP);
		helper.runAfterDelay(15, () -> {
			try {
				if (pod.getY() - startY < 1.0 || !pod.flying()) {
					throw helper.assertionException("a pod loaded to the stock engine power but with ten times the engine should climb, rose %s, flying %s",
							pod.getY() - startY, pod.flying());
				}
				helper.succeed();
			} finally {
				cleanUp(helper, pod, pilot);
			}
		});
	}

	@GameTest(maxTicks = 60)
	public void drainOverrideBurnsFuelFaster(GameTestHelper helper) {
		float idle = PodTuning.DEFAULT.fuel().idleLitresPerSecond();
		float tank = PodTuning.DEFAULT.fuel().tankLitres();
		PodEntity drained = spawnOnFloor(helper, stats -> stats.withIdleLitresPerSecond(idle * 10f));
		PodEntity control = helper.spawn(PodRegistry.POD, new Vec3(1.5, FLOOR_Y + 1, 1.5));
		int ticks = 20;
		helper.runAfterDelay(ticks, () -> {
			try {
				float full = PodTuning.DEFAULT.shell().fullFuel();
				float drainedBurn = full - drained.fuel();
				float controlBurn = full - control.fuel();
				float expected = ticks * percentPerTick(idle * 10f, tank);
				if (Math.abs(drainedBurn - expected) > expected * 0.25f) {
					throw helper.assertionException("ten times the idle drain should burn about %s over %s ticks, burned %s", expected, ticks, drainedBurn);
				}
				if (drainedBurn < controlBurn * 5f) {
					throw helper.assertionException("the drained pod should burn far more than its control, %s against %s", drainedBurn, controlBurn);
				}
				helper.succeed();
			} finally {
				control.discard();
				cleanUp(helper, drained, null);
			}
		});
	}

	@GameTest
	public void slotsOverrideChangesWhatTheBayHolds(GameTestHelper helper) {
		PodEntity roomy = helper.spawn(PodRegistry.POD, 2, 2, 2);
		PodEntity cramped = helper.spawn(PodRegistry.POD, 4, 2, 2);
		OVERRIDES.put(roomy.getUUID(), stats -> stats.withCargoSlots(9));
		OVERRIDES.put(cramped.getUUID(), stats -> stats.withCargoSlots(2));
		try {
			ItemStack ore = OreRegistry.stack(OreType.IRONIUM);
			int roomyHeld = fill(roomy, ore, 12);
			int crampedHeld = fill(cramped, ore, 12);
			if (roomyHeld != 9 || roomy.cargoUsed() != 9 || crampedHeld != 2 || cramped.cargoUsed() != 2) {
				throw helper.assertionException("the bays should hold 9 and 2 ore, held %s and %s", roomyHeld, crampedHeld);
			}
			helper.succeed();
		} finally {
			OVERRIDES.remove(roomy.getUUID());
			OVERRIDES.remove(cramped.getUUID());
			roomy.discard();
			cramped.discard();
		}
	}

	private static int fill(PodEntity pod, ItemStack ore, int attempts) {
		int held = 0;
		for (int i = 0; i < attempts; i++) {
			if (pod.cargo().tryAdd(pod, ore)) {
				held++;
			}
		}
		return held;
	}

	@GameTest
	public void overridesComposeInRegistrationOrder(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		PodStats.Modifier doubler = (target, stats) -> target == pod ? stats.withCargoSlots(stats.cargoSlots() * 2) : stats;
		PodStats.Modifier adder = (target, stats) -> target == pod ? stats.withCargoSlots(stats.cargoSlots() + 1) : stats;
		PodStats.MODIFY.register(doubler);
		PodStats.MODIFY.register(adder);
		try {
			int slots = PodStats.of(pod).cargoSlots();
			int stock = PodTuning.DEFAULT.cargo().slots();
			if (slots != stock * 2 + 1) {
				throw helper.assertionException("doubling then adding one should give %s slots, got %s", stock * 2 + 1, slots);
			}
			helper.succeed();
		} finally {
			// Fabric cannot unregister a listener: the two stay, and touch no pod but this one.
			pod.discard();
		}
	}

	@GameTest
	public void aBadStatFailsWhereItIsSet(GameTestHelper helper) {
		PodStats base = PodStats.base();
		expectRejected(helper, () -> base.withHorizontalSpeed(-0.1f));
		expectRejected(helper, () -> base.withEnginePower(0f));
		expectRejected(helper, () -> base.withMaxHull(Float.NaN));
		expectRejected(helper, () -> base.withTankLitres(Float.POSITIVE_INFINITY));
		expectRejected(helper, () -> base.withCargoSlots(-1));
		expectRejected(helper, () -> base.withIdleLitresPerSecond(Float.NaN));
		helper.succeed();
	}

	private static void expectRejected(GameTestHelper helper, Runnable bad) {
		try {
			bad.run();
		} catch (IllegalArgumentException expected) {
			return;
		}
		throw helper.assertionException("a stat that is negative, zero where it must not be, or not a number should be refused");
	}

	@GameTest
	public void hullHasAMaximumAndAnOverrideChangesIt(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		try {
			float full = PodTuning.DEFAULT.shell().fullHull();
			if (pod.maxHull() != full || pod.hull() != full) {
				throw helper.assertionException("a new pod should have its full hull %s, got %s of %s", full, pod.hull(), pod.maxHull());
			}
			pod.setHull(full * 5f);
			if (pod.hull() != full) {
				throw helper.assertionException("the hull should not pass its maximum %s, got %s", full, pod.hull());
			}
			OVERRIDES.put(pod.getUUID(), stats -> stats.withMaxHull(full * 1.5f));
			pod.setHull(full * 5f);
			if (pod.maxHull() != full * 1.5f || pod.hull() != full * 1.5f) {
				throw helper.assertionException("with a larger maximum the hull should reach it, got %s of %s", pod.hull(), pod.maxHull());
			}
			helper.succeed();
		} finally {
			OVERRIDES.remove(pod.getUUID());
			pod.discard();
		}
	}

	@GameTest
	public void damageReducesHullAndDepletionFiresOnceAtZero(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		try {
			float full = pod.hull();
			pod.damageHull(30f);
			if (pod.hull() != full - 30f || DEPLETED.containsKey(pod.getUUID())) {
				throw helper.assertionException("30 damage should leave %s with no depletion, got %s and %s", full - 30f, pod.hull(), DEPLETED);
			}
			pod.damageHull(full * 10f);
			pod.damageHull(5f);
			if (pod.hull() != 0f || DEPLETED.getOrDefault(pod.getUUID(), 0) != 1) {
				throw helper.assertionException("a hull taken to 0 should stay 0 and fire HULL_DEPLETED once, got hull %s and %s events",
						pod.hull(), DEPLETED.getOrDefault(pod.getUUID(), 0));
			}
			try {
				pod.damageHull(-1f);
			} catch (IllegalArgumentException expected) {
				helper.succeed();
				return;
			}
			throw helper.assertionException("negative damage should be refused");
		} finally {
			pod.discard();
		}
	}

	@GameTest(maxTicks = 100)
	public void landingDamageFollowsTheStats(GameTestHelper helper) {
		PodEntity padded = spawnOnFloor(helper, 12, stats -> stats.withHullDamagePerBlock(0f));
		PodEntity control = helper.spawn(PodRegistry.POD, new Vec3(1.5, FLOOR_Y + 1 + 12, 1.5));
		float before = padded.hull();
		helper.runAfterDelay(50, () -> {
			try {
				boolean landed = padded.onGround() && control.onGround();
				if (!landed || padded.hull() != before || control.hull() >= before) {
					throw helper.assertionException("both pods should land from 12 blocks, the padded one keeping %s and the control losing hull, got %s and %s (landed %s)",
							before, padded.hull(), control.hull(), landed);
				}
				helper.succeed();
			} finally {
				control.discard();
				cleanUp(helper, padded, null);
			}
		});
	}

	@GameTest
	public void aFuelItemDefinedOnlyInDataRefuelsThePod(GameTestHelper helper) {
		// The tag and the litres of the blaze rod are in this test mod's data, and nowhere in the code.
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		MockPlayer player = MockPlayers.join(helper, "data-refueler");
		try {
			player.player().setGameMode(GameType.SURVIVAL);
			double litres = PodFuelItems.litresOf(new ItemStack(Items.BLAZE_ROD)).orElseThrow(
					() -> helper.assertionException("the blaze rod should be fuel from this test's data"));
			if (litres != 7.5) {
				throw helper.assertionException("the blaze rod's table entry is 7.5 litres, got %s", litres);
			}
			pod.setFuel(10f);
			player.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BLAZE_ROD, 2));
			InteractionResult result = useOnPod(player, pod);
			float expected = 10f + (float) litres / PodTuning.DEFAULT.fuel().tankLitres() * 100f;
			int left = player.player().getItemInHand(InteractionHand.MAIN_HAND).getCount();
			if (!result.consumesAction() || Math.abs(pod.fuel() - expected) > 0.5f || left != 1) {
				throw helper.assertionException("a blaze rod should bring fuel to %s and use one, got result %s fuel %s left %s",
						expected, result, pod.fuel(), left);
			}
			helper.succeed();
		} finally {
			player.leave();
			pod.discard();
		}
	}

	@GameTest
	public void theBuiltInFuelsAreInTheTableToo(GameTestHelper helper) {
		for (var fuel : List.of(Items.COAL, Items.CHARCOAL)) {
			if (PodFuelItems.litresOf(new ItemStack(fuel)).orElse(0) != 2.0) {
				throw helper.assertionException("%s should give 2 litres from the data table", fuel);
			}
		}
		if (PodFuelItems.litresOf(new ItemStack(Items.STICK)).isPresent()) {
			throw helper.assertionException("a stick is not fuel");
		}
		helper.succeed();
	}

	@GameTest
	public void aTaggedItemWithNoLitresEntryOrABadFileIsLoggedAndNotFuel(GameTestHelper helper) {
		// This test's data tags the feather (no file) and the flint (a file with negative litres), next to the blaze rod.
		List<Item> missing = PodFuelItems.checkLitresFor(List.of(Items.COAL, Items.STICK, Items.FEATHER, Items.FLINT, Items.BLAZE_ROD));
		if (!missing.equals(List.of(Items.STICK, Items.FEATHER, Items.FLINT))) {
			throw helper.assertionException("the items with no litres should be the stick, feather and flint, got %s", missing);
		}
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		MockPlayer player = MockPlayers.join(helper, "bad-data-refueler");
		try {
			pod.setFuel(10f);
			player.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FEATHER, 2));
			InteractionResult feather = useOnPod(player, pod);
			player.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT, 2));
			InteractionResult flint = useOnPod(player, pod);
			if (feather.consumesAction() || flint.consumesAction() || pod.fuel() != 10f
					|| player.player().getItemInHand(InteractionHand.MAIN_HAND).getCount() != 2) {
				throw helper.assertionException("a tagged item with no usable litres entry must not refuel, got %s and %s, fuel %s", feather, flint, pod.fuel());
			}
			helper.succeed();
		} finally {
			player.leave();
			pod.discard();
		}
	}

	@GameTest
	public void tankSizeChangesThePercentAFuelItemGives(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		OVERRIDES.put(pod.getUUID(), stats -> stats.withTankLitres(20f));
		MockPlayer player = MockPlayers.join(helper, "tank-refueler");
		try {
			player.player().setGameMode(GameType.SURVIVAL);
			pod.setFuel(10f);
			player.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL, 2));
			useOnPod(player, pod);
			// Coal gives 2 litres: 20 percent of the stock 10 litre tank, 10 percent of a 20 litre one.
			if (Math.abs(pod.fuel() - 20f) > 0.01f) {
				throw helper.assertionException("2 litres in a 20 litre tank should bring 10 percent to 20, got %s", pod.fuel());
			}
			helper.succeed();
		} finally {
			OVERRIDES.remove(pod.getUUID());
			player.leave();
			pod.discard();
		}
	}

	@GameTest
	public void aCapListenerRunsAfterABaseListenerThatRegisteredLater(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		PodStats.MODIFY.register(PodStats.CAP, (target, stats) -> target == pod ? stats.withCargoSlots(Math.min(stats.cargoSlots(), 8)) : stats);
		PodStats.MODIFY.register(PodStats.BASE, (target, stats) -> target == pod ? stats.withCargoSlots(stats.cargoSlots() + 10) : stats);
		try {
			int slots = PodStats.of(pod).cargoSlots();
			if (slots != 8) {
				throw helper.assertionException("adding 10 in BASE then capping at 8 in CAP should give 8 slots, got %s", slots);
			}
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void aSavedHullThatIsNotAHullLoadsAsNoneAndDamageDoesNotThrow(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		CompoundTag saved = save(level, pod);
		pod.discard();
		for (float bad : new float[] {Float.NaN, -5f, Float.POSITIVE_INFINITY}) {
			saved.putFloat("hull", bad);
			PodEntity loaded = (PodEntity) load(level, saved);
			try {
				loaded.damageHull(1f);
				loaded.setHull(loaded.hull());
				if (loaded.hull() != 0f || DEPLETED.containsKey(loaded.getUUID())) {
					throw helper.assertionException("a saved hull of %s should load as 0 with no HULL_DEPLETED, got hull %s and %s events",
							bad, loaded.hull(), DEPLETED.getOrDefault(loaded.getUUID(), 0));
				}
			} finally {
				loaded.discard();
			}
		}
		helper.succeed();
	}

	/** A mock pilot, and the pod it sits in once the far chunk ticks entities; {@link #pod} stays null until then. */
	private static final class FarRig {
		private final MockPlayer pilot;
		private PodEntity pod;

		private FarRig(MockPlayer pilot) {
			this.pilot = pilot;
		}

		boolean ready() {
			return pod != null;
		}

		/** Must be called from the test method. */
		static FarRig await(GameTestHelper helper, ServerLevel level, Vec3 at, String name, Consumer<PodEntity> prepare) {
			MockPlayer pilot = MockPlayers.join(helper, name);
			pilot.teleportTo(level, at, 0f, 0f);
			FarRig rig = new FarRig(pilot);
			FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(at), () -> {
				PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
				pod.setPos(at);
				level.addFreshEntity(pod);
				if (!pilot.player().startRiding(pod)) {
					throw helper.assertionException(Component.literal("the pilot could not mount the pod"));
				}
				prepare.accept(pod);
				pilot.setInput(SPRINT);
				rig.pod = pod;
			});
			return rig;
		}
	}

	private static ServerLevel layer(GameTestHelper helper, int layer) {
		ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(layer));
		if (level == null) {
			throw helper.assertionException(Component.literal("dimension " + LayerChain.dimension(layer) + " did not load"));
		}
		return level;
	}

	private static void box(ServerLevel level, int x1, int x2, int y1, int y2, int z1, int z2, Block block) {
		BlockState state = block.defaultBlockState();
		for (BlockPos pos : BlockPos.betweenClosed(x1, y1, z1, x2, y2, z2)) {
			level.setBlock(pos, state, Block.UPDATE_CLIENTS);
		}
	}

	@GameTest(maxTicks = FAR_TICKS)
	public void halvedTicksPerHardnessBoresInAboutHalfTheTicks(GameTestHelper helper) {
		int x = 4000;
		int floor = 60;
		ServerLevel level = layer(helper, 1);
		box(level, x - 3, x + 8, floor - 8, floor - 1, Z - 3, Z + 4, Blocks.STONE);
		box(level, x - 3, x + 8, floor, floor + 10, Z - 3, Z + 4, Blocks.AIR);
		FarRig[] rigs = new FarRig[2];
		int[] columns = {x, x + 5};
		for (int i = 0; i < 2; i++) {
			boolean fast = i == 1;
			rigs[i] = FarRig.await(helper, level, new Vec3(columns[i], floor, Z + 1), "drill-stats-" + i, pod -> {
				if (fast) {
					OVERRIDES.put(pod.getUUID(), stats -> stats.withTicksPerHardness(stats.ticksPerHardness() / 2f));
				}
			});
		}
		int[] start = {-1, -1};
		int[] end = {-1, -1};
		helper.onEachTick(() -> {
			for (int i = 0; i < 2; i++) {
				if (!rigs[i].ready()) {
					continue;
				}
				if (start[i] < 0 && rigs[i].pod.drilling()) {
					start[i] = rigs[i].pod.tickCount;
				}
				if (start[i] >= 0 && end[i] < 0 && level.getBlockState(new BlockPos(columns[i], floor - 1, Z)).isAir()) {
					end[i] = rigs[i].pod.tickCount;
				}
			}
			if (end[0] >= 0 && end[1] >= 0) {
				int slow = end[0] - start[0];
				int fast = end[1] - start[1];
				OVERRIDES.remove(rigs[1].pod.getUUID());
				rigs[0].pod.discard();
				rigs[1].pod.discard();
				if (fast >= slow || Math.abs(2 * fast - slow) > 4) {
					throw helper.assertionException(Component.literal(String.format(
							"half the ticks per hardness should bore in about half the ticks, the control took %d and the other %d", slow, fast)));
				}
				helper.succeed();
			}
		});
	}

	/** Boring the one crust row left under a pod in layer 1 with the pod's hull and crust damage set; checks the pod that arrives in layer 2. */
	private static void borePodThroughCrust(GameTestHelper helper, int x, float hull, float crustDamage, Consumer<PodEntity> check) {
		ServerLevel one = layer(helper, 1);
		box(one, x - 2, x + 2, 0, 2, Z - 2, Z + 2, LayerBlocks.BREACH_CRUST);
		box(one, x - 2, x + 2, 1, 8, Z - 2, Z + 2, Blocks.AIR);
		UUID[] id = new UUID[1];
		FarRig rig = FarRig.await(helper, one, new Vec3(x, 1, Z), "crust-stats-" + x, pod -> {
			id[0] = pod.getUUID();
			OVERRIDES.put(pod.getUUID(), stats -> stats.withCrustHullDamage(crustDamage));
			pod.setHull(hull);
		});
		helper.onEachTick(() -> {
			if (!rig.ready() || !rig.pilot.player().level().dimension().equals(LayerChain.dimension(2))) {
				return;
			}
			rig.pilot.releaseInput();
			if (!(rig.pilot.player().getVehicle() instanceof PodEntity crossed)) {
				throw helper.assertionException(Component.literal("the pilot crossed without the pod"));
			}
			OVERRIDES.remove(id[0]);
			try {
				check.accept(crossed);
			} finally {
				crossed.discard();
			}
			DEPLETED.remove(id[0]);
			helper.succeed();
		});
	}

	@GameTest(maxTicks = FAR_TICKS)
	public void crustDamageStatSetsWhatABoredCrustCostsTheHull(GameTestHelper helper) {
		borePodThroughCrust(helper, 4064, 10f, 3f, crossed -> {
			if (Math.abs(crossed.hull() - 7f) > 0.01f) {
				throw helper.assertionException(Component.literal("a crust bore costing 3 should take a hull of 10 to 7, got " + crossed.hull()));
			}
		});
	}

	@GameTest(maxTicks = FAR_TICKS)
	public void aCrustBoreThatTakesTheLastHullFiresHullDepleted(GameTestHelper helper) {
		borePodThroughCrust(helper, 4128, 15f, 20f, crossed -> {
			int events = DEPLETED.getOrDefault(crossed.getUUID(), 0);
			if (crossed.hull() != 0f || events != 1) {
				throw helper.assertionException(Component.literal(
						"a crust bore costing 20 should take a hull of 15 to 0 and fire HULL_DEPLETED once, got hull " + crossed.hull() + " and " + events + " events"));
			}
		});
	}

	@GameTest
	public void aPodSavedBeforeStatsLoadsWithItsHullPoints(GameTestHelper helper) {
		// M1 saved the hull as a plain float of percentage points under "hull": that is now hull points, and the
		// default maximum is 100, so the saved value means the same. A hull above the maximum (a saved pod whose
		// bigger maximum is gone) loads as it was and is held to the maximum at the next change.
		ServerLevel level = helper.getLevel();
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		CompoundTag saved = save(level, pod);
		pod.discard();
		saved.putFloat("hull", 37.5f);
		Entity damaged = load(level, saved);
		saved.putFloat("hull", 250f);
		Entity oversized = load(level, saved);
		try {
			PodEntity damagedPod = (PodEntity) damaged;
			PodEntity oversizedPod = (PodEntity) oversized;
			if (damagedPod.hull() != 37.5f || damagedPod.maxHull() != PodTuning.DEFAULT.shell().fullHull()) {
				throw helper.assertionException("an M1 pod with 37.5 hull should load with 37.5 of %s, got %s of %s",
						PodTuning.DEFAULT.shell().fullHull(), damagedPod.hull(), damagedPod.maxHull());
			}
			if (oversizedPod.hull() != 250f) {
				throw helper.assertionException("a saved hull above the maximum should load as it was, got %s", oversizedPod.hull());
			}
			oversizedPod.damageHull(0f);
			if (oversizedPod.hull() != oversizedPod.maxHull()) {
				throw helper.assertionException("the next change should hold the hull to its maximum, got %s of %s",
						oversizedPod.hull(), oversizedPod.maxHull());
			}
			helper.succeed();
		} finally {
			damaged.discard();
			oversized.discard();
		}
	}

	private static CompoundTag save(ServerLevel level, PodEntity pod) {
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		pod.saveWithoutId(output);
		return output.buildResult();
	}

	private static Entity load(ServerLevel level, CompoundTag tag) {
		return EntityType.create(PodRegistry.POD,
				TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag.copy()),
				level, EntitySpawnReason.LOAD).orElseThrow(() -> new AssertionError("the saved pod did not load"));
	}

	private static InteractionResult useOnPod(MockPlayer player, PodEntity pod) {
		return UseEntityCallback.EVENT.invoker().interact(player.player(), pod.level(), InteractionHand.MAIN_HAND, pod, null);
	}
}
