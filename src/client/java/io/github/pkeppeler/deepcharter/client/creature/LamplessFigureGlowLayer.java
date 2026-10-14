package io.github.pkeppeler.deepcharter.client.creature;

import com.geckolib.renderer.base.GeoRenderer;
import com.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Draws the glowmask of the lamp in the figure's helmet at full bright, so that a dim amber point shows from far off in the dark with vanilla
 * lighting and no light source. A figure whose lamp is not lit, or one that is dying, draws nothing: the lamp goes out as the figure goes.
 *
 * <p>GeckoLib draws it with vanilla's {@code eyes} render type on macOS and Linux and with its own emissive pipeline on Windows
 * (docs/tooling/geckolib-audit.md), so a mask is designed for {@code eyes}.
 */
final class LamplessFigureGlowLayer
        extends AutoGlowingGeoLayer<LamplessFigureAnimatable, LamplessFigure, LamplessFigureRenderState> {
    LamplessFigureGlowLayer(GeoRenderer<LamplessFigureAnimatable, LamplessFigure, LamplessFigureRenderState> renderer) {
        super(renderer);
    }

    @Override
    protected Identifier getTextureResource(LamplessFigureRenderState renderState) {
        return LamplessFigureAssets.glowmask();
    }

    @Override
    protected @Nullable RenderType getRenderType(LamplessFigureRenderState renderState) {
        return renderState.lamp && renderState.fade == 0 ? super.getRenderType(renderState) : null;
    }
}
