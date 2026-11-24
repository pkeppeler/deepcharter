package io.github.pkeppeler.deepcharter.client.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import java.util.Arrays;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

/**
 * Interim cover for the void under a broken breach crust (#333). The bottom of a layer, and of the surface, has nothing below it, so
 * a hole through the crust showed the fog or sky colour (lifted to full brightness by night vision). This draws one black floor
 * under the whole bottom of the world, so every hole shows darkness from any angle and under any effect.
 *
 * <p>A deep shaft (#1188): the floor lies no deeper than the sections the client draws under the camera, and takes the render-distance
 * fog the terrain shader gives a block. Past the drawn sections a straight-down view showed the sky, with the floor a small black square
 * far under it, while the shaft's walls faded into the fog at their drawn ends. Every point of a floor that deep is past the
 * render-distance fog's end, so it is the fog colour throughout, as the walls' ends are. Nearer the camera it stays black.
 *
 * <p>It draws nothing in a world outside the layer chain, and nothing for a camera under the floor. Remove it with the tall world ({@code docs/design/mechanics.md}, #214 and #215), where the breach is physical crust.
 */
public final class BreachVoidCover {
    /**
     * How far under the bottom of the world the floor lies. A crossing fires when a pod's feet pass the bottom, and a pod falls
     * under 4 blocks a tick at most, so the floor stays clear of a pod, its particles and the fade until the crossing has happened.
     */
    private static final float DEPTH = 8f;
    /**
     * The floor reaches this share of the camera's far plane out from under it, so its rim stays inside the far plane (the client's far
     * plane is at least four times the render distance, and a floor under the drawn sections lies at most two sections past it).
     */
    private static final float REACH_OF_FAR = 0.6f;
    /** The floor is a disc of rings, each cut into this many quads round the camera. */
    private static final int SEGMENTS = 64;

    private BreachVoidCover() {}

    public static void init() {
        LevelRenderEvents.COLLECT_SUBMITS.register(BreachVoidCover::submit);
    }

    /**
     * A world of the surface or a layer, or the campaign world, whose bottom is below its last layer; a dimension type that is not a
     * registry entry (a direct holder) is not one.
     */
    private static boolean inLayerChain(ClientLevel level) {
        return level.dimensionTypeRegistration().unwrapKey().isPresent()
                && (LayerChain.isCampaign(level)
                        || LayerChain.bandAt(level, level.getMinY()).isPresent());
    }

    private static void submit(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null || !inLayerChain(level)) {
            return;
        }
        CameraRenderState camera = context.levelState().cameraRenderState;
        double eye = camera.pos.y;
        float cover = Math.max(level.getMinY() - DEPTH, undrawnBelow(eye, client.options.getEffectiveRenderDistance()));
        if (eye <= cover) {
            return;
        }
        // Camera-relative, as the pose stack is.
        float y = (float) (cover - eye);
        FogData fog = camera.fogData;
        float[] radii = radii(-y, camera.depthFar * REACH_OF_FAR, fog);
        int[] colours = new int[radii.length];
        for (int i = 0; i < radii.length; i++) {
            colours[i] = fogged(fog, Math.max(radii[i], -y));
        }
        context.submitNodeCollector()
                .submitCustomGeometry(context.poseStack(), RenderTypes.debugQuads(), (pose, vertices) -> {
                    for (int i = 0; i + 1 < radii.length; i++) {
                        for (int k = 0; k < SEGMENTS; k++) {
                            float a0 = Mth.TWO_PI * k / SEGMENTS;
                            float a1 = Mth.TWO_PI * (k + 1) / SEGMENTS;
                            ring(vertices, pose, radii[i], a0, y, colours[i]);
                            ring(vertices, pose, radii[i], a1, y, colours[i]);
                            ring(vertices, pose, radii[i + 1], a1, y, colours[i + 1]);
                            ring(vertices, pose, radii[i + 1], a0, y, colours[i + 1]);
                        }
                    }
                });
    }

    private static void ring(
            VertexConsumer vertices, PoseStack.Pose pose, float radius, float angle, float y, int colour) {
        vertices.addVertex(pose, radius * Mth.cos(angle), y, radius * Mth.sin(angle))
                .setColor(colour);
    }

    /**
     * The bottom of the sections the client draws under a camera at {@code eye}, less one section to spare: it draws the sections up to
     * the render distance above and below the camera's ({@code SectionOcclusionGraph.getRelativeFrom}), and nothing under them.
     */
    private static float undrawnBelow(double eye, int renderDistance) {
        return SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(eye) - renderDistance - 1);
    }

    /**
     * The radii of the floor's rings, out to {@code reach}: under the camera, at the drop (inside it the cylindrical distance is the drop),
     * and where the render-distance fog starts and ends. Between two rings the fog changes linearly with the radius, so a vertex colour
     * per ring gives the fog the terrain shader would.
     */
    private static float[] radii(float drop, float reach, FogData fog) {
        float[] wanted = {0f, drop, fog.renderDistanceStart, fog.renderDistanceEnd, reach};
        float[] radii = new float[wanted.length];
        int count = 0;
        Arrays.sort(wanted);
        for (float radius : wanted) {
            if (radius >= 0f && radius <= reach && (count == 0 || radius > radii[count - 1])) {
                radii[count++] = radius;
            }
        }
        return Arrays.copyOf(radii, count);
    }

    /**
     * Black, fogged by the render-distance fog at cylindrical distance {@code distance}, as the terrain shader fogs a vertex there
     * ({@code fog.glsl}). The environmental fog is left out: it would lift the void under a hole near the camera towards the fog colour,
     * which is what the cover hides (#333).
     */
    private static int fogged(FogData fog, float distance) {
        float amount = linear(distance, fog.renderDistanceStart, fog.renderDistanceEnd) * fog.color.w();
        return ARGB.colorFromFloat(1f, fog.color.x() * amount, fog.color.y() * amount, fog.color.z() * amount);
    }

    private static float linear(float distance, float start, float end) {
        if (distance <= start) {
            return 0f;
        }
        if (distance >= end) {
            return 1f;
        }
        return (distance - start) / (end - start);
    }
}
