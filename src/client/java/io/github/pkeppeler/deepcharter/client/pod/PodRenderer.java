package io.github.pkeppeler.deepcharter.client.pod;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * Draws a pod from resource-pack models (ADR 0033), so a pack changes the look with no Java. Each model is asked for through an
 * item model definition ({@link PodSkins}), the same way vanilla draws a held item. Models are authored at true size, centred on
 * the entity in x and z with the floor at y 0, so nothing here scales them. The drill is its own model, turned about its
 * axis while the pod drills.
 */
public class PodRenderer extends EntityRenderer<PodEntity, PodRenderState> {
	/** The pivot the drill turns about, in blocks from the entity's feet: the middle of the hull, as the pod models are authored. */
	private static final float DRILL_PIVOT_Y = 0.45f;
	private static final float DRILL_DEGREES_PER_TICK = 45f;

	private final ItemModelResolver itemModelResolver;
	private final PodSkins skins;
	// Built on first use: item components are not bound yet while renderers are created.
	private ItemStack hull;
	private ItemStack wreck;
	private ItemStack drill;

	public PodRenderer(EntityRendererProvider.Context context, Chassis chassis) {
		super(context);
		shadowRadius = chassis.width() / 2;
		itemModelResolver = context.getItemModelResolver();
		skins = PodSkins.of(chassis);
	}

	/** A stack whose only job is to name a model: any item will do, as the item model component overrides its own. */
	private static ItemStack modelStack(Identifier model) {
		ItemStack stack = new ItemStack(Items.STONE);
		stack.set(DataComponents.ITEM_MODEL, model);
		return stack;
	}

	@Override
	public PodRenderState createRenderState() {
		return new PodRenderState();
	}

	@Override
	public void extractRenderState(PodEntity pod, PodRenderState state, float partialTick) {
		super.extractRenderState(pod, state, partialTick);
		if (hull == null) {
			hull = modelStack(skins.hull());
			wreck = modelStack(skins.wreck());
			drill = modelStack(skins.drill());
		}
		boolean wrecked = Wrecks.isWreck(pod);
		itemModelResolver.updateForNonLiving(state.hull, wrecked ? wreck : hull, ItemDisplayContext.NONE, pod);
		if (wrecked) {
			state.drill.clear();
		} else {
			itemModelResolver.updateForNonLiving(state.drill, drill, ItemDisplayContext.NONE, pod);
		}
		state.drilling = pod.drilling();
		state.drillDirection = pod.drillDirection();
	}

	@Override
	public void submit(PodRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		// The models are authored in block space, where the entity stands at the middle of the block.
		poseStack.pushPose();
		poseStack.translate(-0.5, 0, -0.5);
		submitPart(state.hull, state, poseStack, collector);
		if (!state.drill.isEmpty()) {
			poseStack.pushPose();
			poseStack.translate(0.5, DRILL_PIVOT_Y, 0.5);
			aim(poseStack, state.drillDirection);
			if (state.drilling) {
				poseStack.rotateDegrees(Axis.YP, state.ageInTicks * DRILL_DEGREES_PER_TICK);
			}
			poseStack.translate(-0.5, -DRILL_PIVOT_Y, -0.5);
			submitPart(state.drill, state, poseStack, collector);
			poseStack.popPose();
		}
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}

	private static void submitPart(ItemStackRenderState part, PodRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
		if (!part.isEmpty()) {
			// Item rendering centres a model on the origin (it moves it by -0.5 on each axis); undo that, as the models are
			// authored in block space.
			poseStack.pushPose();
			poseStack.translate(0.5, 0.5, 0.5);
			part.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
			poseStack.popPose();
		}
	}

	/** Turns the drill model, which is authored pointing down, to point along {@code direction}. */
	private static void aim(PoseStack poseStack, Direction direction) {
		switch (direction) {
			case DOWN -> { }
			case UP -> poseStack.rotateDegrees(Axis.XP, 180);
			case NORTH -> poseStack.rotateDegrees(Axis.XP, 90);
			case SOUTH -> poseStack.rotateDegrees(Axis.XP, -90);
			case EAST -> poseStack.rotateDegrees(Axis.ZP, 90);
			case WEST -> poseStack.rotateDegrees(Axis.ZP, -90);
		}
	}
}
