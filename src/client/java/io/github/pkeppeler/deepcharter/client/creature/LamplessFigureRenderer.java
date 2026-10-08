package io.github.pkeppeler.deepcharter.client.creature;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;

/** Placeholder until the creatures session (#13): a dark vanilla humanoid that goes see-through as it fades. */
public class LamplessFigureRenderer extends HumanoidMobRenderer<LamplessFigure, LamplessFigureRenderState, HumanoidModel<LamplessFigureRenderState>> {
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "textures/entity/lampless_figure.png");

	public LamplessFigureRenderer(EntityRendererProvider.Context context) {
		super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.4f);
	}

	@Override
	public LamplessFigureRenderState createRenderState() {
		return new LamplessFigureRenderState();
	}

	@Override
	public void extractRenderState(LamplessFigure figure, LamplessFigureRenderState state, float partialTick) {
		super.extractRenderState(figure, state, partialTick);
		state.fade = figure.fadeFraction();
	}

	@Override
	public Identifier getTextureLocation(LamplessFigureRenderState state) {
		return TEXTURE;
	}

	@Override
	protected RenderType getRenderType(LamplessFigureRenderState state, boolean bodyVisible, boolean translucent, boolean glowing) {
		return state.fade > 0 ? RenderTypes.entityTranslucent(TEXTURE) : super.getRenderType(state, bodyVisible, translucent, glowing);
	}

	@Override
	protected int getModelTint(LamplessFigureRenderState state) {
		return ARGB.white(1f - state.fade);
	}
}
