package io.github.pkeppeler.deepcharter.client.layer;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.minecraft.util.Mth;

/**
 * Hides the sun and the moon while the camera is under the surface band of the campaign world: in a layer (#1161) or in the unbuilt
 * depth under the last one (#1192). Their biomes stand both at 180 degrees, under the dark disc the client draws below the horizon, and
 * fog that disc. Under water the client draws no dark disc, and the sun and the moon are drawn without fog, so from under water a line of
 * sight past the drawn sections showed them. The sky's state is extracted before {@link LevelExtractionEvents#END_EXTRACTION}, and its
 * rain brightness is the alpha both are drawn with, so a rain brightness of 0 draws them clear.
 *
 * <p>In play {@link BreachVoidCover} now also covers the nadir past the drawn sections (#1188). This and the underground biomes' sky fog are
 * the guard that holds without it, which {@code CavernSkyClientTest} checks with the cover off.
 */
public final class LayerSky {
    private LayerSky() {}

    public static void init() {
        LevelExtractionEvents.END_EXTRACTION.register(LayerSky::hideSunAndMoon);
    }

    private static void hideSunAndMoon(LevelExtractionContext context) {
        int y = Mth.floor(context.camera().position().y);
        if (LayerChain.isCampaign(context.level()) && y < CampaignBands.SURFACE.minY()) {
            context.levelState().skyRenderState.rainBrightness = 0f;
        }
    }
}
