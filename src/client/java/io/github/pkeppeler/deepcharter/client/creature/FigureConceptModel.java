package io.github.pkeppeler.deepcharter.client.creature;

import com.geckolib.model.GeoModel;
import com.geckolib.renderer.base.GeoRenderState;

import net.minecraft.resources.Identifier;

/** Tells GeckoLib which model, texture and animation file the figure shows: the files of the concept that the dev switch names. */
final class FigureConceptModel extends GeoModel<FigureConceptAnimatable> {
	private final FigureConcept concept;

	FigureConceptModel(FigureConcept concept) {
		this.concept = concept;
	}

	@Override
	public Identifier getModelResource(GeoRenderState renderState) {
		return concept.resource();
	}

	@Override
	public Identifier getTextureResource(GeoRenderState renderState) {
		return concept.texture();
	}

	@Override
	public Identifier getAnimationResource(FigureConceptAnimatable animatable) {
		return concept.resource();
	}
}
