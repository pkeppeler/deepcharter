package io.github.pkeppeler.deepcharter.test;

import java.util.List;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/**
 * Server GameTests for the surface: the breach between the overworld (layer 0) and layer 1, the open
 * overworld floor, the structures that are gone, and portals that cannot be lit. Each crossing test uses
 * its own X/Z so the columns it carves do not touch another test's.
 */
public class SurfaceBreachTest {
	/** Ticks a fall, once the chunk ticks, needs to cross a breach and be checked. */
	private static final int CROSSING_TICKS = 200;

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
	public void fallingOutOfTheOverworldFloorArrivesInLayerOne(GameTestHelper helper) {
		double x = 2500.5;
		double z = 2500.5;
		ServerLevel surface = surface(helper);
		openShaft(surface, x, z);
		keepLoaded(surface, x, z);
		MockPlayer mock = MockPlayers.join(helper, "surface-falls");
		mock.teleportTo(surface, new Vec3(x, surface.getMinY() + 6, z), 0, 0);
		fallWhileIn(helper, mock, surface);
		helper.succeedWhen(() -> {
			ServerPlayer player = mock.player();
			expectIn(helper, player, LayerChain.dimension(1));
			if (Math.abs(player.getX() - x) > 0.5 || Math.abs(player.getZ() - z) > 0.5) {
				throw failure(helper, "arrived at x=%s z=%s, expected %s %s", player.getX(), player.getZ(), x, z);
			}
			ServerLevel one = layer(helper, 1);
			if (player.getY() > one.getMaxY() || player.getY() < one.getMaxY() - LayerTuning.DEFAULT.pocketHeight() - 1) {
				throw failure(helper, "arrived at y=%s, not just under the top %s of layer 1", player.getY(), one.getMaxY());
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
	public void aMinecartRidesDownFromTheOverworldStillRiding(GameTestHelper helper) {
		double x = 2600.5;
		double z = 2600.5;
		ServerLevel surface = surface(helper);
		openShaft(surface, x, z);
		keepLoaded(surface, x, z);
		MockPlayer mock = MockPlayers.join(helper, "surface-rides");
		double y = surface.getMinY() + 6;
		mock.teleportTo(surface, new Vec3(x, y, z), 0, 0);
		boolean[] ticking = {false};
		FarChunks.awaitEntityTicking(helper, surface, BlockPos.containing(x, y, z), () -> {
			Minecart cart = EntityTypes.MINECART.create(surface, EntitySpawnReason.COMMAND);
			cart.setPos(x, y, z);
			surface.addFreshEntity(cart);
			if (!mock.player().startRiding(cart, true, false)) {
				throw failure(helper, "the mock could not board the minecart");
			}
			ticking[0] = true;
		});
		helper.onEachTick(() -> {
			ServerPlayer player = mock.player();
			if (ticking[0] && player.level().dimension().equals(Level.OVERWORLD)) {
				Entity root = player.getRootVehicle();
				root.setPos(root.getX(), root.getY() - 1, root.getZ());
			}
		});
		helper.succeedWhen(() -> {
			ServerPlayer player = mock.player();
			expectIn(helper, player, LayerChain.dimension(1));
			if (!(player.getVehicle() instanceof Minecart)) {
				throw failure(helper, "the player is riding %s after crossing, not a minecart", player.getVehicle());
			}
		});
	}

	@GameTest(maxTicks = 200)
	public void risingAboveLayerOnesTopReturnsToTheOverworld(GameTestHelper helper) {
		double x = 2700.5;
		double z = 2700.5;
		ServerLevel one = layer(helper, 1);
		MockPlayer mock = MockPlayers.join(helper, "surface-rises");
		mock.teleportTo(one, new Vec3(x, one.getMaxY() + 2, z), 0, 0);
		helper.succeedWhen(() -> {
			ServerPlayer player = mock.player();
			expectIn(helper, player, Level.OVERWORLD);
			if (Math.abs(player.getX() - x) > 0.5 || Math.abs(player.getZ() - z) > 0.5) {
				throw failure(helper, "arrived at x=%s z=%s, expected %s %s", player.getX(), player.getZ(), x, z);
			}
			int floor = surface(helper).getMinY() + LayerTuning.DEFAULT.crustThickness();
			if (player.getY() < floor || player.getY() > floor + LayerTuning.DEFAULT.pocketHeight()) {
				throw failure(helper, "arrived at y=%s, not just above the overworld floor (%s)", player.getY(), floor);
			}
		});
	}

	@GameTest(maxTicks = 60)
	public void theOverworldHasNoCrossingAtTheSkyLimit(GameTestHelper helper) {
		ServerLevel surface = surface(helper);
		MockPlayer mock = MockPlayers.join(helper, "surface-sky");
		mock.teleportTo(surface, new Vec3(2800.5, surface.getMaxY() + 5, 2800.5), 0, 0);
		helper.runAfterDelay(40, () -> {
			expectIn(helper, mock.player(), Level.OVERWORLD);
			helper.succeed();
		});
	}

	@GameTest
	public void theOverworldFloorHasNoBedrock(GameTestHelper helper) {
		var registries = helper.getLevel().registryAccess();
		NoiseGeneratorSettings settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getValueOrThrow(NoiseGeneratorSettings.OVERWORLD);
		JsonElement rule = MaterialRule.DIRECT_CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), settings.materialRule().value())
				.getOrThrow(message -> failure(helper, "could not encode the overworld material rule: %s", message));
		if (rule.toString().contains("bedrock_floor")) {
			throw failure(helper, "the overworld material rule still places the bedrock floor: %s", rule);
		}
		helper.succeed();
	}

	@GameTest
	public void villagesOutpostsAndStrongholdsAreGone(GameTestHelper helper) {
		var sets = helper.getLevel().registryAccess().lookupOrThrow(Registries.STRUCTURE_SET);
		for (String name : List.of("villages", "pillager_outposts", "strongholds")) {
			ResourceKey<StructureSet> key = ResourceKey.create(Registries.STRUCTURE_SET, Identifier.withDefaultNamespace(name));
			StructureSet set = sets.getValueOrThrow(key);
			if (!set.structures().isEmpty()) {
				throw failure(helper, "structure set %s still holds %s", name, set.structures());
			}
		}
		helper.succeed();
	}

	@GameTest
	public void aPortalCannotBeLit(GameTestHelper helper) {
		ServerLevel surface = surface(helper);
		BlockPos origin = new BlockPos(3000, 40, 3000);
		// A 4 wide, 5 tall obsidian frame in the x axis, with a 2 by 3 opening.
		for (int dx = 0; dx < 4; dx++) {
			for (int dy = 0; dy < 5; dy++) {
				boolean edge = dx == 0 || dx == 3 || dy == 0 || dy == 4;
				surface.setBlock(origin.offset(dx, dy, 0), edge ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
			}
		}
		BlockPos inside = origin.offset(1, 1, 0);
		surface.setBlock(inside, Blocks.FIRE.defaultBlockState(), 3);
		for (int dx = 1; dx <= 2; dx++) {
			for (int dy = 1; dy <= 3; dy++) {
				if (surface.getBlockState(origin.offset(dx, dy, 0)).is(Blocks.NETHER_PORTAL)) {
					throw failure(helper, "a portal lit at %s", origin.offset(dx, dy, 0));
				}
			}
		}
		helper.succeed();
	}

	/** A mock has no client gravity, so drop it a block a tick while in {@code level}, once its chunk ticks. */
	private static void fallWhileIn(GameTestHelper helper, MockPlayer mock, ServerLevel level) {
		boolean[] ticking = {false};
		FarChunks.awaitEntityTicking(helper, level, mock.player().blockPosition(), () -> ticking[0] = true);
		helper.onEachTick(() -> {
			ServerPlayer player = mock.player();
			if (ticking[0] && player.level().dimension().equals(level.dimension())) {
				player.setPos(player.getX(), player.getY() - 1, player.getZ());
			}
		});
	}

	/**
	 * A mock player teleported within its own dimension does not load chunks around itself, because it never
	 * answers the teleport. A forced chunk keeps the column ticking instead. Forced chunks last as long as the
	 * test world does.
	 */
	private static void keepLoaded(ServerLevel level, double x, double z) {
		level.setChunkForced(BlockPos.containing(x, 0, z).getX() >> 4, BlockPos.containing(x, 0, z).getZ() >> 4, true);
	}

	/** Clear the floor of {@code level} at one column, so an entity above it falls straight out of the world. */
	private static void openShaft(ServerLevel level, double x, double z) {
		BlockPos column = BlockPos.containing(x, 0, z);
		for (int y = level.getMinY(); y <= level.getMinY() + 10; y++) {
			level.setBlock(column.atY(y), Blocks.AIR.defaultBlockState(), 3);
		}
	}

	// assertionException(String, Object...) leaves the placeholders unfilled in the report.
	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	private static void expectIn(GameTestHelper helper, Entity entity, ResourceKey<Level> dimension) {
		if (!entity.level().dimension().equals(dimension)) {
			throw failure(helper, "%s is in %s, expected %s", entity.getName().getString(), entity.level().dimension(), dimension);
		}
	}

	private static ServerLevel surface(GameTestHelper helper) {
		return helper.getLevel().getServer().overworld();
	}

	private static ServerLevel layer(GameTestHelper helper, int layer) {
		ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(layer));
		if (level == null) {
			throw failure(helper, "dimension %s did not load", LayerChain.dimension(layer));
		}
		return level;
	}
}
