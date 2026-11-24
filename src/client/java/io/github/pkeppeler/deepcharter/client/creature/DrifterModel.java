package io.github.pkeppeler.deepcharter.client.creature;

import com.geckolib.model.GeoModel;
import com.geckolib.renderer.base.GeoRenderState;
import io.github.pkeppeler.deepcharter.creature.DrifterVariant;
import java.util.Arrays;
import net.minecraft.resources.Identifier;

/** Picks the model and the texture by the form in the render state. */
final class DrifterModel extends GeoModel<DrifterAnimatable> {
    @Override
    public Identifier getModelResource(GeoRenderState renderState) {
        return DrifterAssets.resource(DrifterRenderState.variantOf(renderState));
    }

    @Override
    public Identifier getTextureResource(GeoRenderState renderState) {
        return DrifterAssets.texture(DrifterRenderState.variantOf(renderState));
    }

    /**
     * One stand-in animates every drifter, so the animation file cannot follow the form. The names do: each file holds only the
     * animations named for its form ({@link DrifterAssets#idle}), and GeckoLib looks a name up in this file and then in the fallbacks.
     */
    @Override
    public Identifier getAnimationResource(DrifterAnimatable animatable) {
        return DrifterAssets.resource(DrifterVariant.values()[0]);
    }

    @Override
    public Identifier[] getAnimationResourceFallbacks(DrifterAnimatable animatable) {
        return Arrays.stream(DrifterVariant.values())
                .skip(1)
                .map(DrifterAssets::resource)
                .toArray(Identifier[]::new);
    }
}
