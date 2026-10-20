package io.github.pkeppeler.deepcharter.test.support;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;

/**
 * The scenes of the layer concepts (#447, docs/design/layer-concepts-2.md; round 1 was #241): for each of layers 1, 2 and 3, one box of rock
 * with a gallery, a hall and a natural cave, written as a structure file by {@code tools/layer_concepts/make.py}, with the cameras to shoot it
 * from and the lava sources that start its flows, which {@code scenes.json} beside the structures lists. Layer 3 does not exist yet, so its
 * scene is built in layer 2's dimension. Nothing here says what a scene looks like: the shape is in the structure file and the look is in the
 * test packs.
 */
public final class LayerConceptScenes {
    public static final List<Integer> LAYERS = List.of(1, 2, 3);
    private static final String NAMESPACE = "deepcharter-test";
    private static final Identifier SCENES = Identifier.fromNamespaceAndPath(NAMESPACE, "layer_concepts/scenes.json");
    /** Where the first scene stands (on a chunk boundary, so a scene touches as few chunks as it can), and the gap between two layers' scenes along X. */
    private static final BlockPos ORIGIN = new BlockPos(3008, 40, 3008);

    private static final int GAP = 128;
    private static final int ATMOSPHERE_MARGIN = 8;
    private static final int FILL_SLICE = 16;

    private LayerConceptScenes() {}

    /**
     * A camera.
     *
     * @param name   the view, as the still is named
     * @param eye    the eye, in blocks from the scene's corner
     * @param target what it looks at, the same way
     * @param settle ticks to wait after the camera arrives, before the still
     * @param light  the kind of light the still is shot under: {@code company} (sodium amber lamp tint) or {@code deep} (cold)
     * @param lamp   the level of the light block put at the camera while the still is shot, as a pod's lamp is, or 0 for none
     */
    public record View(String name, Vec3 eye, Vec3 target, int settle, String light, int lamp) {}

    /**
     * One scene.
     *
     * @param layer     1, 2 or 3: the layer whose rock, light and fog the scene shows
     * @param dimension the layer whose dimension the scene is built in: its own, except that layer 3 has none yet and uses layer 2's
     * @param structure the structure file the scene is built from
     * @param origin    where its lowest corner goes in the layer's dimension
     * @param lava      its lava sources, in blocks from its corner
     * @param views     the cameras, in the order they are shot
     */
    public record Scene(
            int layer, int dimension, Identifier structure, BlockPos origin, List<BlockPos> lava, List<View> views) {
        public String key() {
            return "layer_" + layer;
        }

        public ServerLevel level(MinecraftServer server) {
            return Objects.requireNonNull(
                    server.getLevel(LayerChain.dimension(dimension)),
                    "the server has no level for layer dimension " + dimension);
        }

        public Vec3 absolute(Vec3 local) {
            return local.add(origin.getX(), origin.getY(), origin.getZ());
        }
    }

    /** The size of every scene, in blocks, as the scene file gives it. */
    public record Size(int x, int y, int z) {}

