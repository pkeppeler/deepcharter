package io.github.pkeppeler.deepcharter.test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.layer.BreachEvents;
import io.github.pkeppeler.deepcharter.layer.BreachService;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;

/**
 * Server GameTests for breach crossing for players and vehicles. Each test uses its own X/Z so
 * the columns it carves do not touch another test's.
 */
public class BreachCrossingTest {
	/** Ticks a fall, once the chunk ticks, needs to cross a breach and be checked. */
	private static final int CROSSING_TICKS = 200;
	/** Ticks the no-bounce test needs: the crossing plus 100 ticks of watching. */
	private static final int NO_BOUNCE_TICKS = 300;
	/** Crossings seen per entity UUID. A UUID survives a crossing, so this follows recreated entities. */
	private static final Map<UUID, Integer> CROSSINGS = new ConcurrentHashMap<>();

	static {
		BreachEvents.CROSSED.register((entity, from, to, fromLayer, toLayer) -> CROSSINGS.merge(entity.getUUID(), 1, Integer::sum));
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
	public void fallingPlayerArrivesAtTheSameXZ(GameTestHelper helper) {
		double x = 1000.5;
		double z = 1000.5;
		openShaft(layer(helper, 1), x, z);
		MockPlayer mock = MockPlayers.join(helper, "breach-falls");
		mock.teleportTo(layer(helper, 1), new Vec3(x, 8, z), 0, 0);
		fallWhileInLayerOne(helper, mock);
		helper.succeedWhen(() -> {
			ServerPlayer player = mock.player();
			expectIn(helper, player, 2);
			if (Math.abs(player.getX() - x) > 0.5 || Math.abs(player.getZ() - z) > 0.5) {
				throw failure(helper, "arrived at x=%s z=%s, expected %s %s", player.getX(), player.getZ(), x, z);
			}
			ServerLevel two = layer(helper, 2);
			if (player.getY() > two.getMaxY() || player.getY() < two.getMaxY() - LayerTuning.DEFAULT.pocketHeight() - 1) {
				throw failure(helper, "arrived at y=%s, not just under the ceiling %s", player.getY(), two.getMaxY());
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
	public void crossingCarvesAPocketUnderTheCeiling(GameTestHelper helper) {
		double x = 1100.5;
		double z = 1100.5;
		ServerLevel two = layer(helper, 2);
		openShaft(layer(helper, 1), x, z);
		// Fill the whole top of layer 2 so only a carved pocket can hold the arriving player.
		BlockPos column = BlockPos.containing(x, 0, z);
		int radius = LayerTuning.DEFAULT.pocketRadius() + 1;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int y = two.getMaxY() - 12; y <= two.getMaxY(); y++) {
					two.setBlock(column.offset(dx, 0, dz).atY(y), Blocks.STONE.defaultBlockState(), 3);
				}
			}
		}
		MockPlayer mock = MockPlayers.join(helper, "breach-pocket");
		mock.teleportTo(layer(helper, 1), new Vec3(x, 8, z), 0, 0);
		fallWhileInLayerOne(helper, mock);
		helper.succeedWhen(() -> {
			expectIn(helper, mock.player(), 2);
			BlockPos feet = mock.player().blockPosition();
			for (int dy = 0; dy < LayerTuning.DEFAULT.pocketHeight(); dy++) {
				if (!two.getBlockState(feet.above(dy)).isAir()) {
					throw failure(helper, "pocket is not clear at %s", feet.above(dy));
				}
			}
			if (!two.getBlockState(feet.atY(two.getMaxY())).is(Blocks.STONE)) {
				throw failure(helper, "the ceiling itself was carved");
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
	public void playerInAMinecartCrossesStillRiding(GameTestHelper helper) {
		double x = 1200.5;
		double z = 1200.5;
		ServerLevel one = layer(helper, 1);
		openShaft(one, x, z);
		MockPlayer mock = MockPlayers.join(helper, "breach-rides");
		mock.teleportTo(one, new Vec3(x, 8, z), 0, 0);
		FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(x, 8, z), () -> {
			Minecart cart = EntityTypes.MINECART.create(one, EntitySpawnReason.COMMAND);
			cart.setPos(x, 8, z);
			one.addFreshEntity(cart);
			if (!mock.player().startRiding(cart, true, false)) {
				throw failure(helper, "the mock could not board the minecart");
			}
		});
		helper.succeedWhen(() -> {
			ServerPlayer player = mock.player();
			expectIn(helper, player, 2);
			Entity vehicle = player.getVehicle();
			if (!(vehicle instanceof Minecart)) {
				throw failure(helper, "the player is riding %s after crossing, not a minecart", vehicle);
			}
			if (vehicle.level() != player.level()) {
				throw failure(helper, "the minecart is in %s, the player in %s", vehicle.level().dimension(), player.level().dimension());
			}
			if (CROSSINGS.getOrDefault(player.getUUID(), 0) != 1) {
				throw failure(helper, "expected one crossing event for the rider, saw %s", CROSSINGS.get(player.getUUID()));
			}
		});
	}

	@GameTest(maxTicks = 200)
	public void risingAboveTheTopGoesBackUp(GameTestHelper helper) {
		double x = 1300.5;
		double z = 1300.5;
		ServerLevel two = layer(helper, 2);
		MockPlayer mock = MockPlayers.join(helper, "breach-rises");
		mock.teleportTo(two, new Vec3(x, two.getMaxY() + 2, z), 0, 0);
		helper.succeedWhen(() -> {
			ServerPlayer player = mock.player();
			expectIn(helper, player, 1);
			ServerLevel one = layer(helper, 1);
			if (Math.abs(player.getX() - x) > 0.5 || Math.abs(player.getZ() - z) > 0.5) {
				throw failure(helper, "arrived at x=%s z=%s, expected %s %s", player.getX(), player.getZ(), x, z);
			}
			int floor = one.getMinY() + LayerTuning.DEFAULT.crustThickness();
			if (player.getY() < floor || player.getY() > floor + LayerTuning.DEFAULT.pocketHeight()) {
				throw failure(helper, "arrived at y=%s, not just above the crust (floor %s)", player.getY(), floor);
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + NO_BOUNCE_TICKS)
	public void noBouncingOverOneHundredTicks(GameTestHelper helper) {
		double x = 1400.5;
		double z = 1400.5;
		openShaft(layer(helper, 1), x, z);
		MockPlayer mock = MockPlayers.join(helper, "breach-no-bounce");
		mock.teleportTo(layer(helper, 1), new Vec3(x, 8, z), 0, 0);
		fallWhileInLayerOne(helper, mock);
		long[] arrivedAt = {-1};
		helper.onEachTick(() -> {
			boolean inTwo = mock.player().level().dimension().equals(LayerChain.dimension(2));
			if (inTwo && arrivedAt[0] < 0) {
				arrivedAt[0] = helper.getTick();
				ServerLevel two = layer(helper, 2);
				double y = mock.player().getY();
				// Strictly inside both crossing lines (y < minY goes down, y > maxY goes up), with margin.
				if (y - two.getMinY() < 1 || two.getMaxY() - y < 1) {
					throw failure(helper, "arrival y=%s is not at least 1 block inside layer_2 [%s, %s]", y, two.getMinY(), two.getMaxY());
				}
			}
			if (arrivedAt[0] >= 0) {
				if (!inTwo) {
					throw failure(helper, "the player left layer_2 %d ticks after arriving", helper.getTick() - arrivedAt[0]);
				}
				if (helper.getTick() - arrivedAt[0] >= 100) {
					if (CROSSINGS.getOrDefault(mock.player().getUUID(), 0) != 1) {
						throw failure(helper, "expected exactly one crossing, saw %s", CROSSINGS.get(mock.player().getUUID()));
					}
					helper.succeed();
				}
			}
		});
	}

	@GameTest(maxTicks = 40)
	public void theLastLayersFloorHasNoCrossing(GameTestHelper helper) {
		ServerLevel two = layer(helper, 2);
		MockPlayer mock = MockPlayers.join(helper, "breach-last-floor");
		mock.teleportTo(two, new Vec3(1500.5, two.getMinY() - 2, 1500.5), 0, 0);
		helper.runAfterDelay(20, () -> {
			if (CROSSINGS.containsKey(mock.player().getUUID())) {
				throw failure(helper, "the last layer's floor crossed");
			}
			expectIn(helper, mock.player(), 2);
			helper.succeed();
		});
	}

	@GameTest
	public void handsCannotBreakTheCrustButADrillCan(GameTestHelper helper) {
		ServerLevel one = layer(helper, 1);
		BlockPos pos = new BlockPos(1600, one.getMinY() + 1, 1600);
		MockPlayer mock = MockPlayers.join(helper, "breach-hands");
		mock.teleportTo(one, Vec3.atBottomCenterOf(pos.above(4)), 0, 0);
		mock.player().setGameMode(GameType.SURVIVAL);
		one.setBlock(pos, LayerBlocks.BREACH_CRUST.defaultBlockState(), 3);
		if (mock.player().gameMode.destroyBlock(pos)) {
			throw failure(helper, "a player broke the crust by hand");
		}
		if (!one.getBlockState(pos).is(LayerBlocks.BREACH_CRUST)) {
			throw failure(helper, "the crust is gone after a refused hand-break");
		}
		// Other blocks are unaffected.
		BlockPos stone = pos.east();
		one.setBlock(stone, Blocks.DIRT.defaultBlockState(), 3);
		if (!mock.player().gameMode.destroyBlock(stone)) {
			throw failure(helper, "the hand-break guard stopped an ordinary block");
		}
		mock.player().setGameMode(GameType.ADVENTURE);
		if (mock.player().gameMode.destroyBlock(pos) || !one.getBlockState(pos).is(LayerBlocks.BREACH_CRUST)) {
			throw failure(helper, "an adventure player broke the crust by hand");
		}
		mock.player().setGameMode(GameType.CREATIVE);
		if (!mock.player().gameMode.destroyBlock(pos) || !one.getBlockState(pos).isAir()) {
			throw failure(helper, "a creative player could not break the crust");
		}
		one.setBlock(pos, LayerBlocks.BREACH_CRUST.defaultBlockState(), 3);
		if (!BreachService.breakCrust(one, pos)) {
			throw failure(helper, "the drill hook refused to break the crust");
		}
		if (!one.getBlockState(pos).isAir()) {
			throw failure(helper, "the crust is still there after the drill hook");
		}
		helper.succeed();
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
	public void arrivalPocketHasASolidFloor(GameTestHelper helper) {
		double x = 1700.5;
		double z = 1700.5;
		ServerLevel two = layer(helper, 2);
		openShaft(layer(helper, 1), x, z);
		MockPlayer mock = MockPlayers.join(helper, "breach-floor");
		mock.teleportTo(layer(helper, 1), new Vec3(x, 8, z), 0, 0);
		fallWhileInLayerOne(helper, mock);
		helper.succeedWhen(() -> {
			expectIn(helper, mock.player(), 2);
			BlockPos floor = mock.player().blockPosition().below();
			if (two.getBlockState(floor).isAir()) {
				throw failure(helper, "no floor under the arrival at %s", floor);
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
	public void crossingKeepsBlockEntities(GameTestHelper helper) {
		double x = 1800.5;
		double z = 1800.5;
		ServerLevel two = layer(helper, 2);
		BlockPos chest = BlockPos.containing(x, two.getMaxY() - LayerTuning.DEFAULT.pocketHeight() + 1, z);
		two.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
		openShaft(layer(helper, 1), x, z);
		MockPlayer mock = MockPlayers.join(helper, "breach-chest");
		mock.teleportTo(layer(helper, 1), new Vec3(x, 8, z), 0, 0);
		fallWhileInLayerOne(helper, mock);
		helper.succeedWhen(() -> {
			expectIn(helper, mock.player(), 2);
			if (!two.getBlockState(chest).is(Blocks.CHEST) || two.getBlockEntity(chest) == null) {
				throw failure(helper, "the crossing deleted the chest at %s", chest);
			}
		});
	}

	/** A mock has no client gravity, so drop it a block a tick while in layer_1, once its chunk ticks. */
	private static void fallWhileInLayerOne(GameTestHelper helper, MockPlayer mock) {
		boolean[] ticking = {false};
		FarChunks.awaitEntityTicking(helper, layer(helper, 1), mock.player().blockPosition(), () -> ticking[0] = true);
		helper.onEachTick(() -> {
			ServerPlayer player = mock.player();
			if (ticking[0] && player.level().dimension().equals(LayerChain.dimension(1))) {
				player.setPos(player.getX(), player.getY() - 1, player.getZ());
			}
		});
	}

	/** Clear the floor of {@code level} at one column, so an entity above it falls straight out of the layer. */
	private static void openShaft(ServerLevel level, double x, double z) {
		BlockPos column = BlockPos.containing(x, 0, z);
		RoomCarver.carve(level, column.atY(level.getMinY()), column.atY(level.getMinY() + 10), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
	}

	// assertionException(String, Object...) leaves the placeholders unfilled in the report.
	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	private static void expectIn(GameTestHelper helper, Entity entity, int layer) {
		if (!entity.level().dimension().equals(LayerChain.dimension(layer))) {
			throw failure(helper, "%s is in %s, expected layer_%d", entity.getName().getString(), entity.level().dimension(), layer);
		}
	}

	private static ServerLevel layer(GameTestHelper helper, int layer) {
		ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(layer));
		if (level == null) {
			throw failure(helper, "dimension %s did not load", LayerChain.dimension(layer));
		}
		return level;
	}
}
