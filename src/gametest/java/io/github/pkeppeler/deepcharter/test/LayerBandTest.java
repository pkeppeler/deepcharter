package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Server GameTests for {@link LayerChain#bandAt}: in the campaign level a layer is a band of {@link CampaignBands}, and every Y of it reads that band. */
public class LayerBandTest {
    @GameTest
    public void aLayerIsOneBandWhateverTheYInIt(GameTestHelper helper) {
        expectBand(helper, 1);
        expectBand(helper, 2);
        helper.succeed();
    }

    @GameTest
    public void theSurfaceIsBandZero(GameTestHelper helper) {
        ServerLevel surface = helper.getLevel().getServer().overworld();
        LayerChain.Band surfaceBand = TestLayers.band(surface, TestLayers.SURFACE);
        Band band = bandAt(helper, surface, 63);
        if (band.isLayer()) {
            throw helper.assertionException("the overworld read as layer %d", band.layer());
        }
        if (band.layer() != LayerChain.SURFACE
                || band.minY() != surfaceBand.minY()
                || band.height() != surfaceBand.height()) {
            throw helper.assertionException(
                    "expected the surface band %d..+%d, got %s", surfaceBand.minY(), surfaceBand.height(), band);
        }
        helper.succeed();
    }

    @GameTest
    public void aLevelOutsideTheChainHasNoBand(GameTestHelper helper) {
        ServerLevel nether = ClientLight.serverLevel(helper.getLevel().getServer(), Level.NETHER);
        // level-height: the Nether is not in the chain, so it has no layer band
        Optional<Band> band = LayerChain.bandAt(nether, nether.getMinY() + 4);
        if (band.isPresent()) {
            throw helper.assertionException("the Nether read as %s", band.get());
        }
        helper.succeed();
    }

    private static void expectBand(GameTestHelper helper, int layer) {
        ServerLevel level = TestLayers.level(helper, layer);
        LayerChain.Band levelBand = TestLayers.band(level, layer);
        CampaignBands.Band table = CampaignBands.layer(layer);
        for (int y : new int[] {levelBand.minY(), levelBand.minY() + levelBand.height() / 2, levelBand.maxY()}) {
            Band band = bandAt(helper, level, y);
            if (!band.isLayer()
                    || band.layer() != layer
                    || band.minY() != table.minY()
                    || band.height() != table.height()) {
                throw helper.assertionException(
                        "layer %d at y=%d: expected layer %d, minY %d, height %d, got %s",
                        layer, y, layer, table.minY(), table.height(), band);
            }
        }
    }

    private static Band bandAt(GameTestHelper helper, ServerLevel level, int y) {
        return LayerChain.bandAt(level, y)
                .orElseThrow(() -> helper.assertionException("%s has no band at y=%d", level.dimension(), y));
    }
}
