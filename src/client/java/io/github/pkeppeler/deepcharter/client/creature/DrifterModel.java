package io.github.pkeppeler.deepcharter.client.creature;

import com.geckolib.model.GeoModel;
import com.geckolib.renderer.base.GeoRenderState;
import net.minecraft.resources.Identifier;

final class DrifterModel extends GeoModel<DrifterAnimatable> {
    @Override
    public Identifier getModelResource(GeoRenderState renderState) {
        return DrifterAssets.resource();
    }

    @Override
    public Identifier getTextureResource(GeoRenderState renderState) {
        return DrifterAssets.texture();
    }

    @Override
    public Identifier getAnimationResource(DrifterAnimatable animatable) {
        return DrifterAssets.resource();
    }
}
