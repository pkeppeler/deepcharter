package io.github.pkeppeler.deepcharter.test;

import java.util.List;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

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
