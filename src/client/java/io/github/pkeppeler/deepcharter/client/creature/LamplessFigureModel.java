package io.github.pkeppeler.deepcharter.client.creature;

import com.geckolib.model.GeoModel;
import com.geckolib.renderer.base.GeoRenderState;
import net.minecraft.resources.Identifier;

final class LamplessFigureModel extends GeoModel<LamplessFigureAnimatable> {
    @Override
    public Identifier getModelResource(GeoRenderState renderState) {
        return LamplessFigureAssets.resource();
    }

    @Override
    public Identifier getTextureResource(GeoRenderState renderState) {
        return LamplessFigureAssets.texture();
    }

    @Override
    public Identifier getAnimationResource(LamplessFigureAnimatable animatable) {
        return LamplessFigureAssets.resource();
    }
}
