package io.github.pkeppeler.deepcharter.client.pod;

import com.geckolib.renderer.base.GeoRenderer;
import com.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Draws the pod's hot mask over its texture at full bright while lava burns its hull (#634): the hull glows like hot metal against the
 * dark rock. It is the glowmask layer's sibling ({@link PodGlowLayer}) and reads the same way: the render type is vanilla's {@code eyes}
 * on macOS and Linux, a render layer and not a light source (ADR 0024), so the glow lights nothing around the pod. The mask is a texture
 * of the pod's look ({@link PodLook.Variant#hotmask}), so a pack restyles the heat by replacing it.
 */
public final class PodHotLayer extends AutoGlowingGeoLayer<PodGeoAnimatable, PodEntity, PodGeoRenderState> {
    PodHotLayer(GeoRenderer<PodGeoAnimatable, PodEntity, PodGeoRenderState> renderer) {
        super(renderer);
    }

    /** Whether the hot mask is drawn this frame: the hull burns and the pod is whole and drawn (a wreck is cold, and the camera feed hides the cab). */
    public static boolean draws(PodGeoRenderState state) {
        return state.hot && !state.cabHidden && !state.appearance.wrecked();
    }

    @Override
    protected Identifier getTextureResource(PodGeoRenderState renderState) {
        return renderState.appearance.variant().hotmask();
    }

    @Override
    protected @Nullable RenderType getRenderType(PodGeoRenderState renderState) {
        return draws(renderState) ? super.getRenderType(renderState) : null;
    }
}
