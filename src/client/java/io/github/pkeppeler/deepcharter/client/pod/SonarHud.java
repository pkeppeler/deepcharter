package io.github.pkeppeler.deepcharter.client.pod;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.Colors;
import io.github.pkeppeler.deepcharter.client.theme.SonarLook;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodSeat;
import io.github.pkeppeler.deepcharter.pod.PodSonar;
import io.github.pkeppeler.deepcharter.pod.SonarEcho;
import io.github.pkeppeler.deepcharter.pod.SonarKind;
import io.github.pkeppeler.deepcharter.pod.SonarTuning;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The navigator's sonar scope (#988), at the left of the screen, drawn only for the player in a pod's navigator seat: a round plan view of the
 * last ping's echoes, the pod at the middle and the way it faces up, out to the ping's range. Each kind of echo has its own shape as well as its colour (ore a
 * filled square, gas a hollow one, an empty cave a dot, a contact that moved a cross), so that it reads without the colour, and a tick over or under a shape says it
 * is above or below the pod. The echoes dim as they fade, and a bar under the scope fills while the sonar clears for the next ping. The pilot's scanner and Ribbon are
 * not touched: the scope is the one thing only the navigator sees past the headlights.
 */
public final class SonarHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "sonar");
    private static final int TITLE_GAP = 10;
    private static final int BAR_HEIGHT = 2;
    private static final int LINE_GAP = 2;

    private SonarHud() {}

    public static void init() {
        HudElementRegistry.addLast(ID, SonarHud::extract);
    }

    /** The pod whose sonar the local player works: the pod they ride, when they sit in its navigator seat. */
    static Optional<PodEntity> navigatedPod(Minecraft client) {
        if (client.player != null
                && client.player.getVehicle() instanceof PodEntity pod
                && PodSeat.find(pod, client.player)
                        .filter(seat -> seat == PodSeat.NAVIGATOR)
                        .isPresent()) {
            return Optional.of(pod);
        }
        return Optional.empty();
    }

    /** The echo the navigator is looking toward, within {@link SonarTuning#aimDegrees()}: the one a press of the mark key marks. Empty for anyone who is not the navigator. */
    static Optional<SonarEcho> aimedEcho(Minecraft client) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (level == null || player == null) {
            return Optional.empty();
        }
        return navigatedPod(client)
                .flatMap(PodSonar::reading)
                .flatMap(state -> SonarAim.pick(
                        player.getEyePosition(),
                        player.getLookAngle(),
                        state.shown(level.getGameTime()),
                        SonarTuning.DEFAULT.aimDegrees()));
    }

    /** Whether the scope is on screen for the local player. */
    public static boolean shown(Minecraft client) {
        return client.level != null
                && navigatedPod(client).flatMap(PodSonar::reading).isPresent();
    }

    private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null || !shown(client)) {
            return;
        }
        PodEntity pod = navigatedPod(client).orElseThrow();
        PodSonar.State state = PodSonar.reading(pod).orElseThrow();
        long now = level.getGameTime();
        SonarLook look = SonarLook.current();
        int side = look.scopePixels();
        int radius = side / 2;
        int left = look.margin();
        int top = (graphics.guiHeight() - side) / 2;
        int cx = left + radius;
        int cy = top + radius;

        graphics.fill(left - 1, top - 1, left + side + 1, top + side + 1, look.frameColor());
        graphics.fill(left, top, left + side, top + side, look.backgroundColor());
        rings(graphics, look, cx, cy, radius);
        graphics.text(
                client.font,
                Component.translatable("deepcharter.pod.sonar.title"),
                left,
                top - TITLE_GAP,
                look.titleColor());

        int alpha = look.minAlpha() + Math.round((255 - look.minAlpha()) * state.strength(now));
        for (SonarEcho echo : state.shown(now)) {
            SonarScope.project(
                            pod.position(), pod.getDirection(), echo.pos(), SonarTuning.DEFAULT.rangeBlocks(), radius)
                    .ifPresent(dot -> echo(graphics, look, echo.kind(), cx + dot.x(), cy + dot.y(), dot.rise(), alpha));
        }
        Optional<SonarEcho> aimed = aimedEcho(client);
        Set<BlockPos> marked = new HashSet<>();
        state.activeMarks(now).forEach(mark -> marked.add(mark.pos()));
        for (SonarEcho echo : state.shown(now)) {
            boolean isAimed = aimed.filter(echo::equals).isPresent();
            if (isAimed || marked.contains(echo.pos())) {
                SonarScope.project(
                                pod.position(),
                                pod.getDirection(),
                                echo.pos(),
                                SonarTuning.DEFAULT.rangeBlocks(),
                                radius)
                        .ifPresent(dot -> bracket(graphics, look.markColor(), cx + dot.x(), cy + dot.y(), isAimed));
            }
        }
        // The pod: a square at the middle, and its nose pointing up the scope.
        graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, look.podColor());
        graphics.fill(cx, cy - 3, cx + 1, cy - 1, look.podColor());

        int barTop = top + side + LINE_GAP;
        graphics.fill(left, barTop, left + side, barTop + BAR_HEIGHT, look.chargeBackColor());
        graphics.fill(
                left, barTop, left + Math.round(side * state.charged(now)), barTop + BAR_HEIGHT, look.chargeColor());
        graphics.text(
                client.font,
                Component.translatable("deepcharter.pod.sonar.hint", Component.keybind("key.deepcharter.sonar_ping")),
                left,
                barTop + BAR_HEIGHT + LINE_GAP,
                look.hintColor());
    }

    /** A frame of 7 x 7 round an echo: whole for a marked one, corners only for the one the navigator is aiming at. */
    private static void bracket(GuiGraphicsExtractor graphics, int colour, int x, int y, boolean cornersOnly) {
        int reach = 3;
        int arm = cornersOnly ? 2 : 2 * reach + 1;
        for (int sx : new int[] {-reach, reach}) {
            for (int sy : new int[] {-reach, reach}) {
                int armX = sx < 0 ? 0 : -(arm - 1);
                int armY = sy < 0 ? 0 : -(arm - 1);
                graphics.fill(x + sx + armX, y + sy, x + sx + armX + arm, y + sy + 1, colour);
                graphics.fill(x + sx, y + sy + armY, x + sx + 1, y + sy + armY + arm, colour);
            }
        }
    }

    /** Rings at half and the whole range, and the cross through the pod. A ring is a run of single pixels, so no texture is needed. */
    private static void rings(GuiGraphicsExtractor graphics, SonarLook look, int cx, int cy, int radius) {
        graphics.fill(cx - radius, cy, cx + radius + 1, cy + 1, look.ringColor());
        graphics.fill(cx, cy - radius, cx + 1, cy + radius + 1, look.ringColor());
        for (int ring : new int[] {radius / 2, radius}) {
            int steps = Math.max(32, 8 * ring);
            for (int i = 0; i < steps; i++) {
                double angle = 2 * Math.PI * i / steps;
                int x = cx + (int) Math.round(ring * Math.cos(angle));
                int y = cy + (int) Math.round(ring * Math.sin(angle));
                graphics.fill(x, y, x + 1, y + 1, look.ringColor());
            }
        }
    }

    /** One echo at ({@code x}, {@code y}): its shape for the kind, in the kind's colour at {@code alpha}, with a tick over it when it is above and under it when below. */
    private static void echo(
            GuiGraphicsExtractor graphics, SonarLook look, SonarKind kind, int x, int y, int rise, int alpha) {
        int colour = Colors.withAlpha(
                switch (kind) {
                    case ORE -> look.oreColor();
                    case GAS -> look.gasColor();
                    case CAVE -> look.caveColor();
                    case MOVED -> look.movedColor();
                },
                alpha);
        switch (kind) {
            case ORE -> graphics.fill(x - 1, y - 1, x + 2, y + 2, colour);
            case GAS -> {
                graphics.fill(x - 1, y - 1, x + 2, y, colour);
                graphics.fill(x - 1, y + 1, x + 2, y + 2, colour);
                graphics.fill(x - 1, y, x, y + 1, colour);
                graphics.fill(x + 1, y, x + 2, y + 1, colour);
            }
            case CAVE -> graphics.fill(x, y, x + 1, y + 1, colour);
            case MOVED -> {
                graphics.fill(x - 1, y, x + 2, y + 1, colour);
                graphics.fill(x, y - 1, x + 1, y + 2, colour);
            }
        }
        if (rise > 0) {
            graphics.fill(x, y - 3, x + 1, y - 2, colour);
        } else if (rise < 0) {
            graphics.fill(x, y + 3, x + 1, y + 4, colour);
        }
    }
}
