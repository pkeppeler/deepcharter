package io.github.pkeppeler.deepcharter.client.pod;

import com.geckolib.renderer.base.GeoRenderer;
import com.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Draws the look's hot mask at full bright while lava burns the hull (#634). Like {@link PodGlowLayer} it is a render layer and no
 * light source (ADR 0024).
 */
public final class PodHotLayer extends AutoGlowingGeoLayer<PodGeoAnimatable, PodEntity, PodGeoRenderState> {
    PodHotLayer(GeoRenderer<PodGeoAnimatable, PodEntity, PodGeoRenderState> renderer) {
        super(renderer);
    }

    /** Whether the mask is drawn: the hull burns, the pod is whole and its cab is drawn. */
    public static boolean draws(PodGeoRenderState state) {
        return state.hot && !state.cabHidden && !state.appearance.wrecked();
    }

    @Override
    public Identifier getTextureResource(PodGeoRenderState renderState) {
        return renderState.appearance.variant().hotmask();
    }

    @Override
    public @Nullable RenderType getRenderType(PodGeoRenderState renderState) {
        return draws(renderState) ? super.getRenderType(renderState) : null;
    }
}
