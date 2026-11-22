package io.github.pkeppeler.deepcharter.client.creature;

import com.geckolib.renderer.base.GeoRenderer;
import com.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer;
import io.github.pkeppeler.deepcharter.creature.Tally;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Draws the glowmask of the Tally's eye at full bright, so that a small amber point shows from far off in the dark with vanilla lighting and
 * no light source. A Tally that is coming apart draws nothing: the eye goes out as it goes. GeckoLib draws it with vanilla's {@code eyes}
 * render type on macOS and Linux (docs/tooling/geckolib-audit.md), so the mask is designed for {@code eyes}.
 */
final class TallyGlowLayer extends AutoGlowingGeoLayer<TallyAnimatable, Tally, TallyRenderState> {
    TallyGlowLayer(GeoRenderer<TallyAnimatable, Tally, TallyRenderState> renderer) {
        super(renderer);
    }

    @Override
    protected Identifier getTextureResource(TallyRenderState renderState) {
        return TallyAssets.glowmask();
    }

    @Override
    protected @Nullable RenderType getRenderType(TallyRenderState renderState) {
        return renderState.fade == 0 ? super.getRenderType(renderState) : null;
    }
}
