package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.test.support.LayerConceptScenes;
import io.github.pkeppeler.deepcharter.test.support.LayerConceptScenes.Scene;
import io.github.pkeppeler.deepcharter.test.support.LayerConceptScenes.Size;
import io.github.pkeppeler.deepcharter.test.support.LayerConceptScenes.View;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeMap;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Server GameTests for the layer concepts (#447, docs/design/layer-concepts-2.md): each of the three scenes that {@code tools/layer_concepts/make.py}
 * writes builds as a sealed box that holds the things the evidence scenario shoots, and its lava sources start a flow. The
 * packs that draw them are files, checked by {@code tools/tests/test_layer_concepts.py} and the client test.
 */
public class LayerConceptsTest {
    /** The things a scene is shot at, so the scene must hold at least this many of the blocks that make them. */
    private static final int COMPANY_ROCK_BLOCKS = 3;

    private static final int CRUST_BLOCKS = 100;

    @GameTest
    public void everySceneBuildsSealedWithItsLampItsCompanyRockAndItsCrust(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Size size = LayerConceptScenes.size(server);
        List<Scene> scenes = LayerConceptScenes.read(server);
        if (scenes.size() != LayerConceptScenes.LAYERS.size()) {
            throw helper.assertionException("%s scenes, not one for each layer", scenes.size());
        }
        for (Scene scene : scenes) {
            LayerConceptScenes.place(server, scene, size);
            ServerLevel level = scene.level(server);
            Counts counts = count(level, scene.origin(), size);
            if (counts.shellOpen > 0) {
                throw helper.assertionException(
                        "scene %s has %s open blocks in the shell of its box: a still could show the world behind it",
                        scene.key(), counts.shellOpen);
            }
            if (counts.lights < 1 || counts.companyRock < COMPANY_ROCK_BLOCKS || counts.crust < CRUST_BLOCKS) {
                throw helper.assertionException(
                        "scene %s has %s light blocks, %s Company Rock and %s breach crust",
                        scene.key(), counts.lights, counts.companyRock, counts.crust);
            }
            if (counts.lava > 0) {
                throw helper.assertionException(
                        "scene %s holds %s lava before the camera arrives; the scenario places it",
                        scene.key(), counts.lava);
            }
            for (View view : scene.views()) {
                Vec3 eye = scene.absolute(view.eye());
                if (!level.getBlockState(BlockPos.containing(eye)).isAir()) {
                    throw helper.assertionException(
                            "scene %s: the camera of %s at %s is in a block", scene.key(), view.name(), eye);
                }
            }
            LayerConceptScenes.release(server, scene, size);
        }
        helper.succeed();
    }

    @GameTest
    public void theLavaSourcesStandInAirAndStartAFlow(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Size size = LayerConceptScenes.size(server);
        for (Scene scene : LayerConceptScenes.read(server)) {
            LayerConceptScenes.place(server, scene, size);
            ServerLevel level = scene.level(server);
            for (BlockPos at : scene.lava()) {
                if (!level.getBlockState(scene.origin().offset(at)).isAir()) {
                    throw helper.assertionException("scene %s: the lava source %s is not in air", scene.key(), at);
                }
            }
            LayerConceptScenes.startLava(server, scene);
            for (BlockPos at : scene.lava()) {
                if (!level.getBlockState(scene.origin().offset(at)).is(Blocks.LAVA)) {
                    throw helper.assertionException("scene %s: the lava source %s did not take", scene.key(), at);
                }
            }
            LayerConceptScenes.release(server, scene, size);
        }
        helper.succeed();
    }

    @GameTest
    public void everyViewsAtmosphereBiomeCarriesTheDecidedFogAmbientColourAndLampTint(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Size size = LayerConceptScenes.size(server);
        var biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
        for (int layer : LayerConceptScenes.LAYERS) {
            Object fog = null;
            Object tintCompany = null;
            for (String light : List.of("company", "deep")) {
                Identifier id = LayerConceptScenes.atmosphere(layer, light);
                EnvironmentAttributeMap attributes = biomes.get(ResourceKey.create(Registries.BIOME, id))
                        .orElseThrow(() -> helper.assertionException("the biome %s does not load", id))
                        .value()
                        .getAttributes();
                for (EnvironmentAttribute<?> attribute : List.of(
                        EnvironmentAttributes.FOG_COLOR,
                        EnvironmentAttributes.AMBIENT_LIGHT_COLOR,
                        EnvironmentAttributes.BLOCK_LIGHT_TINT,
                        EnvironmentAttributes.FOG_START_DISTANCE,
                        EnvironmentAttributes.FOG_END_DISTANCE)) {
                    if (!attributes.contains(attribute)) {
                        throw helper.assertionException("the biome %s sets no %s", id, attribute);
                    }
                }
                Object thisFog = attributes.applyModifier(EnvironmentAttributes.FOG_COLOR, new Vector3f());
                Object tint = attributes.applyModifier(EnvironmentAttributes.BLOCK_LIGHT_TINT, new Vector3f());
                if (fog != null && !fog.equals(thisFog)) {
                    throw helper.assertionException(
                            "layer %s: the two kinds of light have different fog colours", layer);
                }
                if (tintCompany != null && tintCompany.equals(tint)) {
                    throw helper.assertionException("layer %s: the company and deep lamp tints are the same", layer);
                }
                fog = thisFog;
                tintCompany = tint;
            }
            for (Scene scene : LayerConceptScenes.read(server)) {
                if (scene.layer() == layer) {
                    LayerConceptScenes.place(server, scene, size);
                    // It checks the biome in the corners and the middle of the box, and fails when /fillbiome did not
                    // take.
                    LayerConceptScenes.setAtmosphere(server, scene, size, "company");
                    LayerConceptScenes.setAtmosphere(server, scene, size, "deep");
                    LayerConceptScenes.release(server, scene, size);
                }
            }
        }
        helper.succeed();
    }

    private record Counts(int shellOpen, int lights, int companyRock, int crust, int lava) {}

    private static Counts count(ServerLevel level, BlockPos origin, Size size) {
        int shellOpen = 0;
        int lights = 0;
        int companyRock = 0;
        int crust = 0;
        int lava = 0;
        for (int x = 0; x < size.x(); x++) {
            for (int y = 0; y < size.y(); y++) {
                for (int z = 0; z < size.z(); z++) {
                    BlockState state = level.getBlockState(origin.offset(x, y, z));
                    boolean shell =
                            x == 0 || y == 0 || z == 0 || x == size.x() - 1 || y == size.y() - 1 || z == size.z() - 1;
                    if (shell && (state.isAir() || state.is(Blocks.LIGHT) || state.is(Blocks.LAVA))) {
                        shellOpen++;
                    }
                    lights += state.is(Blocks.LIGHT) ? 1 : 0;
                    companyRock += state.is(HazardBlocks.COMPANY_ROCK) ? 1 : 0;
                    crust += state.is(LayerBlocks.BREACH_CRUST) ? 1 : 0;
                    lava += state.is(Blocks.LAVA) ? 1 : 0;
                }
            }
        }
        return new Counts(shellOpen, lights, companyRock, crust, lava);
    }
}
