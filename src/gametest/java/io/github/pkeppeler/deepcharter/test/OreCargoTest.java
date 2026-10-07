package io.github.pkeppeler.deepcharter.test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.OreCargoMenu;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreTuning;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodCargo;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/**
 * Server GameTests for ore items and the pod's item cargo. Timing follows PodDrillTest: wait on conditions with a
 * generous budget, because far layer chunks tick late in a fresh world.
 */
public class OreCargoTest {
	private static final int MAX_TICKS = 20000;
	private static final int Z = 3200;
	private static final Input SPRINT = new Input(false, false, false, false, false, false, true);
	private static final Input JUMP = new Input(false, false, false, false, true, false, false);
	private static final Input SNEAK = new Input(false, false, false, false, false, true, false);
	private static final double EPSILON = 1e-6;
	private static final float ENGINE_POWER = PodTuning.DEFAULT.movement().enginePower();

	@GameTest
	public void everyOreItemStacksToOne(GameTestHelper helper) {
		for (OreType type : OreType.values()) {
			ItemStack stack = OreRegistry.stack(type);
			if (stack.getMaxStackSize() != 1) {
				throw failure(helper, "%s stacks to %d, expected 1", type, stack.getMaxStackSize());
			}
		}
		helper.succeed();
	}

	@GameTest
	public void theOreTableUsesTheOriginalsNumbers(GameTestHelper helper) {
		OreType[] originals = {OreType.IRONIUM, OreType.BRONZIUM, OreType.SILVERIUM, OreType.GOLDIUM, OreType.PLATINIUM, OreType.EINSTEINIUM};
		int[] values = {30, 60, 100, 250, 750, 2000};
		int[] masses = {1, 1, 1, 2, 3, 4};
		for (int i = 0; i < originals.length; i++) {
			OreType type = originals[i];
			if (type.value() != values[i] || type.referenceMass() != masses[i]) {
				throw failure(helper, "%s is $%d mass %d, the original says $%d mass %d", type, type.value(), type.referenceMass(), values[i], masses[i]);
			}
			float expected = masses[i] * OreTuning.DEFAULT.massScale();
			if (type.mass() != expected) {
				throw failure(helper, "%s weighs %s in pod units, expected %s", type, type.mass(), expected);
			}
		}
		if (OreType.values().length != 7 || OreType.CICATRIUM.value() <= 0 || OreType.CICATRIUM.referenceMass() <= 0) {
			throw failure(helper, "the table is the six originals plus Cicatrium with a value and a mass, got %s", List.of(OreType.values()));
		}
		helper.succeed();
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void carriedOreSlowsAPlayerOnFoot(GameTestHelper helper) {
		MockPlayer walker = MockPlayers.join(helper, "ore-walker");
		ServerPlayer player = walker.player();
		double bare = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
		player.getInventory().add(OreRegistry.stack(OreType.EINSTEINIUM));
		double expected = bare * (1 - OreType.EINSTEINIUM.mass() * OreTuning.DEFAULT.slowdownPerMass());
		boolean[] loaded = {false};
		helper.onEachTick(() -> {
			double speed = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
			if (!loaded[0]) {
				if (speed >= bare) {
					return;
				}
				if (Math.abs(speed - expected) > EPSILON) {
					walker.leave();
					throw failure(helper, "carrying Einsteinium should give speed %s, got %s (bare %s)", expected, speed, bare);
				}
				loaded[0] = true;
				player.getInventory().clearContent();
				return;
			}
			if (Math.abs(speed - bare) < EPSILON) {
				walker.leave();
				helper.succeed();
			}
		});
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void drilledOreReachesTheCargoAndVanillaOreDoesNot(GameTestHelper helper) {
		int x = 3200;
		int floor = 60;
		ServerLevel level = layer(helper, 1);
		room(level, x, floor);
		level.setBlock(new BlockPos(x - 1, floor - 1, Z - 1), OreRegistry.block(OreType.EINSTEINIUM).defaultBlockState(), 3);
		level.setBlock(new BlockPos(x, floor - 1, Z), Blocks.DIAMOND_ORE.defaultBlockState(), 3);
		level.setBlock(new BlockPos(x, floor - 1, Z - 1), Blocks.IRON_ORE.defaultBlockState(), 3);
		MockPlayer pilot = MockPlayers.join(helper, "ore-drill");
		PodEntity pod = pod(level, pilot, new Vec3(x, floor, Z));
		pilot.setInput(SPRINT);
		helper.onEachTick(() -> {
			if (count(level, x - 1, x, floor - 1, floor - 1, Z - 1, Z, Blocks.AIR) != 4) {
				return;
			}
			pilot.releaseInput();
			List<ItemStack> kept = pod.cargo().entries().stream().map(PodCargo.Entry::stack).toList();
			if (kept.size() != 1 || !kept.getFirst().is(OreRegistry.item(OreType.EINSTEINIUM))) {
				throw failure(helper, "cargo should hold only the Einsteinium item, it holds %s", kept);
			}
			if (pod.cargoUsed() != 1 || pod.cargoMass() != OreType.EINSTEINIUM.mass()) {
				throw failure(helper, "the synced cargo should be 1 ore of mass %s, it is %d of %s", OreType.EINSTEINIUM.mass(), pod.cargoUsed(), pod.cargoMass());
			}
			pod.discard();
			pilot.leave();
			helper.succeed();
		});
	}

	/** Four drilled Einsteinium weigh 80 against an engine of 100, so the pod still climbs; three more make 140 and it cannot. */
	@GameTest(maxTicks = MAX_TICKS)
	public void drilledOreMassCutsLift(GameTestHelper helper) {
		int x = 3264;
		int floor = 60;
		ServerLevel level = layer(helper, 1);
		room(level, x, floor);
		for (int i = 0; i < 4; i++) {
			level.setBlock(new BlockPos(x - 1 + i % 2, floor - 1, Z - 1 + i / 2), OreRegistry.block(OreType.EINSTEINIUM).defaultBlockState(), 3);
		}
		MockPlayer pilot = MockPlayers.join(helper, "ore-lift");
		PodEntity pod = pod(level, pilot, new Vec3(x, floor, Z));
		pilot.setInput(SPRINT);
		boolean[] drilled = {false};
		boolean[] loaded = {false};
		double[] startY = {0};
		int[] jumpedAt = {0};
		helper.onEachTick(() -> {
			if (!drilled[0]) {
				if (count(level, x - 1, x, floor - 1, floor - 1, Z - 1, Z, Blocks.AIR) != 4) {
					return;
				}
				pilot.releaseInput();
				drilled[0] = true;
				float drilledMass = 4 * OreType.EINSTEINIUM.mass();
				if (pod.cargoMass() != drilledMass) {
					throw failure(helper, "four drilled Einsteinium should weigh %s, the cargo weighs %s", drilledMass, pod.cargoMass());
				}
				return;
			}
			if (!pod.onGround()) {
				return;
			}
			if (!loaded[0]) {
				fillBay(pod, OreType.EINSTEINIUM, 3);
				loaded[0] = true;
				if (pod.cargoMass() < ENGINE_POWER) {
					throw failure(helper, "the full bay should outweigh the engine, it weighs %s", pod.cargoMass());
				}
				startY[0] = pod.getY();
				pilot.setInput(JUMP);
				jumpedAt[0] = pod.tickCount;
				return;
			}
			if (pod.tickCount - jumpedAt[0] < 20) {
				return;
			}
			if (pod.getY() - startY[0] > 0.01 || pod.flying()) {
				throw failure(helper, "a pod too heavy to lift rose %s", pod.getY() - startY[0]);
			}
			pod.discard();
			pilot.leave();
			helper.succeed();
		});
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void lightCargoStillLiftsThePod(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		MockPlayer pilot = MockPlayers.join(helper, "ore-light");
		pilot.teleportTo(helper.getLevel(), pod.position(), 0f, 0f);
		pilot.player().startRiding(pod);
		pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
		pilot.setInput(JUMP);
		double startY = pod.getY();
		helper.onEachTick(() -> {
			if (pod.getY() - startY > 0.5) {
				pod.discard();
				pilot.leave();
				helper.succeed();
			}
		});
	}

	@GameTest
	public void vanillaOreIsNeverCargo(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		try {
			for (ItemStack vanilla : List.of(new ItemStack(Items.IRON_ORE), new ItemStack(Items.RAW_IRON), new ItemStack(Items.DIAMOND))) {
				boolean refused = false;
				try {
					pod.cargo().tryAdd(pod, vanilla);
				} catch (IllegalArgumentException expected) {
					refused = true;
				}
				if (!refused) {
					throw failure(helper, "%s must not be accepted as cargo", vanilla);
				}
			}
			if (pod.cargoUsed() != 0) {
				throw failure(helper, "refused stacks must change nothing, %d in the bay", pod.cargoUsed());
			}
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void cargoStacksSurviveSaveAndLoad(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
		pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.CICATRIUM));
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		pod.saveWithoutId(output);
		pod.discard();
		PodEntity copy = (PodEntity) EntityType.create(PodRegistry.POD,
				TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult()),
				level, EntitySpawnReason.LOAD).orElseThrow(() -> failure(helper, "the saved pod did not load"));
		try {
			List<ItemStack> kept = copy.cargo().entries().stream().map(PodCargo.Entry::stack).toList();
			float mass = OreType.GOLDIUM.mass() + OreType.CICATRIUM.mass();
			if (kept.size() != 2 || !kept.get(0).is(OreRegistry.item(OreType.GOLDIUM)) || !kept.get(1).is(OreRegistry.item(OreType.CICATRIUM))
					|| copy.cargoUsed() != 2 || copy.cargoMass() != mass) {
				throw failure(helper, "cargo did not survive: %s, %d used, mass %s (expected %s)", kept, copy.cargoUsed(), copy.cargoMass(), mass);
			}
			helper.succeed();
		} finally {
			copy.discard();
		}
	}

	@GameTest
	public void sneakUseOnThePodOpensTheCargoScreen(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		MockPlayer player = MockPlayers.join(helper, "ore-sneaker");
		try {
			pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.SILVERIUM));
			pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.PLATINIUM));
			player.teleportTo(helper.getLevel(), pod.position().add(2, 0, 0), 0f, 0f);
			player.setInput(SNEAK);
			InteractionResult result = UseEntityCallback.EVENT.invoker().interact(player.player(), pod.level(), InteractionHand.MAIN_HAND, pod, null);
			if (!result.consumesAction() || !(player.player().containerMenu instanceof OreCargoMenu menu)) {
				throw failure(helper, "sneak-use should open the cargo menu, got %s with %s", result, player.player().containerMenu);
			}
			List<ItemStack> shown = menu.shownOre();
			if (shown.size() != 2 || !shown.get(0).is(OreRegistry.item(OreType.SILVERIUM)) || !shown.get(1).is(OreRegistry.item(OreType.PLATINIUM))) {
				throw failure(helper, "the menu should show the Silverium and the Platinium, it shows %s", shown);
			}
			if (pod.getPassengers().contains(player.player())) {
				throw failure(helper, "sneak-use must not board the pod");
			}
			helper.succeed();
		} finally {
			player.leave();
			pod.discard();
		}
	}

	@GameTest
	public void cargoSavedBeforeOreItemsIsDroppedWithAnErrorLog(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		PodEntity copy = helper.spawn(PodRegistry.POD, 4, 2, 2);
		CapturingAppender log = CapturingAppender.attach();
		try {
			CompoundTag tag = savedPod(level, pod);
			// The pre-#56 format: no version, and a vanilla ore block in each entry.
			ListTag old = new ListTag();
			for (int i = 0; i < 2; i++) {
				CompoundTag entry = new CompoundTag();
				entry.putString("ore", "minecraft:iron_ore");
				entry.putFloat("mass", 1f);
				old.add(entry);
			}
			tag.put("cargo", old);
			tag.remove("cargo_version");
			copy.cargo().load(inputOf(level, tag), copy);
			if (copy.cargoUsed() != 0 || !copy.cargo().entries().isEmpty() || copy.cargoMass() != 0f) {
				throw failure(helper, "the old vanilla ore must be dropped, the bay holds %s", copy.cargo().entries());
			}
			String expected = "Pod " + copy.getUUID() + " was saved before ore items: dropped 2 cargo entries, which were vanilla ores";
			if (!log.errors().contains(expected)) {
				throw failure(helper, "the drop must be logged at ERROR as \"%s\", logged %s", expected, log.errors());
			}
			// Once saved again it is the current format, and reads back as the empty bay it is.
			CompoundTag resaved = savedPod(level, copy);
			if (!resaved.contains("cargo_version") || !resaved.getListOrEmpty("cargo").isEmpty()) {
				throw failure(helper, "the migrated cargo should save as an empty list with a version, saved %s", resaved);
			}
			helper.succeed();
		} finally {
			log.detach();
			pod.discard();
			copy.discard();
		}
	}

	@GameTest
	public void cargoOfAFutureVersionIsKeptAndRefusesChanges(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		PodEntity copy = helper.spawn(PodRegistry.POD, 4, 2, 2);
		try {
			pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
			CompoundTag tag = savedPod(level, pod);
			tag.putInt("cargo_version", 99);
			ListTag future = tag.getListOrEmpty("cargo");
			CompoundTag extra = new CompoundTag();
			extra.putString("shape_from_the_future", "kept as it is");
			future.add(extra);
			copy.cargo().load(inputOf(level, tag), copy);
			CompoundTag resaved = savedPod(level, copy);
			if (!resaved.get("cargo").equals(tag.get("cargo")) || resaved.getIntOr("cargo_version", -1) != 99) {
				throw failure(helper, "a cargo of version 99 must be saved back unchanged, got %s and version %s", resaved.get("cargo"), resaved.get("cargo_version"));
			}
			expectNamesPod(helper, copy, () -> copy.cargo().tryAdd(copy, OreRegistry.stack(OreType.IRONIUM)));
			expectNamesPod(helper, copy, () -> copy.cargo().dump(copy));
			helper.succeed();
		} finally {
			pod.discard();
			copy.discard();
		}
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void drillingOreIntoUnreadableCargoLosesTheOreLogsOnceAndKeepsTheData(GameTestHelper helper) {
		int x = 3328;
		int floor = 60;
		ServerLevel level = layer(helper, 1);
		room(level, x, floor);
		level.setBlock(new BlockPos(x - 1, floor - 1, Z - 1), OreRegistry.block(OreType.IRONIUM).defaultBlockState(), 3);
		level.setBlock(new BlockPos(x, floor - 1, Z), OreRegistry.block(OreType.GOLDIUM).defaultBlockState(), 3);
		MockPlayer pilot = MockPlayers.join(helper, "ore-unreadable");
		PodEntity pod = pod(level, pilot, new Vec3(x, floor, Z));
		pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.SILVERIUM));
		CompoundTag tag = savedPod(level, pod);
		tag.putInt("cargo_version", 99);
		pod.cargo().load(inputOf(level, tag), pod);
		CapturingAppender log = CapturingAppender.attach();
		pilot.setInput(SPRINT);
		helper.onEachTick(() -> {
			if (count(level, x - 1, x, floor - 1, floor - 1, Z - 1, Z, Blocks.AIR) != 4) {
				return;
			}
			pilot.releaseInput();
			try {
				String once = "Pod " + pod.getUUID() + ": cargo unreadable, drilled ore discarded";
				long logged = log.errors().stream().filter(once::equals).count();
				if (logged != 1) {
					throw failure(helper, "two drilled ores must log \"%s\" exactly once, logged %d times in %s", once, logged, log.errors());
				}
				if (!savedPod(level, pod).get("cargo").equals(tag.get("cargo")) || pod.cargoUsed() != 0) {
					throw failure(helper, "the unreadable cargo must be saved back unchanged and nothing added, got %s", savedPod(level, pod).get("cargo"));
				}
				helper.succeed();
			} finally {
				log.detach();
				pod.discard();
				pilot.leave();
			}
		});
	}

	@GameTest
	public void aNonOreStackInTheSaveKeepsTheCargoAndDoesNotCrashTheLoad(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
		CompoundTag tag = savedPod(level, pod);
		pod.discard();
		CompoundTag vanilla = new CompoundTag();
		CompoundTag stack = new CompoundTag();
		stack.putString("id", "minecraft:iron_ore");
		stack.putInt("count", 1);
		vanilla.put("stack", stack);
		vanilla.putFloat("mass", 1f);
		tag.getListOrEmpty("cargo").add(vanilla);
		PodEntity loaded = (PodEntity) EntityType.create(PodRegistry.POD, inputOf(level, tag), level, EntitySpawnReason.LOAD)
				.orElseThrow(() -> failure(helper, "the pod with a vanilla stack in its cargo did not load"));
		try {
			CompoundTag resaved = savedPod(level, loaded);
			if (!resaved.get("cargo").equals(tag.get("cargo"))) {
				throw failure(helper, "the cargo with an unreadable entry must be saved back unchanged, got %s", resaved.get("cargo"));
			}
			expectNamesPod(helper, loaded, () -> loaded.cargo().tryAdd(loaded, OreRegistry.stack(OreType.IRONIUM)));
			helper.succeed();
		} finally {
			loaded.discard();
		}
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void oreInABundleSlowsAPlayer(GameTestHelper helper) {
		ItemStack bundle = new ItemStack(Items.BUNDLE);
		bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of()).copyWithContents(java.util.stream.Stream.of(OreRegistry.stack(OreType.EINSTEINIUM))));
		expectSpeedFraction(helper, "bundle-walker", player -> player.getInventory().add(bundle), 1 - OreType.EINSTEINIUM.mass() * OreTuning.DEFAULT.slowdownPerMass());
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void oreInAShulkerBoxSlowsAPlayer(GameTestHelper helper) {
		ItemStack box = new ItemStack(Items.SHULKER_BOX);
		box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(OreRegistry.stack(OreType.GOLDIUM), OreRegistry.stack(OreType.GOLDIUM))));
		expectSpeedFraction(helper, "shulker-walker", player -> player.getInventory().add(box), 1 - 2 * OreType.GOLDIUM.mass() * OreTuning.DEFAULT.slowdownPerMass());
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void oreInTheOffhandSlowsAPlayer(GameTestHelper helper) {
		expectSpeedFraction(helper, "offhand-walker", player -> player.setItemInHand(InteractionHand.OFF_HAND, OreRegistry.stack(OreType.PLATINIUM)),
				1 - OreType.PLATINIUM.mass() * OreTuning.DEFAULT.slowdownPerMass());
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void theSlowdownStopsAtTheCap(GameTestHelper helper) {
		expectSpeedFraction(helper, "cap-walker", player -> {
			for (int i = 0; i < 6; i++) {
				player.getInventory().add(OreRegistry.stack(OreType.EINSTEINIUM));
			}
		}, 1 - OreTuning.DEFAULT.maxSlowdown());
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void theSlowdownIsSetOnceNotEveryTick(GameTestHelper helper) {
		MockPlayer walker = MockPlayers.join(helper, "once-walker");
		ServerPlayer player = walker.player();
		player.getInventory().add(OreRegistry.stack(OreType.EINSTEINIUM));
		AttributeModifier[] first = {null};
		int[] ticks = {0};
		helper.onEachTick(() -> {
			AttributeModifier now = player.getAttribute(Attributes.MOVEMENT_SPEED).getModifiers().stream()
					.filter(modifier -> modifier.id().getPath().equals("ore_load")).findFirst().orElse(null);
			if (now == null) {
				return;
			}
			if (first[0] == null) {
				first[0] = now;
				return;
			}
			if (now != first[0]) {
				walker.leave();
				throw failure(helper, "the modifier was replaced while the load did not change: every replacement sends a packet");
			}
			if (++ticks[0] >= 20) {
				walker.leave();
				helper.succeed();
			}
		});
	}

	@GameTest
	public void clickingTheCargoScreenChangesNothing(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		MockPlayer player = MockPlayers.join(helper, "ore-clicker");
		try {
			pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.SILVERIUM));
			player.teleportTo(helper.getLevel(), pod.position().add(2, 0, 0), 0f, 0f);
			player.setInput(SNEAK);
			UseEntityCallback.EVENT.invoker().interact(player.player(), pod.level(), InteractionHand.MAIN_HAND, pod, null);
			OreCargoMenu menu = (OreCargoMenu) player.player().containerMenu;
			menu.clicked(0, 0, ContainerInput.PICKUP, player.player());
			if (!menu.getCarried().isEmpty()) {
				throw failure(helper, "picking up from a read-only slot must give nothing, got %s", menu.getCarried());
			}
			menu.setCarried(new ItemStack(Items.DIAMOND));
			menu.clicked(3, 0, ContainerInput.PICKUP, player.player());
			menu.clicked(0, 0, ContainerInput.QUICK_MOVE, player.player());
			if (!menu.getCarried().is(Items.DIAMOND) || pod.cargo().entries().size() != 1 || menu.shownOre().size() != 1) {
				throw failure(helper, "clicks must not move ore, carried %s, bay %s", menu.getCarried(), pod.cargo().entries());
			}
			helper.succeed();
		} finally {
			player.leave();
			pod.discard();
		}
	}

	private static void expectNamesPod(GameTestHelper helper, PodEntity pod, Runnable action) {
		try {
			action.run();
		} catch (IllegalStateException expected) {
			if (!expected.getMessage().contains(pod.getUUID().toString())) {
				throw failure(helper, "the error must name the pod %s, it says: %s", pod.getUUID(), expected.getMessage());
			}
			return;
		}
		throw failure(helper, "changing an unreadable cargo must throw");
	}

	/** Gives a joined player something to carry, then waits for the walking speed to become the bare speed times {@code fraction}. */
	private static void expectSpeedFraction(GameTestHelper helper, String name, java.util.function.Consumer<ServerPlayer> carry, double fraction) {
		MockPlayer walker = MockPlayers.join(helper, name);
		ServerPlayer player = walker.player();
		double bare = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
		carry.accept(player);
		helper.onEachTick(() -> {
			if (Math.abs(player.getAttributeValue(Attributes.MOVEMENT_SPEED) - bare * fraction) < EPSILON) {
				walker.leave();
				helper.succeed();
			}
		});
	}

	private static CompoundTag savedPod(ServerLevel level, PodEntity pod) {
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		pod.saveWithoutId(output);
		return output.buildResult();
	}

	private static ValueInput inputOf(ServerLevel level, CompoundTag tag) {
		return TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag);
	}

	/** Collects the ERROR lines of the mod's logger while attached. */
	private static final class CapturingAppender extends AbstractAppender {
		private final List<String> errors = new CopyOnWriteArrayList<>();

		private CapturingAppender() {
			super("ore-cargo-test", null, null, true, Property.EMPTY_ARRAY);
		}

		static CapturingAppender attach() {
			CapturingAppender appender = new CapturingAppender();
			appender.start();
			((org.apache.logging.log4j.core.Logger) LogManager.getLogger(DeepCharter.MOD_ID)).addAppender(appender);
			return appender;
		}

		void detach() {
			((org.apache.logging.log4j.core.Logger) LogManager.getLogger(DeepCharter.MOD_ID)).removeAppender(this);
			stop();
		}

		List<String> errors() {
			return errors;
		}

		@Override
		public void append(LogEvent event) {
			if (event.getLevel() == Level.ERROR) {
				errors.add(event.getMessage().getFormattedMessage());
			}
		}
	}

	private static void fillBay(PodEntity pod, OreType type, int count) {
		for (int i = 0; i < count; i++) {
			pod.cargo().tryAdd(pod, OreRegistry.stack(type));
		}
	}

	private static PodEntity pod(ServerLevel level, MockPlayer pilot, Vec3 at) {
		pilot.teleportTo(level, at, 0f, 0f);
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(at);
		level.addFreshEntity(pod);
		if (!pilot.player().startRiding(pod)) {
			throw new AssertionError("the pilot could not mount the pod");
		}
		return pod;
	}

	/** Stone bed under open air, the same shape as PodDrillTest's room. */
	private static void room(ServerLevel level, int x, int floor) {
		box(level, x - 4, x + 5, floor - 8, floor - 1, Z - 4, Z + 4, Blocks.STONE);
		box(level, x - 4, x + 5, floor, floor + 10, Z - 4, Z + 4, Blocks.AIR);
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

	private static int count(ServerLevel level, int x1, int x2, int y1, int y2, int z1, int z2, Block block) {
		int found = 0;
		for (int x = x1; x <= x2; x++) {
			for (int y = y1; y <= y2; y++) {
				for (int z = z1; z <= z2; z++) {
					if (level.getBlockState(new BlockPos(x, y, z)).is(block)) {
						found++;
					}
				}
			}
		}
		return found;
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
