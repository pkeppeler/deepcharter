package io.github.pkeppeler.deepcharter.client.pod;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;

/**
 * Placeholder pod model: a slab of raw copper block under the rider, as wide as the hitbox. It follows
 * vanilla's TntRenderer: extract copies the entity into a render state, submit draws that state.
 * The real model replaces this.
 */
public class PodRenderer extends EntityRenderer<PodEntity, PodRenderState> {
	private static final BlockDisplayContext DISPLAY_CONTEXT = BlockDisplayContext.create();
	private static final BlockState HULL_BLOCK = Blocks.RAW_COPPER_BLOCK.defaultBlockState();
	/** The slab is as tall as the seat is high, so the rider sits on it. */
	private static final float SLAB_HEIGHT = 0.9f;

	private final BlockModelResolver blockModelResolver;

	public PodRenderer(EntityRendererProvider.Context context) {
		super(context);
		shadowRadius = Chassis.MOLE.width() / 2;
		blockModelResolver = context.getBlockModelResolver();
	}

	@Override
	public PodRenderState createRenderState() {
		return new PodRenderState();
	}

	@Override
	public void extractRenderState(PodEntity pod, PodRenderState state, float partialTick) {
		super.extractRenderState(pod, state, partialTick);
		blockModelResolver.update(state.hull, HULL_BLOCK, DISPLAY_CONTEXT);
	}

	@Override
	public void submit(PodRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		poseStack.pushPose();
		float width = Chassis.MOLE.width();
		poseStack.translate(-width / 2, 0, -width / 2);
		poseStack.scale(width, SLAB_HEIGHT, width);
		if (!state.hull.isEmpty()) {
			state.hull.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
		}
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
