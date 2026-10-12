package io.github.pkeppeler.deepcharter.client.pod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.TowChainLook;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.pod.TowTuning;

/**
 * Draws the tow line (#508): a chain of links along a hanging curve from the tower to the pod it tows, like vanilla's leash but with
 * links from {@code textures/item/tow_chain.png}. The cable is the attachment on the towed pod ({@link PodTowing#STATE}), synced to
 * every client, so the line follows the cable across chunk reloads and breach crossings with no state of its own: each frame it reads
 * the two pods' places and builds the curve ({@link TowChain}). Its look is the {@code tow} theme area, so a pack restyles it on F3+T.
 *
 * <p>Nothing here allocates per frame beyond the one render node vanilla makes for a submit: the curve, the lights and the geometry
 * callback are reused for each towed pod.
 */
public final class TowChainRenderer {
	/** The links' texture: a face-on link in the left half and the same link edge-on in the right half. */
	static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "textures/item/tow_chain.png");
	/** Blocks from the camera beyond which a line is not drawn: well past where a pod's hull is anything but a dot. */
	private static final double DRAW_DISTANCE = 160.0;

	/** The chain of each towed pod, kept while the pod is. */
	private static final Map<PodEntity, Chain> CHAINS = Collections.synchronizedMap(new WeakHashMap<>());
	private static final List<PodEntity> PODS = new ArrayList<>();
	private static final BlockPos.MutableBlockPos LIGHT_AT = new BlockPos.MutableBlockPos();

	private TowChainRenderer() {
	}

	/** A towed pod's line, ready to draw: its curve, the light of each link, and where the camera was. */
	public static final class Chain implements CustomGeometryRenderer {
		public final TowChain curve = new TowChain();
		final int[] lights = new int[TowChain.MAX_LINKS];
		double cameraX;
		double cameraY;
		double cameraZ;
		TowChainLook look = TowChainLook.current();

		@Override
		public void render(PoseStack.Pose pose, VertexConsumer vertices) {
			TowChainLinks.emit(this, pose, vertices);
		}
	}

	public static void init() {
		LevelRenderEvents.COLLECT_SUBMITS.register(TowChainRenderer::submit);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> CHAINS.clear());
	}

	private static @Nullable RenderType renderType;

	private static RenderType renderType() {
		if (renderType == null) {
			renderType = RenderTypes.entityCutout(TEXTURE);
		}
		return renderType;
	}

	private static void submit(LevelRenderContext context) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}
		PODS.clear();
		for (Entity entity : level.entitiesForRendering()) {
			if (entity instanceof PodEntity pod && !pod.isRemoved()) {
				PODS.add(pod);
			}
		}
		var camera = context.levelState().cameraRenderState.pos;
		float partial = context.levelState().worldPartialTicks;
		for (PodEntity towed : PODS) {
			Chain chain = chainFor(level, towed, PODS, partial, camera.x, camera.y, camera.z);
			if (chain != null) {
				context.submitNodeCollector().submitCustomGeometry(context.poseStack(), renderType(), chain);
			}
		}
	}

	/**
	 * The line of {@code towed}, built for this frame, or null when the pod is not towed, its tower is not among {@code pods} (not loaded
	 * here, so the line has no far end), or the line is farther from the camera than {@link #DRAW_DISTANCE}.
	 */
	public static @Nullable Chain chainFor(ClientLevel level, PodEntity towed, List<PodEntity> pods, float partial, double cameraX, double cameraY, double cameraZ) {
		UUID towerId = PodTowing.towerId(towed).orElse(null);
		if (towerId == null) {
			return null;
		}
		PodEntity tower = null;
		for (PodEntity other : pods) {
			if (other.getUUID().equals(towerId)) {
				tower = other;
				break;
			}
		}
		if (tower == null || tower == towed) {
			return null;
		}
		double towedX = Mth.lerp(partial, towed.xo, towed.getX());
		double towedY = Mth.lerp(partial, towed.yo, towed.getY()) + towed.getBbHeight() / 2.0;
		double towedZ = Mth.lerp(partial, towed.zo, towed.getZ());
		double distanceX = towedX - cameraX;
		double distanceY = towedY - cameraY;
		double distanceZ = towedZ - cameraZ;
		if (distanceX * distanceX + distanceY * distanceY + distanceZ * distanceZ > DRAW_DISTANCE * DRAW_DISTANCE) {
			return null;
		}
		Chain chain = CHAINS.computeIfAbsent(towed, pod -> new Chain());
		TowChainLook look = TowChainLook.current();
		chain.look = look;
		chain.cameraX = cameraX;
		chain.cameraY = cameraY;
		chain.cameraZ = cameraZ;
		chain.curve.build(Mth.lerp(partial, tower.xo, tower.getX()), Mth.lerp(partial, tower.yo, tower.getY()) + tower.getBbHeight() / 2.0,
				Mth.lerp(partial, tower.zo, tower.getZ()), tower.chassis().width() / 2.0, towedX, towedY, towedZ, towed.chassis().width() / 2.0,
				TowTuning.DEFAULT.lineLength(), look.linkLength());
		TowChain curve = chain.curve;
		for (int i = 0; i < curve.links; i++) {
			LIGHT_AT.set((curve.x[i] + curve.x[i + 1]) / 2.0, (curve.y[i] + curve.y[i + 1]) / 2.0, (curve.z[i] + curve.z[i + 1]) / 2.0);
			int light = LightCoordsUtil.getLightCoords(level, LIGHT_AT);
			chain.lights[i] = LightCoordsUtil.pack(Math.max(LightCoordsUtil.block(light), look.minBlockLight()), LightCoordsUtil.sky(light));
		}
		return chain;
	}
}
