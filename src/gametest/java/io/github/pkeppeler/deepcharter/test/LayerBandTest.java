package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Server GameTests for {@link LayerChain#bandAt}: a band is a whole dimension for now, so any Y reads the same band. */
public class LayerBandTest {
    @GameTest
    public void aLayerIsOneBandWhateverTheY(GameTestHelper helper) {
        expectBand(helper, 1, 0, 192);
        expectBand(helper, 2, 0, 256);
        helper.succeed();
    }

    @GameTest
    public void theSurfaceIsBandZero(GameTestHelper helper) {
        ServerLevel surface = helper.getLevel().getServer().overworld();
        Band band = bandAt(helper, surface, 63);
        if (band.isLayer()) {
            throw helper.assertionException("the overworld read as layer %d", band.layer());
        }
        if (band.layer() != LayerChain.SURFACE
                || band.minY() != surface.getMinY()
                || band.height() != surface.getHeight()) {
            throw helper.assertionException(
                    "expected the surface band %d..+%d, got %s", surface.getMinY(), surface.getHeight(), band);
        }
        helper.succeed();
    }

    @GameTest
    public void aLevelOutsideTheChainHasNoBand(GameTestHelper helper) {
        ServerLevel nether = ClientLight.serverLevel(helper.getLevel().getServer(), Level.NETHER);
        Optional<Band> band = LayerChain.bandAt(nether, nether.getMinY() + 4);
        if (band.isPresent()) {
            throw helper.assertionException("the Nether read as %s", band.get());
        }
        helper.succeed();
    }

    private static void expectBand(GameTestHelper helper, int layer, int minY, int height) {
        ServerLevel level = TestLayers.level(helper, layer);
        for (int y : new int[] {level.getMinY(), level.getMinY() + height / 2, level.getMaxY()}) {
            Band band = bandAt(helper, level, y);
            if (!band.isLayer() || band.layer() != layer || band.minY() != minY || band.height() != height) {
                throw helper.assertionException(
                        "layer_%d at y=%d: expected layer %d, minY %d, height %d, got %s",
                        layer, y, layer, minY, height, band);
            }
        }
    }

    private static Band bandAt(GameTestHelper helper, ServerLevel level, int y) {
        return LayerChain.bandAt(level, y)
                .orElseThrow(() -> helper.assertionException("%s has no band at y=%d", level.dimension(), y));
    }
}
