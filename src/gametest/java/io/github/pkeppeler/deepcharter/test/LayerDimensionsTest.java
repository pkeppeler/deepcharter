package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.Reader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.layer.Depth;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/** Server GameTests for layer dimensions and depth math. */
public class LayerDimensionsTest {
	private static final Identifier CRUST_ID = Identifier.fromNamespaceAndPath("deepcharter", "breach_crust");

	/** Runs inside deepcharter:layer_1: proves the GameTest server loads data-pack dimensions. */
	@GameTest(dimension = "deepcharter:layer_1")
	public void gameTestRunsInLayerOne(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		if (!level.dimension().equals(LayerChain.dimension(1))) {
			throw helper.assertionException("test ran in %s, not deepcharter:layer_1", level.dimension());
		}
		helper.succeed();
	}

	// The GameTest server ignores data-pack dimensions and bakes its world from the test mod's copy
	// of minecraft:flat_all_dimensions; this keeps that copy equal to the shipped dimension JSON.
	@GameTest
	public void testPresetMatchesTheShippedDimensions(GameTestHelper helper) throws IOException {
		JsonObject preset = read(helper, "/data/minecraft/worldgen/world_preset/flat_all_dimensions.json").getAsJsonObject();
		for (int layer = 1; layer <= 2; layer++) {
			String id = "deepcharter:layer_" + layer;
			JsonElement shipped = read(helper, "/data/deepcharter/dimension/layer_" + layer + ".json");
			if (!shipped.equals(preset.getAsJsonObject("dimensions").get(id))) {
				throw helper.assertionException("flat_all_dimensions preset entry %s differs from the shipped dimension", id);
			}
		}
		helper.succeed();
	}

	@GameTest
	public void layersLoadWithExpectedHeights(GameTestHelper helper) {
		expectLayer(helper, 1, 0, 192);
		expectLayer(helper, 2, 0, 256);
		helper.succeed();
	}

	@GameTest
	public void layersAreDarkerWithDepth(GameTestHelper helper) {
		float one = level(helper, 1).dimensionType().ambientLight();
		float two = level(helper, 2).dimensionType().ambientLight();
		if (!(two < one)) {
			throw helper.assertionException("layer_2 ambient light %s should be below layer_1's %s", two, one);
		}
		if (level(helper, 1).dimensionType().hasSkyLight() || level(helper, 2).dimensionType().hasSkyLight()) {
			throw helper.assertionException("layers must have no skylight");
		}
		helper.succeed();
	}

	@GameTest
	public void crustIsAtTheFloor(GameTestHelper helper) {
		Block crust = BuiltInRegistries.BLOCK.getValue(CRUST_ID);
		for (int layer = 1; layer <= 2; layer++) {
			int thickness = crustThickness(helper, layer);
			ServerLevel level = level(helper, layer);
			int floor = level.getMinY();
			for (int dy = 0; dy < thickness; dy++) {
				if (!level.getBlockState(new BlockPos(5, floor + dy, 5)).is(crust)) {
					throw helper.assertionException("layer_%d: expected breach_crust at y=%d", layer, floor + dy);
				}
			}
			if (level.getBlockState(new BlockPos(5, floor + thickness, 5)).is(crust)) {
				throw helper.assertionException("layer_%d: crust is thicker than %d blocks", layer, thickness);
			}
		}
		helper.succeed();
	}

	@GameTest
	public void depthMatchesTheFormula(GameTestHelper helper) {
		ServerLevel one = level(helper, 1);
		ServerLevel two = level(helper, 2);
		expectInt(helper, Depth.of(one, one.getMaxY() + 1), 127);
		expectInt(helper, Depth.of(one, 100), 127 + 92);
		expectInt(helper, Depth.of(two, two.getMaxY() + 1), 127 + 192);
		expectInt(helper, Depth.of(two, 0), 127 + 192 + 256);
		ServerLevel surface = helper.getLevel().getServer().overworld();
		expectInt(helper, Depth.of(surface, 63), 0);
		expectInt(helper, Depth.of(surface, surface.getMinY()), 127);
		expectInt(helper, Depth.feet(1000), 3280);
		helper.succeed();
	}

	/** The floor of each link and the top of the next share one depth. */
	@GameTest
	public void depthIsContinuousAcrossTheBoundary(GameTestHelper helper) {
		ServerLevel one = level(helper, 1);
		ServerLevel two = level(helper, 2);
		ServerLevel surface = helper.getLevel().getServer().overworld();
		expectInt(helper, Depth.of(two, two.getMaxY() + 1), Depth.of(one, one.getMinY()));
		expectInt(helper, Depth.of(one, one.getMaxY() + 1), Depth.of(surface, surface.getMinY()));
		helper.succeed();
	}

