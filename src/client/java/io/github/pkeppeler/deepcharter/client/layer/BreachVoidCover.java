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
 * over the whole bottom of the world, seen only from above, so every hole shows darkness from any angle and under any effect.
 * It is drawn flat and unfogged, so distance does not fade it into the fog colour.
 *
 * <p>It draws nothing in a world outside the layer chain, and nothing for a camera under the bottom, so a crossing is not
 * covered. Remove it with the tall world ({@code docs/design/mechanics.md}, #214 and #215), where the breach is physical crust.
 */
public final class BreachVoidCover {
	private static final int BLACK = 0xFF000000; // colour-ok: the cover is black by definition, it hides the void, and no theme restyles darkness
	private static final int CHUNK = 16;

	private BreachVoidCover() {
	}

	public static void init() {
		LevelRenderEvents.COLLECT_SUBMITS.register(BreachVoidCover::submit);
	}

	private static void submit(LevelRenderContext context) {
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = client.level;
		if (level == null || LayerChain.indexOf(level.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier()).isEmpty()) {
			return;
		}
		Vec3 camera = context.levelState().cameraRenderState.pos;
		float bottom = level.getMinY();
		if (camera.y <= bottom) {
			return;
		}
		// Camera-relative, as the pose stack is: the square reaches as far as the world is drawn.
		float reach = client.options.getEffectiveRenderDistance() * CHUNK;
		float y = (float) (bottom - camera.y);
		context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.debugQuads(), (pose, vertices) -> {
			vertices.addVertex(pose, -reach, y, -reach).setColor(BLACK);
			vertices.addVertex(pose, -reach, y, reach).setColor(BLACK);
			vertices.addVertex(pose, reach, y, reach).setColor(BLACK);
			vertices.addVertex(pose, reach, y, -reach).setColor(BLACK);
		});
	}
}
