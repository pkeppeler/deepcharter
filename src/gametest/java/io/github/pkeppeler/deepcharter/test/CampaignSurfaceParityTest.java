package io.github.pkeppeler.deepcharter.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * The layers' density terms must not reach above the surface band (#803). In the shipped overworld they once did: solid pillars rose
 * from the surface to the top of the band in 0.5% of the columns, because the terms ran past their bands. The ground of
 * {@code deepcharter:campaign_sample} (the shipped overworld in the test pack) must be the ground of {@code deepcharter:tall_surface_biomes},
 * the pack's surface world with the surface's own biome source, on a grid of columns, and the density must end the layers' terms at the
 * surface band's floor: a {@code range_choice} on the band below it, a constant that is empty everywhere else.
 */
public class CampaignSurfaceParityTest {
    private static final ResourceKey<Level> CAMPAIGN =
            ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", "campaign_sample"));
    private static final ResourceKey<Level> SURFACE_ONLY = ResourceKey.create(
            Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", "tall_surface_biomes"));
    /** The columns read: a square this many blocks either side of the origin, this far apart (4096 columns, the leak hit 1 in 200). */
    private static final int SPAN = 1024;

    private static final int STEP = 32;
    private static final int MAX_TICKS = 20_000;

    @GameTest(maxTicks = MAX_TICKS)
    public void theCampaignGroundIsTheGroundOfTheSurfaceOnlyWorld(GameTestHelper helper) {
        ServerLevel campaign = ClientLight.serverLevel(helper.getLevel().getServer(), CAMPAIGN);
        ServerLevel surface = ClientLight.serverLevel(helper.getLevel().getServer(), SURFACE_ONLY);
        int columns = 0;
        List<String> differing = new ArrayList<>();
        for (int x = -SPAN; x < SPAN; x += STEP) {
            for (int z = -SPAN; z < SPAN; z += STEP) {
                columns++;
                int expected = baseHeight(surface, x, z);
                int actual = baseHeight(campaign, x, z);
                if (expected != actual) {
                    differing.add(x + " " + z + ": " + expected + " and " + actual);
                }
            }
        }
        if (!differing.isEmpty()) {
            throw helper.assertionException(
                    "the campaign ground differs from the surface-only world in %s of %s columns (x z: surface-only and campaign), the first %s",
                    differing.size(), columns, differing.subList(0, Math.min(5, differing.size())));
        }
        helper.succeed();
    }

    @GameTest
    public void theLayersDensityEndsAtTheSurfaceBandsFloor(GameTestHelper helper) throws IOException {
        JsonObject settings = read("/data/deepcharter/worldgen/noise_settings/campaign.json");
        // final_density = add(squeeze(interpolated(max(<layers' terms>, <surface term>))), beardifier)
        JsonObject layers = settings.getAsJsonObject("noise_router")
                .getAsJsonObject("final_density")
                .getAsJsonObject("left")
                .getAsJsonObject("input")
                .getAsJsonObject("input")
                .getAsJsonObject("left");
        if (!layers.get("type").getAsString().equals("minecraft:range_choice")
                || layers.get("min_inclusive").getAsInt() != CampaignBands.UNBUILT.minY()
                || layers.get("max_exclusive").getAsInt() != CampaignBands.SURFACE.minY()) {
            throw helper.assertionException(
                    "the layers' terms must sit in one range_choice from %s to %s, found %s",
                    CampaignBands.UNBUILT.minY(), CampaignBands.SURFACE.minY(), layers);
        }
        if (!layers.get("when_out_of_range").isJsonPrimitive()
                || layers.get("when_out_of_range").getAsDouble() > -1.0) {
            throw helper.assertionException(
                    "above the surface band's floor the layers' terms must be a constant that is empty, found %s",
                    layers.get("when_out_of_range"));
        }
        helper.succeed();
    }

    private static int baseHeight(ServerLevel level, int x, int z) {
        RandomState random = level.getChunkSource().randomState();
        return level.getChunkSource()
                .getGenerator()
                .getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random);
    }

    private static JsonObject read(String resource) throws IOException {
        try (InputStream stream = CampaignSurfaceParityTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("missing resource " + resource);
            }
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
