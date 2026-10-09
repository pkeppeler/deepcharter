package io.github.pkeppeler.deepcharter.client.pod;

import com.geckolib.model.GeoModel;
import com.geckolib.renderer.base.GeoRenderState;

import net.minecraft.resources.Identifier;

/**
 * Tells GeckoLib which model and texture a pod frame uses: the model its {@link PodLook} names and the texture of the variant the
 * frame shows (intact or wreck). There is no animation file: the bones move by role, in {@link PodGeoRenderer}.
 */
final class PodGeckoModel extends GeoModel<PodGeoAnimatable> {
	private final PodLook look;

	PodGeckoModel(PodLook look) {
		this.look = look;
	}

	@Override
	public Identifier getModelResource(GeoRenderState renderState) {
		return look.model();
	}

	@Override
	public Identifier getTextureResource(GeoRenderState renderState) {
		return ((PodGeoRenderState) renderState).appearance.variant().texture();
	}

	@Override
	public Identifier getAnimationResource(PodGeoAnimatable animatable) {
		// Pods have no controllers, so GeckoLib never opens this. It is the id a pack would give an animation file.
		return look.model();
	}
}
