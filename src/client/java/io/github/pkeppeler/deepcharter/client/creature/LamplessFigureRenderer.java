package io.github.pkeppeler.deepcharter.client.creature;

import com.geckolib.renderer.GeoReplacedEntityRenderer;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;

import io.github.pkeppeler.deepcharter.creature.LamplessFigure;

/** Draws the lampless figure, the Reacher of #250, from the model, texture and animations of {@link LamplessFigureAssets}; it fades by going see-through. */
public class LamplessFigureRenderer extends GeoReplacedEntityRenderer<LamplessFigureAnimatable, LamplessFigure, LamplessFigureRenderState> {
	public static final float SHADOW_RADIUS = 0.4f;

	public LamplessFigureRenderer(EntityRendererProvider.Context context) {
		super(context, checkedModel(context), new LamplessFigureAnimatable());
		shadowRadius = SHADOW_RADIUS;
	}

	private static LamplessFigureModel checkedModel(EntityRendererProvider.Context context) {
		LamplessFigureAssets.check(context.getResourceManager());
		return new LamplessFigureModel();
	}

	@Override
	public LamplessFigureRenderState createRenderState(LamplessFigureAnimatable animatable, LamplessFigure figure) {
		return new LamplessFigureRenderState();
	}

	/** The fade is read before the base class extracts the shadow, which asks for {@link #getShadowRadius}. */
	@Override
	public void extractRenderState(LamplessFigure figure, LamplessFigureRenderState state, float partialTick) {
		state.fade = figure.fadeFraction();
		super.extractRenderState(figure, state, partialTick);
	}

	/** The shadow goes with the body. */
	@Override
	protected float getShadowRadius(LamplessFigureRenderState state) {
		return super.getShadowRadius(state) * (1f - state.fade);
	}

	/** The figure is taller than its hitbox, and its arms and stoop reach past it. */
	@Override
	public AABB getBoundingBoxForCulling(LamplessFigure figure, float partialTick) {
		double x = figure.getX();
		double z = figure.getZ();
		return super.getBoundingBoxForCulling(figure, partialTick).minmax(new AABB(
				x - LamplessFigureAssets.CULL_REACH_BLOCKS, figure.getY(), z - LamplessFigureAssets.CULL_REACH_BLOCKS,
				x + LamplessFigureAssets.CULL_REACH_BLOCKS, figure.getY() + LamplessFigureAssets.CULL_HEIGHT_BLOCKS, z + LamplessFigureAssets.CULL_REACH_BLOCKS));
	}

	@Override
	public RenderType getRenderType(LamplessFigureRenderState state, Identifier texture) {
		return state.fade > 0 ? RenderTypes.entityTranslucent(texture) : super.getRenderType(state, texture);
	}

	@Override
	public int getRenderColor(LamplessFigureAnimatable animatable, LamplessFigure figure, float partialTick) {
		return ARGB.white(1f - figure.fadeFraction());
	}
}
