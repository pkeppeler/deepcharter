package io.github.pkeppeler.deepcharter.test;

import java.util.List;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.pod.PodCargo;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodFuel;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/** Server GameTests for pod cargo, lift and fuel. */
public class PodCargoFuelTest {
	private static final int FLOOR_Y = 1;
	private static final int FLOOR_RADIUS = 3;
	private static final float SOUTH = 0f;
	private static final Input JUMP = new Input(false, false, false, false, true, false, false);
	private static final Input FORWARD = new Input(true, false, false, false, false, false, false);
	private static final Input FORWARD_JUMP = new Input(true, false, false, false, true, false, false);

	private static final PodTuning.Cargo CARGO = PodTuning.DEFAULT.cargo();
	private static final PodTuning.Fuel FUEL = PodTuning.DEFAULT.fuel();
	private static final float FULL_FUEL = PodTuning.DEFAULT.shell().fullFuel();

	private static void fillFloor(GameTestHelper helper, Block block) {
		for (int x = -FLOOR_RADIUS; x <= FLOOR_RADIUS; x++) {
			for (int z = -FLOOR_RADIUS; z <= FLOOR_RADIUS; z++) {
				helper.setBlock(new BlockPos(x + FLOOR_RADIUS, FLOOR_Y, z + FLOOR_RADIUS), block);
			}
		}
	}

	private static PodEntity spawnPod(GameTestHelper helper, double height) {
		fillFloor(helper, Blocks.STONE);
		return helper.spawn(PodRegistry.POD, new Vec3(FLOOR_RADIUS + 0.5, FLOOR_Y + 1 + height, FLOOR_RADIUS + 0.5));
	}

	private static MockPlayer seatPilot(GameTestHelper helper, PodEntity pod) {
		MockPlayer pilot = MockPlayers.join(helper, "cargo-pilot");
		pilot.teleportTo(helper.getLevel(), pod.position(), SOUTH, 0f);
		if (!pilot.player().startRiding(pod)) {
			throw helper.assertionException("the pilot could not mount the pod");
		}
		return pilot;
	}

	private static void cleanUp(GameTestHelper helper, PodEntity pod, MockPlayer pilot) {
		if (pilot != null) {
			pilot.leave();
		}
		pod.discard();
		fillFloor(helper, Blocks.AIR);
	}

	private static float expectedPercentPerTick(float litresPerSecond) {
		return litresPerSecond / 20f / FUEL.tankLitres() * 100f;
	}

	private static InteractionResult useOnPod(MockPlayer player, PodEntity pod) {
		return UseEntityCallback.EVENT.invoker().interact(player.player(), pod.level(), InteractionHand.MAIN_HAND, pod, null);
	}

