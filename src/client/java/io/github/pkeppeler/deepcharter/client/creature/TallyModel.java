package io.github.pkeppeler.deepcharter.client.creature;

import com.geckolib.model.GeoModel;
import com.geckolib.renderer.base.GeoRenderState;
import net.minecraft.resources.Identifier;

final class TallyModel extends GeoModel<TallyAnimatable> {
    @Override
    public Identifier getModelResource(GeoRenderState renderState) {
        return TallyAssets.resource();
    }

    @Override
    public Identifier getTextureResource(GeoRenderState renderState) {
        return TallyAssets.texture();
    }

    @Override
    public Identifier getAnimationResource(TallyAnimatable animatable) {
        return TallyAssets.resource();
    }
}
