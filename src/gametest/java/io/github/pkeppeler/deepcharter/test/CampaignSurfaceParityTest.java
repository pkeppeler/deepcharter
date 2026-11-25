package io.github.pkeppeler.deepcharter.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The layers' density terms must not reach above the surface band (#803). In the shipped overworld they once did: solid pillars rose
 * from the surface to the top of the band in 0.5% of the columns, because the terms ran past their bands. The ground of
 * {@code deepcharter:campaign_full} (the shipped overworld in the test pack) must be the ground of {@code deepcharter:surface_sample},
 * the pack's copy of the overworld before the cutover (the surface alone, 384 tall; {@code tall_surface_biomes} is no reference: its own layer
 * terms leak, 320 in 5 of 4096 columns), to within {@link #TOLERANCE} blocks on a grid of columns, and the density must end the layers' terms at the
 * surface band's floor: a {@code range_choice} on the band below it, a constant that is empty everywhere else.
 */
public class CampaignSurfaceParityTest {
    private static final ResourceKey<Level> CAMPAIGN =
            ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", "campaign_full"));
    private static final ResourceKey<Level> SURFACE_SAMPLE =
            ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", "surface_sample"));
    /** The columns read: a square this many blocks either side of the origin, this far apart (4096 columns, the leak hit 1 in 200). */
    private static final int SPAN = 1024;

    private static final int STEP = 32;
    /**
     * The surface-only world has another height and its own cells, so a column may differ by a block or two. A leak is a pillar from the
     * surface to the top of the band, hundreds of blocks, so two density cells (16) separate the two.
     */
    private static final int TOLERANCE = 16;

    private static final Logger LOGGER = LoggerFactory.getLogger(CampaignSurfaceParityTest.class);
    private static final int MAX_TICKS = 20_000;

    @GameTest(environment = CampaignEnvironments.B, maxTicks = MAX_TICKS)
    public void theCampaignGroundIsTheGroundOfTheSurfaceOnlyWorld(GameTestHelper helper) {
        ServerLevel campaign = ClientLight.serverLevel(helper.getLevel().getServer(), CAMPAIGN);
        ServerLevel surface = ClientLight.serverLevel(helper.getLevel().getServer(), SURFACE_SAMPLE);
        int columns = 0;
        int largest = 0;
        List<String> differing = new ArrayList<>();
        for (int x = -SPAN; x < SPAN; x += STEP) {
            for (int z = -SPAN; z < SPAN; z += STEP) {
                columns++;
                int expected = baseHeight(surface, x, z);
                int actual = baseHeight(campaign, x, z);
                if (Math.abs(expected - actual) > TOLERANCE) {
                    differing.add(x + " " + z + ": " + expected + " and " + actual);
                }
                largest = Math.max(largest, Math.abs(expected - actual));
            }
        }
        if (!differing.isEmpty()) {
            throw helper.assertionException(
                    "the campaign ground differs from the surface-only world by over %s blocks in %s of %s columns (x z: surface-only and campaign), the first %s",
                    TOLERANCE, differing.size(), columns, differing.subList(0, Math.min(5, differing.size())));
        }
        LOGGER.info("[campaign-parity] {} columns, the largest height difference {}", columns, largest);
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
