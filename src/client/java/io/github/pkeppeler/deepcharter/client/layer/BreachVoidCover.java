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
 * Interim cover for the void under a broken breach crust (#333): one black disc under the bottom of the world, so a hole through it
 * shows darkness, lifted to one section under the sections the client draws (#1188) and fogged by the render-distance fog as the terrain
 * is there. It draws nothing outside the layer chain or for a camera under it. Why, and when to remove it: {@code docs/design/mechanics.md},
 * "The void under a broken breach crust".
 */
public final class BreachVoidCover {
    /**
     * How far under the bottom of the world the cover lies. A crossing fires when a pod's feet pass the bottom, and a pod falls
     * under 4 blocks a tick at most, so the cover stays clear of a pod, its particles and the fade until the crossing has happened.
     */
    private static final float DEPTH = 8f;
    /**
     * The cover reaches this share of the camera's far plane out from under it, so its rim stays inside the far plane (the client's far
     * plane is at least four times the render distance, and a cover under the drawn sections lies at most two sections past it).
     */
    private static final float REACH_OF_FAR = 0.6f;
    /** The cover is a disc of rings, each cut into this many quads round the camera. */
    private static final int SEGMENTS = 64;

    /** Written by the test thread, read by the render thread. */
    private static volatile boolean drawn = true;

    private BreachVoidCover() {}

    public static void init() {
        LevelRenderEvents.COLLECT_SUBMITS.register(BreachVoidCover::submit);
    }

    // test-seam: the cover also hides the nadir past the drawn sections, so CavernSky's tour (its client test and the
    // cavern-sky scenario) turns it off to check and show the guard that holds without it, LayerSky and the zone
    // biomes' sky fog; the tour restores the value this returns.
    public static boolean setDrawn(boolean on) {
        boolean was = drawn;
        drawn = on;
        return was;
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
        if (!drawn || level == null || !inLayerChain(level)) {
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
                            vertex(vertices, pose, radii[i], a0, y, colours[i]);
                            vertex(vertices, pose, radii[i], a1, y, colours[i]);
                            vertex(vertices, pose, radii[i + 1], a1, y, colours[i + 1]);
                            vertex(vertices, pose, radii[i + 1], a0, y, colours[i + 1]);
                        }
                    }
                });
    }

    private static void vertex(
            VertexConsumer vertices, PoseStack.Pose pose, float radius, float angle, float y, int colour) {
        vertices.addVertex(pose, radius * Mth.cos(angle), y, radius * Mth.sin(angle))
                .setColor(colour);
    }

    /** One section under the lowest the client draws for a camera at {@code eye} ({@code SectionOcclusionGraph.getRelativeFrom}). */
    private static float undrawnBelow(double eye, int renderDistance) {
        return SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(eye) - renderDistance - 1);
    }

    /**
     * The radii of the rings, out to {@code reach}: under the camera, at the drop, and where the render-distance fog starts and ends.
     * Between two of them the fog is linear in the radius, so a colour per ring is exact.
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
     * Black, fogged by the render-distance fog only at cylindrical distance {@code distance}, as {@code fog.glsl} fogs terrain there.
     */
    private static int fogged(FogData fog, float distance) {
        float amount = linear(distance, fog.renderDistanceStart, fog.renderDistanceEnd) * fog.color.w();
        float red = fog.color.x() * amount;
        float green = fog.color.y() * amount;
        float blue = fog.color.z() * amount;
        return ARGB.colorFromFloat(1f, red, green, blue); // colour-ok: the frame's fog colour, not a theme colour
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
