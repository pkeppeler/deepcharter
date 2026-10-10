package io.github.pkeppeler.deepcharter.client.pod;

import org.jspecify.annotations.Nullable;

import com.geckolib.renderer.base.GeoRenderer;
import com.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.pod.PodEntity;

/**
 * Draws the pod's glowmask over its texture at full bright: the lamp lenses and the cab light. GeckoLib finds the mask by name
 * (the texture's name and {@code _glowmask}). It is drawn while the pod's look says it glows: while it has power, always, or never.
 *
 * <p>GeckoLib draws it with vanilla's {@code eyes} render type on macOS and Linux and with its own emissive pipeline on Windows
 * (docs/tooling/geckolib-audit.md), so a mask is designed for {@code eyes}.
 */
final class PodGlowLayer extends AutoGlowingGeoLayer<PodGeoAnimatable, PodEntity, PodGeoRenderState> {
	PodGlowLayer(GeoRenderer<PodGeoAnimatable, PodEntity, PodGeoRenderState> renderer) {
		super(renderer);
	}

	/** The glowmask of the variant on show: a painted texture has none of its own, and the mask does not change with the paint. */
	@Override
	protected Identifier getTextureResource(PodGeoRenderState renderState) {
		return renderState.appearance.variant().glowmask();
	}

	@Override
	protected @Nullable RenderType getRenderType(PodGeoRenderState renderState) {
		return renderState.appearance.glows(renderState.lit) ? super.getRenderType(renderState) : null;
	}
}
