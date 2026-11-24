package io.github.pkeppeler.deepcharter.client.creature;

import com.geckolib.renderer.base.GeoRenderer;
import com.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer;
import io.github.pkeppeler.deepcharter.creature.Drifter;
import net.minecraft.resources.Identifier;

/**
 * Draws the glowmask of the core and the veins at full bright, so that a drifter glows in the dark with vanilla lighting and no light source:
 * the sense that comes before the sight. GeckoLib draws it with vanilla's {@code eyes} render type on macOS and Linux and with its own emissive
 * pipeline on Windows (docs/tooling/geckolib-audit.md), so a mask is designed for {@code eyes}.
 */
final class DrifterGlowLayer extends AutoGlowingGeoLayer<DrifterAnimatable, Drifter, DrifterRenderState> {
    DrifterGlowLayer(GeoRenderer<DrifterAnimatable, Drifter, DrifterRenderState> renderer) {
        super(renderer);
    }

    @Override
    protected Identifier getTextureResource(DrifterRenderState renderState) {
        return DrifterAssets.glowmask();
    }
}
