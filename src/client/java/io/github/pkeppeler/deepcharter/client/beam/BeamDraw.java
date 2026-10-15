package io.github.pkeppeler.deepcharter.client.beam;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.BeamStyle;
import java.util.Objects;
import net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import org.jspecify.annotations.Nullable;

/**
 * One fake light beam, ready to draw (#248): where it starts, which way it points, how long it is, and the style it is drawn in. A
 * beam is kept for its pod or floodlight and set again each frame, so drawing allocates nothing beyond the one render node vanilla
 * makes for a submit.
 *
 * <p>It is drawn with the {@code eyes} render type: translucent and emissive, so the beam shows in the dark at the colour it is given and
 * the game's lighting does not dim it. It adds no light level: the real light is the ledgered light block (ADR 0024). The texture is
 * {@code textures/effect/beam.png}, which a pack may replace (its v runs along the beam, its u round it).
 */
public final class BeamDraw implements CustomGeometryRenderer, BeamMesh.Sink {
    static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "textures/effect/beam.png");

    private static @Nullable RenderType renderType;

    private BeamStyle style;
    private int sides;
    private double fullLength;
    private double length;
    private double ox;
    private double oy;
    private double oz;
    private double dx;
    private double dy;
    private double dz;
    private int rgb;
    private PoseStack.@Nullable Pose pose;
    private @Nullable VertexConsumer vertices;

    public BeamDraw(BeamStyle style) {
        this.style = style;
    }

    public static RenderType renderType() {
        if (renderType == null) {
            renderType = RenderTypes.eyes(TEXTURE);
        }
        return renderType;
    }

    /** Aims the beam: {@code length} blocks of a beam that reaches {@code fullLength} in open air, from (ox, oy, oz) along the unit vector (dx, dy, dz). */
    public void aim(
            BeamStyle style,
            int sides,
            double fullLength,
            double length,
            double ox,
            double oy,
            double oz,
            double dx,
            double dy,
            double dz) {
        this.style = style;
        this.sides = sides;
        this.rgb = style.color();
        this.fullLength = fullLength;
        this.length = length;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    /** The length in blocks the beam is drawn at: its reach in open air, or less where a block cuts it. */
    // test-seam: the client tests pin the drawn length against the wall that cuts it; nothing in the game reads it back
    public double length() {
        return length;
    }

    /** Moves the start of the beam to (ox, oy, oz), keeping its aim: a floodlight's start is fixed in the world and drawn relative to the camera. */
    public void moveTo(double ox, double oy, double oz) {
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
    }

    @Override
    public void render(PoseStack.Pose pose, VertexConsumer vertices) {
        this.pose = pose;
        this.vertices = vertices;
        BeamMesh.emit(this, style, sides, fullLength, length, ox, oy, oz, dx, dy, dz);
        this.pose = null;
        this.vertices = null;
    }

    @Override
    public void vertex(double x, double y, double z, double alpha, float u, float v) {
        int a = (int) Math.round(Math.min(1.0, Math.max(0.0, alpha)) * 255.0);
        int argb = ARGB.color(a, rgb); // colour-ok: the theme colour with alpha
        PoseStack.Pose pose = Objects.requireNonNull(this.pose);
        Objects.requireNonNull(vertices)
                .addVertex(pose, (float) x, (float) y, (float) z)
                .setColor(argb)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightCoordsUtil.FULL_BRIGHT)
                .setNormal(pose, 0f, 1f, 0f);
    }
}
