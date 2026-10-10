package io.github.pkeppeler.deepcharter.client.creature;

import com.geckolib.renderer.GeoReplacedEntityRenderer;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;

import io.github.pkeppeler.deepcharter.creature.LamplessFigure;

/**
 * Draws the lampless figure as one of the concepts of #250, with GeckoLib. It replaces {@link LamplessFigureRenderer} only while the dev
 * switch {@link FigureConcept#PROPERTY} names a concept. Behaviour is the figure's own, untouched: it walks, and it fades as it always did,
 * here by going see-through as the placeholder does.
 */
public class FigureConceptRenderer extends GeoReplacedEntityRenderer<FigureConceptAnimatable, LamplessFigure, FigureConceptRenderState> {
	private static final float SHADOW_RADIUS = 0.4f;

	private final FigureConcept concept;

	public FigureConceptRenderer(EntityRendererProvider.Context context, FigureConcept concept) {
		super(context, new FigureConceptModel(concept.checked(context.getResourceManager())), new FigureConceptAnimatable());
		this.concept = concept;
		shadowRadius = SHADOW_RADIUS;
	}

	public FigureConcept concept() {
		return concept;
	}

	@Override
	public FigureConceptRenderState createRenderState(FigureConceptAnimatable animatable, LamplessFigure figure) {
		return new FigureConceptRenderState();
	}

	@Override
	public void addRenderData(FigureConceptAnimatable animatable, LamplessFigure figure, FigureConceptRenderState state, float partialTick) {
		state.fade = figure.fadeFraction();
	}

	/** The box the game culls the figure by: a concept is taller than the figure's hitbox, and its arms and lean reach past it. */
	@Override
	public AABB getBoundingBoxForCulling(LamplessFigure figure, float partialTick) {
		double x = figure.getX();
		double z = figure.getZ();
		return super.getBoundingBoxForCulling(figure, partialTick).minmax(new AABB(
				x - FigureConcept.CULL_REACH_BLOCKS, figure.getY(), z - FigureConcept.CULL_REACH_BLOCKS,
				x + FigureConcept.CULL_REACH_BLOCKS, figure.getY() + FigureConcept.CULL_HEIGHT_BLOCKS, z + FigureConcept.CULL_REACH_BLOCKS));
	}

	@Override
	public RenderType getRenderType(FigureConceptRenderState state, Identifier texture) {
		return state.fade > 0 ? RenderTypes.entityTranslucent(texture) : super.getRenderType(state, texture);
	}

	@Override
	public int getRenderColor(FigureConceptAnimatable animatable, LamplessFigure figure, float partialTick) {
		return ARGB.white(1f - figure.fadeFraction());
	}
}
