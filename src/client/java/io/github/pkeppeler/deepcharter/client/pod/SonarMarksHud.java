package io.github.pkeppeler.deepcharter.client.pod;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.SonarLook;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodSonar;
import io.github.pkeppeler.deepcharter.pod.SonarMark;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * The navigator's marks on the HUD of everyone riding the pod (#988), the pilot's above all: a diamond where the marked echo lies in the view, in its
 * kind's colour, with its kind and distance under it. A mark that is off screen or behind sits at the edge of the screen toward it, so the pilot
 * turns toward it. It is its own element and touches neither the Ribbon nor the pod readout.
 */
public final class SonarMarksHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "sonar_marks");
    private static final int LABEL_GAP = 2;

    private SonarMarksHud() {}

    public static void init() {
        // keep-clear-ok: the marks are world-projected diamonds that follow the marked echo across the view, and at the
        // screen edge when it is off screen; they have no fixed box for a popup to avoid
        HudElementRegistry.addLast(ID, SonarMarksHud::extract);
    }

    private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || !(client.player.getVehicle() instanceof PodEntity pod)) {
            return;
        }
        PodSonar.State state = PodSonar.reading(pod).orElse(PodSonar.State.EMPTY);
        float partial = deltaTracker.getGameTimeDeltaPartialTick(false);
        SonarLook look = SonarLook.current();
        Vec3 eye = client.player.getEyePosition(partial);
        for (SonarMark mark : state.activeMarks(client.level.getGameTime())) {
            Vec3 at = Vec3.atCenterOf(mark.pos());
            SonarMarkView.Spot spot = SonarMarkView.project(
                    eye,
                    client.player.getViewYRot(partial),
                    client.player.getViewXRot(partial),
                    at,
                    client.options.fov().get(),
                    graphics.guiWidth(),
                    graphics.guiHeight(),
                    look.markMargin());
            int colour = look.colour(mark.kind());
            diamond(graphics, look, spot.x(), spot.y(), colour);
            Component label = Component.translatable(
                    "deepcharter.pod.sonar.mark",
                    Component.translatable(
                            "deepcharter.pod.sonar.kind." + mark.kind().getSerializedName()),
                    Math.round(eye.distanceTo(at)));
            int width = client.font.width(label);
            int labelX = Math.clamp(spot.x() - width / 2, 0, graphics.guiWidth() - width);
            graphics.text(
                    client.font,
                    label,
                    labelX,
                    Math.min(
                            spot.y() + look.markPixels() / 2 + LABEL_GAP,
                            graphics.guiHeight() - client.font.lineHeight),
                    colour);
        }
    }

    /** A diamond {@code markPixels} across: rows of growing then shrinking width, in a frame. */
    private static void diamond(GuiGraphicsExtractor graphics, SonarLook look, int cx, int cy, int colour) {
        int half = look.markPixels() / 2;
        for (int dy = -half; dy <= half; dy++) {
            int span = half - Math.abs(dy);
            graphics.fill(cx - span - 1, cy + dy, cx + span + 2, cy + dy + 1, look.frameColor());
        }
        for (int dy = -half; dy <= half; dy++) {
            int span = half - Math.abs(dy);
            graphics.fill(cx - span, cy + dy, cx + span + 1, cy + dy + 1, colour);
        }
    }
}
