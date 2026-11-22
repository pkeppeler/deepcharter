package io.github.pkeppeler.deepcharter.client.creature;

import com.geckolib.renderer.GeoReplacedEntityRenderer;
import com.geckolib.renderer.base.GeoRenderState;
import io.github.pkeppeler.deepcharter.creature.Tally;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/** Draws a Tally from the model, texture and animations of {@link TallyAssets}; a broken one comes apart by going see-through. */
public final class TallyRenderer extends GeoReplacedEntityRenderer<TallyAnimatable, Tally, TallyRenderState> {
    public static final float SHADOW_RADIUS = 0.2f;

    public TallyRenderer(EntityRendererProvider.Context context) {
        super(context, checkedModel(context), new TallyAnimatable());
        shadowRadius = SHADOW_RADIUS;
        withRenderLayer(new TallyGlowLayer(this));
    }

    private static TallyModel checkedModel(EntityRendererProvider.Context context) {
        TallyAssets.check(context.getResourceManager());
        return new TallyModel();
    }

    @Override
    public TallyRenderState createRenderState(TallyAnimatable animatable, Tally tally) {
        return new TallyRenderState();
    }

    /** The fade is read before the base class extracts the shadow, which asks for {@link #getShadowRadius}. */
    @Override
    public void extractRenderState(Tally tally, TallyRenderState state, float partialTick) {
        state.fade = tally.fadeFraction();
        state.clamped = tally.isClamped();
        super.extractRenderState(tally, state, partialTick);
    }

    @Override
    protected float getShadowRadius(TallyRenderState state) {
        return super.getShadowRadius(state) * (1f - state.fade);
    }

    /** The wheel and the clamps reach past the hitbox. */
    @Override
    public AABB getBoundingBoxForCulling(Tally tally, float partialTick) {
        double x = tally.getX();
        double z = tally.getZ();
        return super.getBoundingBoxForCulling(tally, partialTick)
                .minmax(new AABB(
                        x - TallyAssets.CULL_REACH_BLOCKS,
                        tally.getY(),
                        z - TallyAssets.CULL_REACH_BLOCKS,
                        x + TallyAssets.CULL_REACH_BLOCKS,
                        tally.getY() + TallyAssets.CULL_HEIGHT_BLOCKS,
                        z + TallyAssets.CULL_REACH_BLOCKS));
    }

    /** A broken Tally comes apart where it hangs: it does not tip over. */
    @Override
    protected float getDeathMaxRotation(GeoRenderState renderState) {
        return 0f;
    }

    /** No red flash when it is hurt or dies: the tone is dread, not blood. A hit shows as sparks and a sound instead. */
    @Override
    public int getPackedOverlay(TallyAnimatable animatable, Tally tally, float u, float partialTick) {
        return OverlayTexture.NO_OVERLAY;
    }

    @Override
    public @Nullable RenderType getRenderType(TallyRenderState state, Identifier texture) {
        return state.fade > 0 ? RenderTypes.entityTranslucent(texture) : super.getRenderType(state, texture);
    }

    @Override
    public int getRenderColor(TallyAnimatable animatable, Tally tally, float partialTick) {
        return ARGB.white(1f - tally.fadeFraction());
    }
}
