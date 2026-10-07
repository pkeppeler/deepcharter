package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.layer.BreachEvents;
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
	/** Crossings from layer 0 into layer 1 seen per entity UUID, so a test can tell the event fired for its player. */
	private static final Map<UUID, Integer> FROM_SURFACE = new ConcurrentHashMap<>();

	static {
		BreachEvents.CROSSED.register((entity, from, to, fromLayer, toLayer) -> {
			if (fromLayer == LayerChain.SURFACE && toLayer == 1) {
				FROM_SURFACE.merge(entity.getUUID(), 1, Integer::sum);
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
	public void fallingOutOfTheOverworldFloorArrivesInLayerOne(GameTestHelper helper) {
		double x = 2500.5;
		double z = 2500.5;
		ServerLevel surface = surface(helper);
		openShaft(surface, x, z);
		MockPlayer mock = MockPlayers.join(helper, "surface-falls");
		mock.teleportTo(surface, new Vec3(x, surface.getMinY() + 6, z), 0, 0);
		fallWhileIn(helper, mock, surface);
		helper.succeedWhen(() -> {
			ServerPlayer player = mock.player();
			expectIn(helper, player, LayerChain.dimension(1));
			if (FROM_SURFACE.getOrDefault(player.getUUID(), 0) != 1) {
				throw failure(helper, "expected one CROSSED event from layer 0, saw %s", FROM_SURFACE.get(player.getUUID()));
			}
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

	/** Our copy of the overworld rule must be vanilla's, with exactly the bedrock floor step removed. */
	@GameTest
	public void theOverworldRuleIsVanillasMinusTheBedrockFloor(GameTestHelper helper) throws IOException {
		String path = "data/minecraft/worldgen/material_rule/overworld.json";
		List<URL> copies = Collections.list(SurfaceBreachTest.class.getClassLoader().getResources(path));
		URL vanilla = copies.stream().filter(url -> url.toString().contains("minecraft-") && url.toString().contains(".jar"))
				.findFirst().orElseThrow(() -> failure(helper, "no copy of %s in the Minecraft jar among %s", path, copies));
		URL ours = copies.stream().filter(url -> !url.equals(vanilla)).findFirst()
				.orElseThrow(() -> failure(helper, "no deepcharter copy of %s among %s", path, copies));
		JsonObject original = readJson(vanilla).getAsJsonObject();
		JsonArray steps = original.getAsJsonArray("sequence");
		JsonArray kept = new JsonArray();
		int removed = 0;
		for (JsonElement step : steps) {
			if (step.equals(new JsonPrimitive("minecraft:bedrock_floor"))) {
				removed++;
			} else {
				kept.add(step);
			}
		}
		if (removed != 1) {
			throw failure(helper, "vanilla's overworld rule has %d bedrock_floor steps, expected 1", removed);
		}
		original.add("sequence", kept);
		if (!original.equals(readJson(ours))) {
			throw failure(helper, "our overworld rule differs from vanilla's minus bedrock_floor (vanilla: %s, ours: %s)", vanilla, ours);
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
		// Control: the frame is valid, so only the missing portal dimension can stop the portal.
		if (PortalShape.findEmptyPortalShape(surface, inside, Direction.Axis.X).isEmpty()) {
			throw failure(helper, "the obsidian frame is not a valid portal frame");
		}
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

	/** Clear the floor of {@code level} at one column, so an entity above it falls straight out of the world. */
	private static void openShaft(ServerLevel level, double x, double z) {
		BlockPos column = BlockPos.containing(x, 0, z);
		for (int y = level.getMinY(); y <= level.getMinY() + 10; y++) {
			level.setBlock(column.atY(y), Blocks.AIR.defaultBlockState(), 3);
		}
	}

	private static JsonElement readJson(URL url) throws IOException {
		try (Reader reader = new InputStreamReader(url.openStream())) {
			return JsonParser.parseReader(reader);
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
