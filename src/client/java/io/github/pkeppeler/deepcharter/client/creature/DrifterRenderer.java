package io.github.pkeppeler.deepcharter.client.creature;

import com.geckolib.renderer.GeoReplacedEntityRenderer;
import io.github.pkeppeler.deepcharter.creature.Drifter;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/** Draws a drifter as its form's model, translucent with a glow of its own. */
public final class DrifterRenderer extends GeoReplacedEntityRenderer<DrifterAnimatable, Drifter, DrifterRenderState> {
    /** Blocks the tendrils of a jellyfish hang below, and a chain trails, past the hitbox. */
    private static final double CULL_MARGIN_BLOCKS = 3.0;

    public DrifterRenderer(EntityRendererProvider.Context context) {
        super(context, checkedModel(context), new DrifterAnimatable());
        shadowRadius = 0f;
        withRenderLayer(new DrifterGlowLayer(this));
    }

    private static DrifterModel checkedModel(EntityRendererProvider.Context context) {
        DrifterAssets.check(context.getResourceManager());
        return new DrifterModel();
    }

    @Override
    public DrifterRenderState createRenderState(DrifterAnimatable animatable, Drifter drifter) {
        return new DrifterRenderState();
    }

    @Override
    public void extractRenderState(Drifter drifter, DrifterRenderState state, float partialTick) {
        state.drawn = drifter.isDrawn();
        state.variant = drifter.variant();
        super.extractRenderState(drifter, state, partialTick);
    }

    @Override
    public AABB getBoundingBoxForCulling(Drifter drifter, float partialTick) {
        return super.getBoundingBoxForCulling(drifter, partialTick).inflate(CULL_MARGIN_BLOCKS);
    }

    /** No red flash when it is hurt: it is a bladder, not a body. */
    @Override
    public int getPackedOverlay(DrifterAnimatable animatable, Drifter drifter, float u, float partialTick) {
        return OverlayTexture.NO_OVERLAY;
    }

    /** The skin is see-through, whichever form: the core shows inside it. */
    @Override
    public @Nullable RenderType getRenderType(DrifterRenderState state, Identifier texture) {
        return RenderTypes.entityTranslucent(texture);
    }
}