	@GameTest
	public void depthReadsOnlyFromTheRegistry(GameTestHelper helper) {
		RegistryAccess registries = helper.getLevel().registryAccess();
		expectInt(helper, LayerChain.count(registries), 2);
		expectInt(helper, LayerChain.topDepth(registries, 1), 127);
		expectInt(helper, LayerChain.topDepth(registries, 2), 127 + 192);
		expectInt(helper, Depth.of(registries, Identifier.fromNamespaceAndPath("deepcharter", "layer_2"), 255), 127 + 192 + 1);
		helper.succeed();
	}

	@GameTest
	public void gotoIsOpOnly(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer mock = MockPlayers.join(server, "layer-goto-denied");
		try {
			var source = mock.player().createCommandSourceStack().withPermission(LevelBasedPermissionSet.ALL);
			var before = mock.player().level().dimension();
			try {
				server.getCommands().getDispatcher().execute("deepcharter layer goto 1", source);
				throw helper.assertionException("a non-op ran /deepcharter layer goto");
			} catch (CommandSyntaxException denied) {
				if (!denied.getType().equals(CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand())
						&& !denied.getType().equals(CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument())) {
					throw helper.assertionException("expected an unknown-command failure, got %s", denied.getMessage());
				}
			}
			if (!mock.player().level().dimension().equals(before)) {
				throw helper.assertionException("a denied non-op still changed dimension");
			}
		} finally {
			mock.leave();
		}
		helper.succeed();
	}

	@GameTest
	public void gotoMovesAnOpToTheLayer(GameTestHelper helper) throws CommandSyntaxException {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer mock = MockPlayers.join(server, "layer-goto-op");
		try {
			var source = mock.player().createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
			server.getCommands().getDispatcher().execute("deepcharter layer goto 2", source);
			ServerLevel level = mock.player().level();
			if (!level.dimension().equals(LayerChain.dimension(2))) {
				throw helper.assertionException("player is in %s after goto 2", level.dimension());
			}
			Vec3 pos = mock.player().position();
			if (pos.y < level.getMinY() + crustThickness(helper, 2) || pos.y > level.getMaxY()) {
				throw helper.assertionException("player placed at y=%s, outside the layer", pos.y);
			}
			try {
				server.getCommands().getDispatcher().execute("deepcharter layer goto 3", source);
				throw helper.assertionException("goto 3 should fail: only 2 layers exist");
			} catch (CommandSyntaxException outOfRange) {
				if (!outOfRange.getMessage().contains("No layer 3")) {
					throw helper.assertionException("expected the no-such-layer failure, got %s", outOfRange.getMessage());
				}
			}
		} finally {
			mock.leave();
		}
		helper.succeed();
	}

	/** A far, never-loaded column: goto must generate the target chunk, not read an empty heightmap. */
	@GameTest
	public void gotoGeneratesAnUnloadedTargetChunk(GameTestHelper helper) throws CommandSyntaxException {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer mock = MockPlayers.join(server, "layer-goto-far");
		try {
			mock.player().setPos(200_000.5, 80, 200_000.5);
			ServerLevel layer = level(helper, 1);
			if (layer.getChunkSource().getChunkNow(200_000 >> 4, 200_000 >> 4) != null) {
				throw helper.assertionException("the far chunk was already loaded, so the test proves nothing");
			}
			var source = mock.player().createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
			server.getCommands().getDispatcher().execute("deepcharter layer goto 1", source);
			double y = mock.player().position().y;
			if (y < layer.getMinY() + crustThickness(helper, 1) || y > layer.getMaxY()) {
				throw helper.assertionException("player placed at y=%s, outside the layer", y);
			}
		} finally {
			mock.leave();
		}
		helper.succeed();
	}

	/** The height of the bottom flat-generator layer of the shipped dimension, which is the crust. */
	private static int crustThickness(GameTestHelper helper, int layer) {
		try {
			return read(helper, "/data/deepcharter/dimension/layer_" + layer + ".json").getAsJsonObject()
					.getAsJsonObject("generator").getAsJsonObject("settings")
					.getAsJsonArray("layers").get(0).getAsJsonObject().get("height").getAsInt();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static JsonElement read(GameTestHelper helper, String resource) throws IOException {
		var stream = LayerDimensionsTest.class.getResourceAsStream(resource);
		if (stream == null) {
			throw helper.assertionException("missing classpath resource %s", resource);
		}
		try (Reader reader = new InputStreamReader(stream)) {
			return JsonParser.parseReader(reader);
		}
	}

	private static ServerLevel level(GameTestHelper helper, int layer) {
		ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(layer));
		if (level == null) {
			throw helper.assertionException("dimension %s did not load", LayerChain.dimension(layer));
		}
		return level;
	}

	private static void expectLayer(GameTestHelper helper, int layer, int minY, int height) {
		ServerLevel level = level(helper, layer);
		if (level.getMinY() != minY || level.getHeight() != height) {
			throw helper.assertionException("layer_%d: expected minY %d height %d, got minY %d height %d",
					layer, minY, height, level.getMinY(), level.getHeight());
		}
	}

	private static void expectInt(GameTestHelper helper, int actual, int expected) {
		if (actual != expected) {
			throw helper.assertionException("expected %d, got %d", expected, actual);
		}
	}
}
