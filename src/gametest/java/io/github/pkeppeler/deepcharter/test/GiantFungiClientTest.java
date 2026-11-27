package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * The giant fungi's looks as the client bakes them (#1202), which a bad model or blockstate would break with no error a
 * server sees: every state of every giant block draws a model of its own, never the missing one, from textures that
 * loaded; the glow is drawn where the design puts it (the rim's line, the gills, the spire tips) and nowhere else (a
 * cap's top and a stem stay dark); and the rim, whose edge is scalloped, is drawn cut out.
 */
public class GiantFungiClientTest implements FabricClientGameTest {
    private static final List<String> BLOCKS = List.of(
            "parasol_stem",
            "parasol_ring",
            "parasol_gills",
            "parasol_cap",
            "parasol_rim",
            "fungal_flare",
            "spire",
            "spire_tip",
            "spire_base");
    /** The blocks whose every state draws some glow, and those that draw none. */
    private static final List<String> GLOWING = List.of("parasol_gills", "parasol_rim", "spire_tip");

    private static final List<String> DARK =
            List.of("parasol_stem", "parasol_cap", "fungal_flare", "spire", "spire_base");

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        context.runOnClient(GiantFungiClientTest::expectTheLooks);
    }

    private static Block block(String path) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
    }

    private static List<BakedQuad> quads(BlockStateModelSet models, BlockState state) {
        List<BlockStateModelPart> parts = new ArrayList<>();
        models.get(state).collectParts(RandomSource.create(0), parts);
        List<BakedQuad> quads = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            for (Direction side : Direction.values()) {
                quads.addAll(part.getQuads(side));
            }
            quads.addAll(part.getQuads(null));
        }
        return quads;
    }

    private static int most(List<BakedQuad> quads, ToIntFunction<BakedQuad> of) {
        return quads.stream().mapToInt(of).max().orElse(0);
    }

    private static void expectTheLooks(Minecraft client) {
        BlockStateModelSet models = client.getModelManager().getBlockStateModelSet();
        int states = 0;
        for (String path : BLOCKS) {
            for (BlockState state : block(path).getStateDefinition().getPossibleStates()) {
                states++;
                require(models.get(state) != models.missingModel(), state + " draws the missing model");
                List<BakedQuad> quads = quads(models, state);
                require(!quads.isEmpty(), state + " draws nothing");
                for (BakedQuad quad : quads) {
                    require(
                            !quad.materialInfo()
                                    .sprite()
                                    .contents()
                                    .name()
                                    .getPath()
                                    .equals("missingno"),
                            state + " draws a missing texture");
                }
                int glow = most(quads, quad -> quad.materialInfo().lightEmission());
                if (GLOWING.contains(path)) {
                    require(glow > 0, state + " draws no glow");
                } else if (DARK.contains(path)) {
                    require(glow == 0, state + " glows at " + glow + ": the mass of a giant stays dark");
                }
            }
        }
        require(states > 1000, "only " + states + " states of the giant blocks were checked");
        BlockState rim = block("parasol_rim").defaultBlockState();
        require(
                quads(models, rim).stream().anyMatch(quad -> quad.materialInfo().layer() == ChunkSectionLayer.CUTOUT),
                "the rim's scalloped skirt is not drawn cut out");
        BlockState cap = block("parasol_cap").defaultBlockState();
        require(
                quads(models, cap).stream()
                        .filter(quad -> quad.direction() == Direction.UP)
                        .allMatch(quad -> quad.materialInfo().lightEmission() == 0),
                "a cap's top glows");
        // Every gill glows at full light (#1218): a gill lamp stands out by its brighter gills and its real light.
        BlockState lamp = block("parasol_gills").defaultBlockState().setValue(BlockStateProperties.LIT, true);
        require(
                quads(models, lamp).stream()
                        .filter(quad -> quad.direction() == Direction.DOWN)
                        .anyMatch(quad -> quad.materialInfo().lightEmission() == 15
                                && quad.materialInfo()
                                        .sprite()
                                        .contents()
                                        .name()
                                        .getPath()
                                        .equals("block/parasol_gills_lamp")),
                "a gill lamp does not draw its own gills at full light");
    }
}
