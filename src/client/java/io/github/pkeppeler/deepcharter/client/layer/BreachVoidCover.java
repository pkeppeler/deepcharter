package io.github.pkeppeler.deepcharter.client.layer;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.layer.LayerChain;

/**
 * Interim cover for the void under a broken breach crust (#333). The bottom of a layer, and of the surface, has nothing below it, so
 * a hole through the crust showed the fog or sky colour (lifted to full brightness by night vision). This draws one black square
 * under the whole bottom of the world, so every hole shows darkness from any angle and under any effect.
 *
 * <p>It draws nothing in a world outside the layer chain, and nothing for a camera under the square. Remove it with the tall world ({@code docs/design/mechanics.md}, #214 and #215), where the breach is physical crust.
 */
public final class BreachVoidCover {
	private static final int BLACK = 0xFF000000; // colour-ok: the cover is black by definition, it hides the void, and no theme restyles darkness
	private static final int CHUNK = 16;
	/**
	 * How far under the bottom of the world the square lies. A crossing fires when a pod's feet pass the bottom, and a pod falls
	 * under 4 blocks a tick at most, so the square stays clear of a pod, its particles and the fade until the crossing has happened.
	 */
	private static final float DEPTH = 8f;

	private BreachVoidCover() {
	}

	public static void init() {
		LevelRenderEvents.COLLECT_SUBMITS.register(BreachVoidCover::submit);
	}

	/** A world of the surface or a layer; a dimension type that is not a registry entry (a direct holder) is not one. */
	private static boolean inLayerChain(ClientLevel level) {
		return level.dimensionTypeRegistration().unwrapKey().map(key -> LayerChain.indexOf(key.identifier()).isPresent()).orElse(false);
	}

	private static void submit(LevelRenderContext context) {
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = client.level;
		if (level == null || !inLayerChain(level)) {
			return;
		}
		Vec3 camera = context.levelState().cameraRenderState.pos;
		float cover = level.getMinY() - DEPTH;
		if (camera.y <= cover) {
			return;
		}
		// Camera-relative, as the pose stack is: the square reaches as far as the world is drawn.
		float reach = client.options.getEffectiveRenderDistance() * CHUNK;
		float y = (float) (cover - camera.y);
		context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.debugQuads(), (pose, vertices) -> {
			vertices.addVertex(pose, -reach, y, -reach).setColor(BLACK);
			vertices.addVertex(pose, -reach, y, reach).setColor(BLACK);
			vertices.addVertex(pose, reach, y, reach).setColor(BLACK);
			vertices.addVertex(pose, reach, y, -reach).setColor(BLACK);
		});
	}
}
