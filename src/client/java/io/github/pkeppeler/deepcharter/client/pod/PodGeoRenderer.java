package io.github.pkeppeler.deepcharter.client.pod;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;

import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;

/**
 * Draws the Mole as a {@link PodConcept}: a Bedrock geometry baked into vanilla ModelParts (ADR 0030's fallback path), its bones
 * posed by role, and its glowmask drawn full bright over it while the lamps are on. Used only when the dev switch picks a concept.
 */
public class PodGeoRenderer extends EntityRenderer<PodEntity, PodGeoRenderState> {
	/** How far the hull shakes while the drill bites, in blocks. */
	private static final float SHAKE = 0.02f;
	/** Vanilla's lift for a ModelPart model, whose floor is at y 24. */
	private static final float MODEL_FLOOR = 1.501f;

	private final PodConcept concept;
	private final PodGeoModel model;
	// Weak, so a pod that leaves the level takes its animation with it.
	private final Map<PodEntity, PodMotion> motions = new WeakHashMap<>();

	public PodGeoRenderer(EntityRendererProvider.Context context, PodConcept concept) {
		super(context);
		shadowRadius = Chassis.MOLE.width() / 2;
		this.concept = concept;
		ResourceManager resources = context.getResourceManager();
		GeoModel geo = read(resources, concept.model());
		checkTexture(resources, geo, concept.texture());
		checkTexture(resources, geo, concept.glowmask());
		model = new PodGeoModel(geo);
	}

	public PodConcept concept() {
		return concept;
	}

	private static GeoModel read(ResourceManager resources, Identifier id) {
		Resource resource = resources.getResource(id).orElseThrow(() -> new IllegalStateException("No pod model at " + id));
		try (Reader reader = resource.openAsReader()) {
			return GeoModel.parse(id.toString(), reader);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read the pod model " + id, e);
		}
	}

	/** The texture is there and fits the model's UV, or this throws naming both: a missing texture would draw magenta, silently. */
	private static void checkTexture(ResourceManager resources, GeoModel geo, Identifier texture) {
		Resource resource = resources.getResource(texture)
				.orElseThrow(() -> new IllegalStateException("No texture " + texture + " for the pod model " + geo.source()));
		try (InputStream png = resource.open()) {
			geo.checkTexture(texture.toString(), png);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read the texture " + texture + " of the pod model " + geo.source(), e);
		}
	}

	@Override
	public PodGeoRenderState createRenderState() {
		return new PodGeoRenderState();
	}

	@Override
	public void extractRenderState(PodEntity pod, PodGeoRenderState state, float partialTick) {
		super.extractRenderState(pod, state, partialTick);
		motions.computeIfAbsent(pod, ignored -> new PodMotion()).advance(pod, state, model.mountRestPitch(), model.drillSpinScale());
	}

	@Override
	public void submit(PodGeoRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		poseStack.pushPose();
		poseStack.rotateDegrees(Axis.YP, 180f - state.heading);
		if (state.drilling) {
			poseStack.translate(SHAKE * Mth.sin(state.ageInTicks * 7.3f), SHAKE * Mth.sin(state.ageInTicks * 9.1f), 0f);
		}
		poseStack.scale(-1f, -1f, 1f);
		poseStack.translate(0f, -MODEL_FLOOR, 0f);
		collector.submitModel(model, state, poseStack, RenderTypes.entityCutout(concept.texture()), state.lightCoords, OverlayTexture.NO_OVERLAY,
				state.outlineColor);
		if (state.lit) {
			collector.order(1).submitModel(model, state, poseStack, RenderTypes.eyes(concept.glowmask()), state.lightCoords,
					OverlayTexture.NO_OVERLAY, state.outlineColor);
		}
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
