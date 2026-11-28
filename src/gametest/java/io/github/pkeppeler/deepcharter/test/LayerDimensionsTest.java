package io.github.pkeppeler.deepcharter.test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.pkeppeler.deepcharter.layer.Altimeter;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.Depth;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.surface.SurfaceBlocks;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/** Server GameTests for layer dimensions and depth math. */
public class LayerDimensionsTest {
    private static final String CAMPAIGN = "deepcharter:campaign_sample";
    private static final Identifier CRUST_ID = Identifier.fromNamespaceAndPath("deepcharter", "breach_crust");

    /** Runs inside deepcharter:campaign_sample: proves the GameTest server loads the test pack's campaign dimension, and that it holds every layer. */
    @GameTest(environment = CampaignEnvironments.B, dimension = CAMPAIGN)
    public void gameTestRunsInTheCampaignLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        if (!LayerChain.isCampaign(level)
                || level != TestLayers.level(helper, 1)
                || level != TestLayers.level(helper, 2)) {
            throw helper.assertionException("test ran in %s, which is not the level of every layer", level.dimension());
        }
        helper.succeed();
    }

    /**
     * {@code TestLayers.band} of a level that does not hold the layer fails loud: the flat test overworld holds the surface only, and the
     * Nether is no layer's level. (The GameTest server's overworld is flat, so {@code TestLayers.dimension(server, n)} returns
     * the sample here; its overworld branch is tested in {@code CampaignWorldClientTest}, in a default-preset world.)
     */
    @GameTest
    public void aLevelThatDoesNotHoldALayerHasNoBandForIt(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel();
        LayerChain.Band surface = TestLayers.band(overworld, TestLayers.SURFACE);
        if (surface.layer() != TestLayers.SURFACE) {
            throw helper.assertionException("the overworld's surface band is layer %d", surface.layer());
        }
        expectNoBand(helper, overworld, 1);
        expectNoBand(helper, ClientLight.serverLevel(helper.getLevel().getServer(), Level.NETHER), TestLayers.SURFACE);
        helper.succeed();
    }

    /** The surface is a band of the campaign level, and a position is in exactly the band its Y falls in. */
    @GameTest(environment = CampaignEnvironments.B, dimension = CAMPAIGN)
    public void theSurfaceIsABandOfTheCampaignLevel(GameTestHelper helper) {
        ServerLevel campaign = helper.getLevel();
        LayerChain.Band surface = TestLayers.band(campaign, TestLayers.SURFACE);
        if (surface.minY() != -64 || surface.maxY() != 319) {
            throw helper.assertionException(
                    "the surface band of the campaign level is %d..%d, not -64..319", surface.minY(), surface.maxY());
        }
        BlockPos onTheSurface = TestLayers.pos(TestLayers.SURFACE, 0, 0, 0);
        BlockPos inLayer1 = TestLayers.pos(1, 0, 5, 0);
        if (!TestLayers.isIn(campaign, onTheSurface, TestLayers.SURFACE)
                || TestLayers.isIn(campaign, onTheSurface, 1)) {
            throw helper.assertionException("%s should be in the surface and not in layer 1", onTheSurface);
        }
        if (TestLayers.isIn(campaign, inLayer1, TestLayers.SURFACE) || !TestLayers.isIn(campaign, inLayer1, 1)) {
            throw helper.assertionException("%s should be in layer 1 and not in the surface", inLayer1);
        }
        MinecraftServer server = campaign.getServer();
        if (TestLayers.level(server, 1) != campaign
                || TestLayers.level(server, TestLayers.SURFACE) != server.overworld()) {
            throw helper.assertionException("layer 1 is the campaign level and the surface the overworld");
        }
        helper.succeed();
    }

    // The GameTest server ignores data-pack dimensions and bakes its world from the test mod's copy
    // of minecraft:flat_all_dimensions; this keeps that copy equal to the shipped dimension JSON, and
    // to the test data pack's campaign_sample, which the client's default-preset worlds get from the pack.
    @GameTest
    public void testPresetMatchesTheShippedDimensions(GameTestHelper helper) throws IOException {
        JsonObject preset = read(helper, "/data/minecraft/worldgen/world_preset/flat_all_dimensions.json")
                .getAsJsonObject();
        for (String name : List.of("layer_1", "layer_2", "campaign_sample", "campaign_full")) {
            String id = "deepcharter:" + name;
            JsonElement shipped = read(helper, "/data/deepcharter/dimension/" + name + ".json");
            if (!shipped.equals(preset.getAsJsonObject("dimensions").get(id))) {
                throw helper.assertionException(
                        "flat_all_dimensions preset entry %s differs from the shipped dimension", id);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void layerBandsLoadWithExpectedHeights(GameTestHelper helper) {
        expectLayer(helper, 1, -256, 192);
        expectLayer(helper, 2, -512, 256);
        helper.succeed();
    }

    /** The darkening is the zone biomes' ambient light colour (ADR 0059): layer 2 reads a darker one than layer 1, and neither is lit by the sky. */
    @GameTest(environment = CampaignEnvironments.B, dimension = CAMPAIGN)
    public void layersAreDarkerWithDepth(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        float one = brightness(level, TestLayers.pos(1, 0, 100, 0));
        float two = brightness(level, TestLayers.pos(2, 0, 100, 0));
        if (!(two < one)) {
            throw helper.assertionException("layer 2 ambient light %s should be below layer 1's %s", two, one);
        }
        for (int layer = 1; layer <= 2; layer++) {
            float sky = level.environmentAttributes()
                    .getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, TestLayers.pos(layer, 0, 100, 0));
            if (sky != 0f) {
                throw helper.assertionException("layer %d reads a sky light factor of %s, not 0", layer, sky);
            }
        }
        helper.succeed();
    }

    private static float brightness(ServerLevel level, BlockPos pos) {
        Vector3fc colour = level.environmentAttributes().getValue(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, pos);
        return colour.x() + colour.y() + colour.z();
    }

    @GameTest
    public void crustIsAtTheFloor(GameTestHelper helper) {
        Block crust = BuiltInRegistries.BLOCK.getValue(CRUST_ID);
        for (int layer = 1; layer <= 2; layer++) {
            int thickness = LayerTuning.DEFAULT.crustThickness();
            ServerLevel level = TestLayers.level(helper, layer);
            int floor = TestLayers.band(level, layer).minY();
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
        ServerLevel one = TestLayers.level(helper, 1);
        ServerLevel two = TestLayers.level(helper, 2);
        expectInt(helper, Depth.of(one, TestLayers.band(one, 1).maxY() + 1), 127);
        expectInt(helper, Depth.of(one, TestLayers.worldY(1, 100)), 127 + 92);
        expectInt(helper, Depth.of(two, TestLayers.band(two, 2).maxY() + 1), 127 + 192);
        expectInt(helper, Depth.of(two, TestLayers.worldY(2, 0)), 127 + 192 + 256);
        ServerLevel surface = helper.getLevel().getServer().overworld();
        expectInt(helper, Depth.of(surface, 63), 0);
        expectInt(
                helper,
                Depth.of(surface, TestLayers.band(surface, TestLayers.SURFACE).minY()),
                127);
        // #1604: the reading is metres, one to a block, negative below sea level, with its thousands grouped.
        if (!Altimeter.number(1234).equals("-1,234") || !Altimeter.number(-5).equals("5")) {
            throw helper.assertionException("the altimeter reads 1,234 blocks deep as -1,234 and 5 up as 5, got "
                    + Altimeter.number(1234) + " and " + Altimeter.number(-5));
        }
        helper.succeed();
    }

    /** The floor of each link and the top of the next share one depth. */
    @GameTest
    public void depthIsContinuousAcrossTheBoundary(GameTestHelper helper) {
        ServerLevel one = TestLayers.level(helper, 1);
        LayerChain.Band oneBand = TestLayers.band(one, 1);
        ServerLevel two = TestLayers.level(helper, 2);
        ServerLevel surface = helper.getLevel().getServer().overworld();
        expectInt(helper, Depth.of(two, TestLayers.band(two, 2).maxY() + 1), Depth.of(one, oneBand.minY()));
        expectInt(
                helper,
                Depth.of(one, oneBand.maxY() + 1),
                Depth.of(surface, TestLayers.band(surface, TestLayers.SURFACE).minY()));
        helper.succeed();
    }

    @GameTest
    public void depthReadsOnlyFromTheRegistry(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        expectInt(helper, LayerChain.count(registries), 2);
        expectInt(helper, LayerChain.topDepth(registries, 1), 127);
        expectInt(helper, LayerChain.topDepth(registries, 2), 127 + 192);
        expectInt(helper, Depth.of(TestLayers.level(helper, 2), TestLayers.worldY(2, 255)), 127 + 192 + 1);
        helper.succeed();
    }

    @GameTest
    public void gotoIsOpOnly(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer mock =
                MockPlayers.join(server, "layer-goto-denied", helper.getLevel(), helper.absoluteVec(Vec3.ZERO));
        try {
            var source = mock.player()
                    .createCommandSourceStack()
                    .withPermission(LevelBasedPermissionSet.forLevel(PermissionLevel.ALL));
            var before = mock.player().level().dimension();
            try {
                server.getCommands().getDispatcher().execute("deepcharter layer goto 1", source);
                throw helper.assertionException("a non-op ran /deepcharter layer goto");
            } catch (CommandSyntaxException denied) {
                if (!denied.getType().equals(CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand())
                        && !denied.getType()
                                .equals(CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument())) {
                    throw helper.assertionException(
                            "expected an unknown-command failure, got %s", String.valueOf(denied.getMessage()));
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
        MockPlayer mock = MockPlayers.join(server, "layer-goto-op", helper.getLevel(), helper.absoluteVec(Vec3.ZERO));
        try {
            // In the campaign level a goto stays in the level and moves the player to the band.
            ServerLevel level = TestLayers.level(helper, 1);
            mock.teleportTo(level, TestLayers.vec(1, 0.5, 100, 0.5), 0, 0);
            var source = mock.player().createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
            server.getCommands().getDispatcher().execute("deepcharter layer goto 2", source);
            LayerChain.Band levelBand = TestLayers.band(level, 2);
            if (!TestLayers.isIn(mock.player(), 2) || mock.player().level() != level) {
                throw helper.assertionException(
                        "player is at y=%s in %s after goto 2",
                        mock.player().getY(), mock.player().level().dimension());
            }
            Vec3 pos = mock.player().position();
            if (pos.y < levelBand.minY() + LayerTuning.DEFAULT.crustThickness() || pos.y > levelBand.maxY()) {
                throw helper.assertionException("player placed at y=%s, outside the layer", pos.y);
            }
            try {
                int past = CampaignBands.lastLayer() + 1;
                server.getCommands().getDispatcher().execute("deepcharter layer goto " + past, source);
                throw helper.assertionException(
                        "goto %s should fail: only %s layers exist", past, CampaignBands.lastLayer());
            } catch (CommandSyntaxException outOfRange) {
                if (!String.valueOf(outOfRange.getMessage()).contains("No layer " + (CampaignBands.lastLayer() + 1))) {
                    throw helper.assertionException(
                            "expected the no-such-layer failure, got %s", String.valueOf(outOfRange.getMessage()));
                }
            }
        } finally {
            mock.leave();
        }
        helper.succeed();
    }

    /** The motion-blocking heightmap counts the mod's blocks as ground (#350): goto stands the player on one, not inside it. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
    public void gotoStandsOnAModBlock(GameTestHelper helper) {
        FarChunks.whenBlocksLoaded(
                helper, List.of(new FarChunks.At(TestLayers.level(helper, 2), 300_000 >> 4, 300_000 >> 4)), () -> {
                    try {
                        gotoStandsOnAModBlockLoaded(helper);
                    } catch (CommandSyntaxException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private static void gotoStandsOnAModBlockLoaded(GameTestHelper helper) throws CommandSyntaxException {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer mock =
                MockPlayers.join(server, "layer-goto-block", helper.getLevel(), helper.absoluteVec(Vec3.ZERO));
        try {
            ServerLevel layer = TestLayers.level(helper, 2);
            mock.teleportTo(layer, TestLayers.vec(1, 300_000.5, 100, 300_000.5), 0, 0);
            BlockPos block = new BlockPos(300_000, TestLayers.band(layer, 2).maxY() - 2, 300_000);
            // The command stands on the highest place with room over it: the band's rock has none here, so two cells
            // are opened over the block.
            RoomCarver.carve(layer, block.above(), block.above(2), Blocks.AIR.defaultBlockState());
            layer.setBlockAndUpdate(block, SurfaceBlocks.REGOLITH_PACKED.defaultBlockState());
            var source = mock.player().createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
            server.getCommands().getDispatcher().execute("deepcharter layer goto 2", source);
            if (mock.player().blockPosition().getY() != block.getY() + 1) {
                throw helper.assertionException(
                        "player placed at %s, expected on top of the block at %s",
                        mock.player().blockPosition().toShortString(), block.toShortString());
            }
        } finally {
            mock.leave();
        }
        helper.succeed();
    }

    /** A far column of the campaign level: goto places the player in the band there, under its top and over its crust. */
    @GameTest
    public void gotoPlacesAPlayerInTheBandAtAFarColumn(GameTestHelper helper) throws CommandSyntaxException {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer mock = MockPlayers.join(server, "layer-goto-far", helper.getLevel(), helper.absoluteVec(Vec3.ZERO));
        try {
            ServerLevel layer = TestLayers.level(helper, 1);
            LayerChain.Band layerBand = TestLayers.band(layer, 1);
            mock.teleportTo(
                    layer,
                    new Vec3(
                            200_000.5,
                            TestLayers.band(layer, TestLayers.SURFACE).minY() + 50,
                            200_000.5),
                    0,
                    0);
            var source = mock.player().createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
            server.getCommands().getDispatcher().execute("deepcharter layer goto 1", source);
            double y = mock.player().position().y;
            if (mock.player().level() != layer
                    || y < layerBand.minY() + LayerTuning.DEFAULT.crustThickness()
                    || y > layerBand.maxY()) {
                throw helper.assertionException("player placed at y=%s, outside the layer", y);
            }
        } finally {
            mock.leave();
        }
        helper.succeed();
    }

    private static JsonElement read(GameTestHelper helper, String resource) throws IOException {
        var stream = LayerDimensionsTest.class.getResourceAsStream(resource);
        if (stream == null) {
            throw helper.assertionException("missing classpath resource %s", resource);
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        }
    }

    private static void expectNoBand(GameTestHelper helper, ServerLevel level, int layer) {
        try {
            TestLayers.band(level, layer);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw helper.assertionException(
                "band(%s, %d) should fail: the level does not hold the layer", level.dimension(), layer);
    }

    private static void expectLayer(GameTestHelper helper, int layer, int minY, int height) {
        ServerLevel level = TestLayers.level(helper, layer);
        LayerChain.Band levelBand = TestLayers.band(level, layer);
        if (levelBand.minY() != minY || levelBand.height() != height) {
            throw helper.assertionException(
                    "layer_%d: expected minY %d height %d, got minY %d height %d",
                    layer, minY, height, levelBand.minY(), levelBand.height());
        }
    }

    private static void expectInt(GameTestHelper helper, int actual, int expected) {
        if (actual != expected) {
            throw helper.assertionException("expected %d, got %d", expected, actual);
        }
    }
}
