package io.github.pkeppeler.deepcharter.test.support;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.layer.LayerChain;

/**
 * The scenes of the layer concepts (#241, docs/design/layer-concepts.md): for each of the three options and each of layers 1 and 2, one
 * gallery in the rock, written as a structure file by {@code tools/layer_concepts/build.py}, with the cameras to shoot it from and the
 * lava sources that start its flows, which {@code scenes.json} beside the structures lists. Nothing here says what a scene looks like:
 * the shape is in the structure file and the look is in the option's test pack.
 */
public final class LayerConceptScenes {
	/** The options, in the order the evidence scenario shoots them, each with the test pack that draws it. */
	public static final List<String> OPTIONS = List.of("a", "b", "c");
	public static final List<Integer> LAYERS = List.of(1, 2);
	private static final String NAMESPACE = "deepcharter-test";
	private static final Identifier SCENES = Identifier.fromNamespaceAndPath(NAMESPACE, "layer_concepts/scenes.json");
	/** Where the first scene stands in each layer, and the gap between two options' scenes along X. */
	private static final BlockPos ORIGIN = new BlockPos(3000, 40, 3000);
	private static final int GAP = 80;

	private LayerConceptScenes() {
	}

	/**
	 * A camera.
	 *
	 * @param name   the view, as the still is named
	 * @param eye    the eye, in blocks from the scene's corner
	 * @param target what it looks at, the same way
	 * @param settle ticks to wait after the camera arrives, before the still
	 */
	public record View(String name, Vec3 eye, Vec3 target, int settle) {
	}

	/**
	 * One scene.
	 *
	 * @param option    {@code a}, {@code b} or {@code c}
	 * @param layer     1 or 2
	 * @param structure the structure file the scene is built from
	 * @param origin    where its lowest corner goes in the layer's dimension
	 * @param lava      its lava sources, in blocks from its corner
	 * @param views     the cameras, in the order they are shot
	 */
	public record Scene(String option, int layer, Identifier structure, BlockPos origin, List<BlockPos> lava, List<View> views) {
		public String key() {
			return option + "_" + layer;
		}

		public ServerLevel level(MinecraftServer server) {
			return server.getLevel(LayerChain.dimension(layer));
		}

		public Vec3 absolute(Vec3 local) {
			return local.add(origin.getX(), origin.getY(), origin.getZ());
		}
	}

	/** The size of every scene, in blocks, as the scene file gives it. */
	public record Size(int x, int y, int z) {
	}

	/** Reads {@code scenes.json} and returns every scene. Fails, naming the file, when it is missing or wrong. */
	public static List<Scene> read(MinecraftServer server) {
		try (Reader reader = server.getResourceManager().getResourceOrThrow(SCENES).openAsReader()) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			JsonObject scenes = root.getAsJsonObject("scenes");
			List<Scene> out = new ArrayList<>();
			for (String option : OPTIONS) {
				for (int layer : LAYERS) {
					String key = option + "_" + layer;
					if (!scenes.has(key)) {
						throw new IllegalStateException(SCENES + " has no scene " + key + "; it has " + scenes.keySet());
					}
					out.add(parse(option, layer, scenes.getAsJsonObject(key)));
				}
			}
			if (scenes.size() != out.size()) {
				throw new IllegalStateException(SCENES + " has scenes " + scenes.keySet() + ", not just " + out.size() + " of the options and layers");
			}
			return out;
		} catch (IOException e) {
			throw new UncheckedIOException(SCENES + " does not load", e);
		}
	}

	/** The size of a scene, from {@code scenes.json}. */
	public static Size size(MinecraftServer server) {
		try (Reader reader = server.getResourceManager().getResourceOrThrow(SCENES).openAsReader()) {
			JsonArray size = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("size");
			return new Size(size.get(0).getAsInt(), size.get(1).getAsInt(), size.get(2).getAsInt());
		} catch (IOException e) {
			throw new UncheckedIOException(SCENES + " does not load", e);
		}
	}

	private static Scene parse(String option, int layer, JsonObject body) {
		BlockPos origin = ORIGIN.offset(GAP * OPTIONS.indexOf(option), 0, 0);
		List<BlockPos> lava = new ArrayList<>();
		for (JsonElement at : body.getAsJsonArray("lava")) {
			JsonArray xyz = at.getAsJsonArray();
			lava.add(new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt()));
		}
		List<View> views = new ArrayList<>();
		for (JsonElement element : body.getAsJsonArray("views")) {
			JsonObject view = element.getAsJsonObject();
			views.add(new View(view.get("name").getAsString(), vec(view.getAsJsonArray("eye")), vec(view.getAsJsonArray("target")), view.get("wait").getAsInt()));
		}
		return new Scene(option, layer, Identifier.parse(body.get("structure").getAsString()), origin, List.copyOf(lava), List.copyOf(views));
	}

	private static Vec3 vec(JsonArray xyz) {
		return new Vec3(xyz.get(0).getAsDouble(), xyz.get(1).getAsDouble(), xyz.get(2).getAsDouble());
	}

	/**
	 * Builds the scene in its layer: the box is cleared through {@link RoomCarver}, which seals its shell against worldgen lava first, and
	 * the structure is placed in it. The structure holds only solid blocks, so the box stays air where the rock does not stand.
	 */
	public static void place(MinecraftServer server, Scene scene, Size size) {
		ServerLevel level = scene.level(server);
		BlockPos low = scene.origin();
		BlockPos high = low.offset(size.x() - 1, size.y() - 1, size.z() - 1);
		for (int cx = low.getX() >> 4; cx <= high.getX() >> 4; cx++) {
			for (int cz = low.getZ() >> 4; cz <= high.getZ() >> 4; cz++) {
				level.getChunk(cx, cz);
			}
		}
		RoomCarver.carve(level, low, high, Blocks.AIR.defaultBlockState());
		StructureTemplate template = server.getStructureTemplateManager().get(scene.structure())
				.orElseThrow(() -> new IllegalStateException("the structure " + scene.structure() + " does not load"));
		if (!template.getSize().equals(new Vec3i(size.x(), size.y(), size.z()))) {
			throw new IllegalStateException("the structure " + scene.structure() + " is " + template.getSize() + ", not " + size);
		}
		template.placeInWorld(level, low, low, new StructurePlaceSettings(), level.getRandom(), Block.UPDATE_CLIENTS);
	}

	/** Puts the scene's lava sources in place, with updates, so the lava starts to flow. */
	public static void startLava(MinecraftServer server, Scene scene) {
		ServerLevel level = scene.level(server);
		for (BlockPos at : scene.lava()) {
			level.setBlock(scene.origin().offset(at), Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
		}
	}
}