	@GameTest
	public void bayHoldsSevenOreAndRefusesTheEighth(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		try {
			for (int i = 0; i < CARGO.slots(); i++) {
				if (!pod.cargo().tryAdd(pod, Blocks.IRON_ORE, 1f)) {
					throw helper.assertionException("ore %s of %s should fit", i + 1, CARGO.slots());
				}
			}
			if (CARGO.slots() != 7 || pod.cargoUsed() != 7) {
				throw helper.assertionException("the bay should have 7 slots, all used, got %s slots and %s used", CARGO.slots(), pod.cargoUsed());
			}
			if (pod.cargo().tryAdd(pod, Blocks.GOLD_ORE, 1f)) {
				throw helper.assertionException("a full bay must refuse another ore");
			}
			if (pod.cargoUsed() != 7 || pod.cargoMass() != 7f || pod.cargo().entries().size() != 7) {
				throw helper.assertionException("a refused ore must change nothing, got used %s mass %s", pod.cargoUsed(), pod.cargoMass());
			}
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void everyOreAddsItsMass(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		try {
			pod.cargo().tryAdd(pod, Blocks.IRON_ORE, 3f);
			pod.cargo().tryAdd(pod, Blocks.DIAMOND_ORE, 30f);
			pod.cargo().tryAdd(pod, Blocks.COAL_ORE);
			float expected = 3f + 30f + CARGO.defaultOreMass();
			if (pod.cargoMass() != expected || pod.cargoUsed() != 3) {
				throw helper.assertionException("mass should be %s over 3 slots, got %s over %s", expected, pod.cargoMass(), pod.cargoUsed());
			}
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void takeoffLimitIsWhereThrustMatchesGravity(GameTestHelper helper) {
		// Independent of the formula: power 100 * gravity 0.08 / thrust 0.16.
		if (Math.abs(PodCargo.takeoffMassLimit() - 50f) > 1e-4) {
			throw helper.assertionException("the takeoff limit should be 50, got %s", PodCargo.takeoffMassLimit());
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 60)
	public void massExactlyAtTheLimitCannotLift(GameTestHelper helper) {
		PodEntity pod = spawnPod(helper, 0);
		MockPlayer pilot = seatPilot(helper, pod);
		pod.cargo().tryAdd(pod, Blocks.DIAMOND_ORE, PodCargo.takeoffMassLimit());
		double startY = pod.getY();
		pilot.setInput(JUMP);
		helper.runAfterDelay(20, () -> {
			try {
				if (pod.getY() - startY > 1e-6) {
					throw helper.assertionException("a pod at exactly the limit should not lift, rose %s", pod.getY() - startY);
				}
				helper.succeed();
			} finally {
				cleanUp(helper, pod, pilot);
			}
		});
	}

	private static ValueInput inputOf(ServerLevel level, CompoundTag tag) {
		return TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag);
	}

	private static CompoundTag savedPod(ServerLevel level, PodEntity pod) {
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		pod.saveWithoutId(output);
		return output.buildResult();
	}

	@GameTest
	public void loadDerivesTheCountsFromTheEntries(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		PodEntity copy = helper.spawn(PodRegistry.POD, 4, 2, 2);
		try {
			pod.cargo().tryAdd(pod, Blocks.IRON_ORE, 4f);
			pod.cargo().tryAdd(pod, Blocks.GOLD_ORE, 6f);
			CompoundTag tag = savedPod(level, pod);
			// Stale or tampered counts from an older format must not matter.
			tag.putInt("cargo_used", 7);
			tag.putFloat("cargo_mass", 999f);
			copy.cargo().load(inputOf(level, tag), copy);
			if (copy.cargoUsed() != 2 || copy.cargoMass() != 10f) {
				throw helper.assertionException("counts should derive from the 2 entries as 2/10, got %s/%s", copy.cargoUsed(), copy.cargoMass());
			}
			helper.succeed();
		} finally {
			pod.discard();
			copy.discard();
		}
	}

	@GameTest
	public void loadFailsLoudWithoutCargoKey(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		try {
			CompoundTag tag = savedPod(level, pod);
			tag.remove("cargo");
			try {
				pod.cargo().load(inputOf(level, tag), pod);
			} catch (IllegalStateException expected) {
				helper.succeed();
				return;
			}
			throw helper.assertionException("loading a save with no cargo key should throw");
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void loadFailsLoudWithMoreThanSevenEntries(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		try {
			for (int i = 0; i < CARGO.slots(); i++) {
				pod.cargo().tryAdd(pod, Blocks.IRON_ORE, 1f);
			}
			CompoundTag tag = savedPod(level, pod);
			ListTag list = tag.getListOrEmpty("cargo");
			list.add(list.getFirst().copy());
			try {
				pod.cargo().load(inputOf(level, tag), pod);
			} catch (IllegalStateException expected) {
				helper.succeed();
				return;
			}
			throw helper.assertionException("loading 8 ore into a 7-slot bay should throw");
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void anotherPodsEntryPointAndBadMassAreRefused(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		PodEntity other = helper.spawn(PodRegistry.POD, 4, 2, 2);
		try {
			boolean crossed = false;
			try {
				pod.cargo().tryAdd(other, Blocks.IRON_ORE, 1f);
			} catch (IllegalArgumentException expected) {
				crossed = true;
			}
			boolean nan = false;
			try {
				pod.cargo().tryAdd(pod, Blocks.IRON_ORE, Float.NaN);
			} catch (IllegalArgumentException expected) {
				nan = true;
			}
			if (!crossed || !nan || pod.cargoUsed() != 0 || other.cargoUsed() != 0) {
				throw helper.assertionException("a foreign pod and a NaN mass must both throw and change nothing, crossed %s nan %s", crossed, nan);
			}
			helper.succeed();
		} finally {
			pod.discard();
			other.discard();
		}
	}

	private static CommandSourceStack source(MockPlayer player, LevelBasedPermissionSet permissions) {
		return player.player().createCommandSourceStack().withPermission(permissions);
	}

	@GameTest
	public void dumpCommandFailsWhenNotRidingAPod(GameTestHelper helper) {
		MockPlayer player = MockPlayers.join(helper, "walker");
		try {
			int result = helper.getLevel().getServer().getCommands().getDispatcher()
					.execute("deepcharter pod dump", source(player, LevelBasedPermissionSet.GAMEMASTER));
			if (result != 0) {
				throw helper.assertionException("dump with no pod should fail with 0, got %s", result);
			}
			helper.succeed();
		} catch (CommandSyntaxException e) {
			throw helper.assertionException("dump should be allowed for an op: %s", e.getMessage());
		} finally {
			player.leave();
		}
	}

	@GameTest(maxTicks = 80)
	public void dumpCommandEmptiesCargoAndRestoresLift(GameTestHelper helper) {
		PodEntity pod = spawnPod(helper, 0);
		MockPlayer pilot = seatPilot(helper, pod);
		pod.cargo().tryAdd(pod, Blocks.DIAMOND_ORE, PodCargo.takeoffMassLimit() + 10f);
		pod.cargo().tryAdd(pod, Blocks.DIAMOND_ORE, 1f);
		try {
			int result = helper.getLevel().getServer().getCommands().getDispatcher()
					.execute("deepcharter pod dump", source(pilot, LevelBasedPermissionSet.GAMEMASTER));
			if (result != 2 || pod.cargoUsed() != 0 || pod.cargoMass() != 0f) {
				throw helper.assertionException("dump should empty 2 ore, got %s, left %s/%s", result, pod.cargoUsed(), pod.cargoMass());
			}
		} catch (CommandSyntaxException e) {
			cleanUp(helper, pod, pilot);
			throw helper.assertionException("dump should be allowed for an op: %s", e.getMessage());
		}
		double startY = pod.getY();
		pilot.setInput(JUMP);
		helper.runAfterDelay(15, () -> {
			try {
				if (pod.getY() - startY < 1.0) {
					throw helper.assertionException("after the dump command the pod should lift, rose %s", pod.getY() - startY);
				}
				helper.succeed();
			} finally {
				cleanUp(helper, pod, pilot);
			}
		});
	}

	@GameTest
	public void dumpCommandRefusesANonOp(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		MockPlayer pilot = seatPilot(helper, pod);
		pod.cargo().tryAdd(pod, Blocks.IRON_ORE, 1f);
		try {
			boolean refused = false;
			try {
				helper.getLevel().getServer().getCommands().getDispatcher()
						.execute("deepcharter pod dump", source(pilot, LevelBasedPermissionSet.ALL));
			} catch (CommandSyntaxException expected) {
				refused = true;
			}
			if (!refused || pod.cargoUsed() != 1) {
				throw helper.assertionException("a non-op must be refused and the cargo kept, refused %s used %s", refused, pod.cargoUsed());
			}
			helper.succeed();
		} finally {
			pilot.leave();
			pod.discard();
		}
	}

	@GameTest
	public void spawnCommandIsOpOnly(GameTestHelper helper) {
		MockPlayer player = MockPlayers.join(helper, "spawner");
		try {
			var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
			boolean refused = false;
			try {
				dispatcher.execute("deepcharter pod spawn", source(player, LevelBasedPermissionSet.ALL));
			} catch (CommandSyntaxException expected) {
				refused = true;
			}
			int result = dispatcher.execute("deepcharter pod spawn", source(player, LevelBasedPermissionSet.GAMEMASTER));
			if (!refused || result != 1) {
				throw helper.assertionException("spawn must be refused to a non-op (%s) and run for an op (result %s)", refused, result);
			}
			helper.succeed();
		} catch (CommandSyntaxException e) {
			throw helper.assertionException("spawn should be allowed for an op: %s", e.getMessage());
		} finally {
			player.leave();
		}
	}

	@GameTest
	public void creativeRefuelKeepsTheCoal(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		MockPlayer player = MockPlayers.join(helper, "creative-refueler");
		try {
			player.player().setGameMode(GameType.CREATIVE);
			pod.setFuel(30f);
			player.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL, 3));
			useOnPod(player, pod);
			int left = player.player().getItemInHand(InteractionHand.MAIN_HAND).getCount();
			if (pod.fuel() <= 30f || left != 3) {
				throw helper.assertionException("a creative refuel should add fuel and keep the coal, fuel %s left %s", pod.fuel(), left);
			}
			helper.succeed();
		} finally {
			player.leave();
			pod.discard();
		}
	}

	@GameTest(maxTicks = 80)
	public void overloadedPodCannotLiftAndDumpingRestoresLift(GameTestHelper helper) {
		PodEntity pod = spawnPod(helper, 0);
		MockPlayer pilot = seatPilot(helper, pod);
		// Past the limit but under the engine power: the rotor spins (flying) yet the pod cannot climb.
		float each = (PodCargo.takeoffMassLimit() + 10f) / 2;
		pod.cargo().tryAdd(pod, Blocks.DIAMOND_ORE, each);
		pod.cargo().tryAdd(pod, Blocks.DIAMOND_ORE, each);
		double startY = pod.getY();
		pilot.setInput(JUMP);
		helper.runAfterDelay(15, () -> {
			if (pod.getY() - startY > 1e-6) {
				cleanUp(helper, pod, pilot);
				throw helper.assertionException("an overloaded pod should not lift, rose %s", pod.getY() - startY);
			}
			int dumped = pod.cargo().dump(pod);
			if (dumped != 2 || pod.cargoUsed() != 0 || pod.cargoMass() != 0f) {
				cleanUp(helper, pod, pilot);
				throw helper.assertionException("dump should empty 2 ore, dumped %s, left %s/%s", dumped, pod.cargoUsed(), pod.cargoMass());
			}
			helper.runAfterDelay(15, () -> {
				try {
					if (pod.getY() - startY < 1.0 || !pod.flying()) {
						throw helper.assertionException("after dumping the pod should lift, rose %s, flying %s", pod.getY() - startY, pod.flying());
					}
					helper.succeed();
				} finally {
					cleanUp(helper, pod, pilot);
				}
			});
		});
	}

	@GameTest(maxTicks = 80)
	public void justUnderTheLimitStillLifts(GameTestHelper helper) {
		PodEntity pod = spawnPod(helper, 0);
		MockPlayer pilot = seatPilot(helper, pod);
		pod.cargo().tryAdd(pod, Blocks.DIAMOND_ORE, PodCargo.takeoffMassLimit() - 10f);
		double startY = pod.getY();
		pilot.setInput(JUMP);
		helper.runAfterDelay(40, () -> {
			try {
				if (pod.getY() - startY < 0.1) {
					throw helper.assertionException("mass just under the limit should still lift, rose %s", pod.getY() - startY);
				}
				helper.succeed();
			} finally {
				cleanUp(helper, pod, pilot);
			}
		});
	}

	@GameTest
	public void cargoSurvivesSaveAndLoad(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		pod.cargo().tryAdd(pod, Blocks.IRON_ORE, 3f);
		pod.cargo().tryAdd(pod, Blocks.GOLD_ORE, 6f);
		pod.cargo().tryAdd(pod, Blocks.DIAMOND_ORE, 30f);
		List<PodCargo.Entry> before = List.copyOf(pod.cargo().entries());
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		pod.saveWithoutId(output);
		pod.discard();

		Entity loaded = EntityType.create(
				PodRegistry.POD,
				TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult()),
				level,
				EntitySpawnReason.LOAD).orElseThrow(() -> helper.assertionException("the saved pod did not load"));
		try {
			if (!(loaded instanceof PodEntity copy)) {
				throw helper.assertionException("loaded a %s instead of a pod", loaded);
			}
			if (!copy.cargo().entries().equals(before) || copy.cargoUsed() != 3 || copy.cargoMass() != 39f) {
				throw helper.assertionException("cargo did not survive save and load: %s, used %s mass %s",
						copy.cargo().entries(), copy.cargoUsed(), copy.cargoMass());
			}
			if (copy.cargo().dump(copy) != 3 || copy.cargoUsed() != 0 || copy.cargoMass() != 0f) {
				throw helper.assertionException("a loaded bay should dump like a live one");
			}
			helper.succeed();
		} finally {
			loaded.discard();
		}
	}

	@GameTest
	public void drainOrderIsIdleThenMovingThenDrilling(GameTestHelper helper) {
		float idle = PodFuel.drainPercentPerTick(PodFuel.Activity.IDLE);
		float moving = PodFuel.drainPercentPerTick(PodFuel.Activity.MOVING);
		float drilling = PodFuel.drainPercentPerTick(PodFuel.Activity.DRILLING);
		if (!(0f < idle && idle < moving && moving < drilling)) {
			throw helper.assertionException("drain should rise idle < moving < drilling, got %s %s %s", idle, moving, drilling);
		}
		if (Math.abs(idle - expectedPercentPerTick(FUEL.idleLitresPerSecond())) > 1e-6
				|| Math.abs(drilling - expectedPercentPerTick(FUEL.drillingLitresPerSecond())) > 1e-6) {
			throw helper.assertionException("rates should follow the tuning in litres of a %s L tank, got %s and %s", FUEL.tankLitres(), idle, drilling);
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 60)
	public void podsBurnFuelIdleAndFasterWhenMoving(GameTestHelper helper) {
		PodEntity idlePod = spawnPod(helper, 0);
		PodEntity movingPod = helper.spawn(PodRegistry.POD, new Vec3(FLOOR_RADIUS + 0.5, FLOOR_Y + 1, 0.5));
		MockPlayer pilot = seatPilot(helper, movingPod);
		pilot.setInput(FORWARD);
		int ticks = 10;
		helper.runAfterDelay(ticks, () -> {
			try {
				float idleBurn = FULL_FUEL - idlePod.fuel();
				float movingBurn = FULL_FUEL - movingPod.fuel();
				float expectedIdle = ticks * expectedPercentPerTick(FUEL.idleLitresPerSecond());
				if (Math.abs(idleBurn - expectedIdle) > expectedIdle * 0.25f) {
					throw helper.assertionException("an idle pod should burn about %s over %s ticks, burned %s", expectedIdle, ticks, idleBurn);
				}
				if (movingBurn <= idleBurn) {
					throw helper.assertionException("a moving pod should burn more than an idle one, moving %s idle %s", movingBurn, idleBurn);
				}
				helper.succeed();
			} finally {
				pilot.leave();
				movingPod.discard();
				cleanUp(helper, idlePod, null);
			}
		});
	}

	@GameTest
	public void beepThresholdIsTwentyOnePercent(GameTestHelper helper) {
		if (FUEL.lowFuelPercent() != 21f || !PodFuel.isLow(21f) || !PodFuel.isLow(5f) || PodFuel.isLow(21.5f)) {
			throw helper.assertionException("low fuel should be 21%% and below, threshold %s", FUEL.lowFuelPercent());
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 40)
	public void emptyTankStrandsThePod(GameTestHelper helper) {
		PodEntity pod = spawnPod(helper, 0);
		pod.setFuel(0.01f);
		helper.runAfterDelay(10, () -> {
			try {
				if (!pod.stranded() || pod.fuel() != 0f) {
					throw helper.assertionException("an empty pod should be stranded with fuel 0, got stranded %s fuel %s", pod.stranded(), pod.fuel());
				}
				helper.succeed();
			} finally {
				cleanUp(helper, pod, null);
			}
		});
	}

	@GameTest(maxTicks = 60)
	public void strandedPodIgnoresInputAndFalls(GameTestHelper helper) {
		PodEntity pod = spawnPod(helper, 6);
		MockPlayer pilot = seatPilot(helper, pod);
		pod.setFuel(0.01f);
		helper.runAfterDelay(5, () -> {
			if (!pod.stranded()) {
				cleanUp(helper, pod, pilot);
				throw helper.assertionException("the pod should have run dry by now");
			}
			double startY = pod.getY();
			Vec3 start = pod.position();
			pilot.setInput(FORWARD_JUMP);
			helper.runAfterDelay(6, () -> {
				try {
					Vec3 now = pod.position();
					if (now.y >= startY - 0.1 || Math.abs(now.x - start.x) > 1e-6 || Math.abs(now.z - start.z) > 1e-6 || pod.flying()) {
						throw helper.assertionException("a stranded pod should ignore input and fall, from %s to %s", start, now);
					}
					helper.succeed();
				} finally {
					cleanUp(helper, pod, pilot);
				}
			});
		});
	}

	@GameTest
	public void coalRefuelsAndConsumesOne(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		MockPlayer player = MockPlayers.join(helper, "refueler");
		try {
			player.player().setGameMode(GameType.SURVIVAL);
			pod.setFuel(30f);
			player.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL, 3));
			InteractionResult result = useOnPod(player, pod);
			float expected = 30f + FUEL.refuelLitres() / FUEL.tankLitres() * 100f;
			int left = player.player().getItemInHand(InteractionHand.MAIN_HAND).getCount();
			if (!result.consumesAction() || Math.abs(pod.fuel() - expected) > 0.5f || left != 2) {
				throw helper.assertionException("coal should bring fuel to %s and use one item, got result %s fuel %s left %s",
						expected, result, pod.fuel(), left);
			}
			helper.succeed();
		} finally {
			player.leave();
			pod.discard();
		}
	}

	@GameTest
	public void charcoalRescuesAStrandedPod(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		MockPlayer player = MockPlayers.join(helper, "rescuer");
		try {
			pod.setFuel(0f);
			pod.setStranded(true);
			player.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CHARCOAL));
			useOnPod(player, pod);
			if (pod.stranded() || pod.fuel() <= 0f) {
				throw helper.assertionException("charcoal should restore a stranded pod, got stranded %s fuel %s", pod.stranded(), pod.fuel());
			}
			helper.succeed();
		} finally {
			player.leave();
			pod.discard();
		}
	}

	@GameTest
	public void fullTankAndOtherItemsDoNotRefuel(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		MockPlayer player = MockPlayers.join(helper, "tinkerer");
		try {
			player.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL, 2));
			InteractionResult full = useOnPod(player, pod);
			pod.setFuel(10f);
			player.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK, 2));
			InteractionResult stick = useOnPod(player, pod);
			if (full.consumesAction() || stick.consumesAction() || pod.fuel() != 10f
					|| player.player().getItemInHand(InteractionHand.MAIN_HAND).getCount() != 2) {
				throw helper.assertionException("a full tank or a stick must not refuel, got %s and %s, fuel %s", full, stick, pod.fuel());
			}
			helper.succeed();
		} finally {
			player.leave();
			pod.discard();
		}
	}
}