    /** Reads {@code scenes.json} and returns every scene. Fails, naming the file, when it is missing or wrong. */
    public static List<Scene> read(MinecraftServer server) {
        try (Reader reader =
                server.getResourceManager().getResourceOrThrow(SCENES).openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject scenes = root.getAsJsonObject("scenes");
            List<Scene> out = new ArrayList<>();
            for (int layer : LAYERS) {
                String key = "layer_" + layer;
                if (!scenes.has(key)) {
                    throw new IllegalStateException(SCENES + " has no scene " + key + "; it has " + scenes.keySet());
                }
                out.add(parse(layer, scenes.getAsJsonObject(key)));
            }
            if (scenes.size() != out.size()) {
                throw new IllegalStateException(
                        SCENES + " has scenes " + scenes.keySet() + ", not just " + out.size() + " of the layers");
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(SCENES + " does not load", e);
        }
    }

    /** The size of a scene, from {@code scenes.json}. */
    public static Size size(MinecraftServer server) {
        try (Reader reader =
                server.getResourceManager().getResourceOrThrow(SCENES).openAsReader()) {
            JsonArray size = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("size");
            return new Size(
                    size.get(0).getAsInt(), size.get(1).getAsInt(), size.get(2).getAsInt());
        } catch (IOException e) {
            throw new UncheckedIOException(SCENES + " does not load", e);
        }
    }

    private static Scene parse(int layer, JsonObject body) {
        BlockPos origin = ORIGIN.offset(GAP * (layer - 1), 0, 0);
        List<BlockPos> lava = new ArrayList<>();
        for (JsonElement at : body.getAsJsonArray("lava")) {
            JsonArray xyz = at.getAsJsonArray();
            lava.add(new BlockPos(
                    xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt()));
        }
        List<View> views = new ArrayList<>();
        for (JsonElement element : body.getAsJsonArray("views")) {
            JsonObject view = element.getAsJsonObject();
            views.add(new View(
                    view.get("name").getAsString(),
                    vec(view.getAsJsonArray("eye")),
                    vec(view.getAsJsonArray("target")),
                    view.get("wait").getAsInt(),
                    view.get("light").getAsString(),
                    view.get("lamp").getAsInt()));
        }
        return new Scene(
                layer,
                body.get("dimension").getAsInt(),
                Identifier.parse(body.get("structure").getAsString()),
                origin,
                List.copyOf(lava),
                List.copyOf(views));
    }

    private static Vec3 vec(JsonArray xyz) {
        return new Vec3(
                xyz.get(0).getAsDouble(), xyz.get(1).getAsDouble(), xyz.get(2).getAsDouble());
    }

    /**
     * Builds the scene in its layer: the box is cleared through {@link RoomCarver}, which seals its shell against worldgen lava first, and
     * the structure is placed in it. The structure holds only solid blocks, so the box stays air where the rock does not stand.
     */
    public static void place(Hold hold, MinecraftServer server, Scene scene, Size size) {
        ServerLevel level = scene.level(server);
        BlockPos low = scene.origin();
        BlockPos high = low.offset(size.x() - 1, size.y() - 1, size.z() - 1);
        eachChunk(hold.force(), level, low, high);
        RoomCarver.carve(level, low, high, Blocks.AIR.defaultBlockState());
        StructureTemplate template = server.getStructureTemplateManager()
                .get(scene.structure())
                .orElseThrow(() -> new IllegalStateException("the structure " + scene.structure() + " does not load"));
        if (!template.getSize().equals(new Vec3i(size.x(), size.y(), size.z()))) {
            throw new IllegalStateException(
                    "the structure " + scene.structure() + " is " + template.getSize() + ", not " + size);
        }
        template.placeInWorld(level, low, low, new StructurePlaceSettings(), level.getRandom(), Block.UPDATE_CLIENTS);
    }

    /** One chunk operation on a level. */
    public interface ChunkAction {
        void apply(ServerLevel level, int chunkX, int chunkZ);
    }

    /** How a scene's chunks are kept loaded and let go: through a test's {@link FarChunks} hold, or directly in a world no test owns. */
    public record Hold(ChunkAction force, ChunkAction release) {}

    /** The hold of a server GameTest: its chunks are let go when the test ends, even when it fails. */
    public static Hold heldBy(GameTestHelper helper) {
        return new Hold(
                (level, x, z) -> FarChunks.force(helper, level, x, z),
                (level, x, z) -> FarChunks.release(helper, level, x, z));
    }

    /** The hold of the evidence world, which no GameTest owns: the client run discards the world when it ends. */
    public static final Hold EVIDENCE = new Hold(
            // force-ok: the evidence world has no GameTestHelper; release() below lets go, and the run discards the
            // world
            (level, x, z) -> level.setChunkForced(x, z, true),
            // force-ok: pairs with the force above
            (level, x, z) -> level.setChunkForced(x, z, false));

    /**
     * Keeps the scene's chunks loaded (or lets them go). A scene is built long before its camera arrives, and the render distance of the
     * scenario is short, so without this its chunks are unloaded in between, and loading them again from the server thread waited for the
     * shared worker pool behind the client's chunk meshing, which on a loaded machine did not finish.
     */
    private static void eachChunk(ChunkAction action, ServerLevel level, BlockPos low, BlockPos high) {
        for (int cx = low.getX() >> 4; cx <= high.getX() >> 4; cx++) {
            for (int cz = low.getZ() >> 4; cz <= high.getZ() >> 4; cz++) {
                action.apply(level, cx, cz);
                level.getChunk(cx, cz);
            }
        }
    }

    /** Lets the scene's chunks unload again, with the margin {@link #setAtmosphere} used. Called when a test has done with a built scene. */
    public static void release(Hold hold, MinecraftServer server, Scene scene, Size size) {
        BlockPos low = scene.origin().offset(-ATMOSPHERE_MARGIN, 0, -ATMOSPHERE_MARGIN);
        eachChunk(
                hold.release(),
                scene.level(server),
                low,
                scene.origin()
                        .offset(size.x() + ATMOSPHERE_MARGIN - 1, size.y() - 1, size.z() + ATMOSPHERE_MARGIN - 1));
    }

    /** The biome that carries a layer's atmosphere (fog, ambient light and lamp tint) for a kind of light; {@code make.py} writes it. */
    public static Identifier atmosphere(int layer, String light) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, "lc_layer_" + layer + "_" + light);
    }

    /**
     * Puts the scene's box, and {@link #ATMOSPHERE_MARGIN} blocks round it (so the biome blend at the walls is still this biome), in the
     * atmosphere biome of its layer for {@code light}, with {@code /fillbiome}, which resends the biomes to the client. The one entry per
     * layer is the same for every texture option, so only the textures differ. Fails if the biome did not take, because the command reports
     * a failure only in the log.
     */
    public static void setAtmosphere(Hold hold, MinecraftServer server, Scene scene, Size size, String light) {
        ServerLevel level = scene.level(server);
        BlockPos low = scene.origin().offset(-ATMOSPHERE_MARGIN, -ATMOSPHERE_MARGIN, -ATMOSPHERE_MARGIN);
        BlockPos high = scene.origin()
                .offset(
                        size.x() + ATMOSPHERE_MARGIN - 1,
                        size.y() + ATMOSPHERE_MARGIN - 1,
                        size.z() + ATMOSPHERE_MARGIN - 1);
        eachChunk(hold.force(), level, low, high);
        Identifier biome = atmosphere(scene.layer(), light);
        CommandSourceStack source =
                server.createCommandSourceStack().withLevel(level).withSuppressedOutput();
        // /fillbiome takes at most 32768 blocks a call, so the box goes in slices along X.
        for (int x = low.getX(); x <= high.getX(); x += FILL_SLICE) {
            int x2 = Math.min(high.getX(), x + FILL_SLICE - 1);
            server.getCommands()
                    .performPrefixedCommand(
                            source,
                            "fillbiome %d %d %d %d %d %d %s"
                                    .formatted(x, low.getY(), low.getZ(), x2, high.getY(), high.getZ(), biome));
        }
        ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, biome);
        for (BlockPos at : List.of(
                scene.origin(),
                scene.origin().offset(size.x() - 1, size.y() - 1, size.z() - 1),
                scene.origin().offset(size.x() / 2, size.y() / 2, size.z() / 2))) {
            if (!level.getBiome(at).is(key)) {
                throw new IllegalStateException("scene " + scene.key() + ": /fillbiome did not put " + at + " in "
                        + biome + "; it is in " + level.getBiome(at).unwrapKey());
            }
        }
    }

    /** Puts the light block of the view's pod lamp at its camera ({@code on}), or takes it away. A view with no lamp does nothing. */
    public static void setCameraLamp(MinecraftServer server, Scene scene, View view, boolean on) {
        if (view.lamp() > 0) {
            BlockState lamp = on
                    ? Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, view.lamp())
                    : Blocks.AIR.defaultBlockState();
            scene.level(server).setBlock(BlockPos.containing(scene.absolute(view.eye())), lamp, Block.UPDATE_ALL);
        }
    }

    /** Puts the scene's lava sources in place, with updates, so the lava starts to flow. */
    public static void startLava(MinecraftServer server, Scene scene) {
        ServerLevel level = scene.level(server);
        for (BlockPos at : scene.lava()) {
            level.setBlock(scene.origin().offset(at), Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
        }
    }
}
