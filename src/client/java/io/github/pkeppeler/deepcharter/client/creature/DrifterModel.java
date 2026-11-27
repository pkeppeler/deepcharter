package io.github.pkeppeler.deepcharter.client.creature;

import com.geckolib.model.GeoModel;
import com.geckolib.renderer.base.GeoRenderState;
import io.github.pkeppeler.deepcharter.creature.DrifterVariant;
import java.util.Arrays;
import net.minecraft.resources.Identifier;

/** Picks the model and the texture by the form in the render state. */
final class DrifterModel extends GeoModel<DrifterAnimatable> {
    private static final Identifier PRIMARY = DrifterAssets.resource(DrifterVariant.JELLYFISH);
    private static final Identifier[] FALLBACKS = Arrays.stream(DrifterVariant.values())
            .filter(variant -> variant != DrifterVariant.JELLYFISH)
            .map(DrifterAssets::resource)
            .toArray(Identifier[]::new);

    @Override
    public Identifier getModelResource(GeoRenderState renderState) {
        return DrifterAssets.resource(DrifterRenderState.variantOf(renderState));
    }

    @Override
    public Identifier getTextureResource(GeoRenderState renderState) {
        return DrifterAssets.texture(DrifterRenderState.variantOf(renderState));
    }

    /**
     * One stand-in animates every form, so the file cannot follow it; the animation names do, and GeckoLib tries the
     * fallbacks for them.
     */
    @Override
    public Identifier getAnimationResource(DrifterAnimatable animatable) {
        return PRIMARY;
    }

    @Override
    public Identifier[] getAnimationResourceFallbacks(DrifterAnimatable animatable) {
        return FALLBACKS;
    }
}
