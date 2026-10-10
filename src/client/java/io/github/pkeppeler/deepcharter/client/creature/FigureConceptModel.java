package io.github.pkeppeler.deepcharter.client.creature;

import com.geckolib.model.GeoModel;
import com.geckolib.renderer.base.GeoRenderState;

import net.minecraft.resources.Identifier;

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
