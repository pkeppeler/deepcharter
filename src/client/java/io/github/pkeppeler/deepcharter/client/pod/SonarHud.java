package io.github.pkeppeler.deepcharter.client.pod;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.Colors;
import io.github.pkeppeler.deepcharter.client.theme.SonarLook;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodSeat;
import io.github.pkeppeler.deepcharter.pod.PodSonar;
import io.github.pkeppeler.deepcharter.pod.SonarEcho;
import io.github.pkeppeler.deepcharter.pod.SonarKind;
import io.github.pkeppeler.deepcharter.pod.SonarMark;
import io.github.pkeppeler.deepcharter.pod.SonarTuning;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
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
    public static final int BAR_HEIGHT = 2;
    public static final int LINE_GAP = 2;

    private SonarHud() {}

    public static void init() {
        HudElementRegistry.addLast(ID, SonarHud::extract);
        HudKeepClear.register(SonarHud::keepClear);
    }

    private static Component title() {
        return Component.translatable("deepcharter.pod.sonar.title");
    }

    private static Component hint() {
        return Component.translatable("deepcharter.pod.sonar.hint", Component.keybind("key.deepcharter.sonar_ping"));
    }

    /** Where the scope sits on a GUI {@code guiHeight} high: its left, its top and its side. Drawing and keeping clear both start here. */
    private record Scope(int left, int top, int side) {
        static Scope of(SonarLook look, int guiHeight) {
            return new Scope(look.margin(), (guiHeight - look.scopePixels()) / 2, look.scopePixels());
        }

        int barTop() {
            return top + side + LINE_GAP;
        }

        int hintTop() {
            return barTop() + BAR_HEIGHT + LINE_GAP;
        }
    }

    /**
     * The box of everything the scope draws: its frame, the title over it, the charge bar and the hint under it, as wide as the widest of
     * them. What is drawn and what is kept clear are this one box.
     */
    private static HudKeepClear.Rect box(Font font, SonarLook look, int guiHeight) {
        Scope scope = Scope.of(look, guiHeight);
        int left = scope.left() - 1;
        int top = scope.top() - TITLE_GAP;
        int right = Math.max(
                scope.left() + scope.side() + 1, scope.left() + Math.max(font.width(title()), font.width(hint())));
        int bottom = scope.hintTop() + font.lineHeight;
        return new HudKeepClear.Rect("sonar scope", left, top, right - left, bottom - top);
    }

    private static void keepClear(Minecraft client, int guiWidth, int guiHeight, List<HudKeepClear.Rect> into) {
        if (client.gui.screen() == null
                && !client.gui.hud.isHidden()
                && frame(client).isPresent()) {
            into.add(box(client.font, SonarLook.current(), guiHeight));
        }
    }

    /**
     * What the local player's sonar shows this frame, resolved once: the pod they ride, its readable picture, the game time and the player. Empty
     * for anyone who does not sit in a pod's navigator seat, and when the pod's picture is unreadable.
     */
    public record Frame(PodEntity pod, PodSonar.State state, long now, LocalPlayer player) {
        /**
         * What a press of the mark key means: the echo or the mark nearest the direction the navigator looks, within {@link SonarTuning#aimDegrees()}.
         * A mark counts after its echo has faded, so it can be taken off.
         */
        Optional<SonarEcho> aimed() {
            List<SonarEcho> candidates = new ArrayList<>(state.shown(now));
            state.activeMarks(now).forEach(mark -> candidates.add(new SonarEcho(mark.kind(), mark.pos())));
            return SonarAim.pick(
                    player.getEyePosition(), player.getLookAngle(), candidates, SonarTuning.DEFAULT.aimDegrees());
        }
    }

    /** The local player's sonar this frame, or empty when they are not working one; see {@link Frame}. */
    public static Optional<Frame> frame(Minecraft client) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (level == null
                || player == null
                || !(player.getVehicle() instanceof PodEntity pod)
                || !PodSeat.isNavigator(pod, player)) {
            return Optional.empty();
        }
        return PodSonar.reading(pod).map(state -> new Frame(pod, state, level.getGameTime(), player));
    }

    public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        Optional<Frame> frame = frame(client);
        if (frame.isEmpty()) {
            return;
        }
        PodEntity pod = frame.get().pod();
        PodSonar.State state = frame.get().state();
        long now = frame.get().now();
        SonarLook look = SonarLook.current();
        Scope scope = Scope.of(look, graphics.guiHeight());
        int side = scope.side();
        int radius = side / 2;
        int left = scope.left();
        int top = scope.top();
        int cx = left + radius;
        int cy = top + radius;

        graphics.fill(left - 1, top - 1, left + side + 1, top + side + 1, look.frameColor());
        graphics.fill(left, top, left + side, top + side, look.backgroundColor());
        rings(graphics, look, cx, cy, radius);
        graphics.text(client.font, title(), left, top - TITLE_GAP, look.titleColor());

        int alpha = look.minAlpha() + Math.round((255 - look.minAlpha()) * state.strength(now));
        for (SonarEcho echo : state.shown(now)) {
            project(pod, echo.pos(), radius)
                    .ifPresent(dot -> echo(graphics, look, echo.kind(), cx + dot.x(), cy + dot.y(), dot.rise(), alpha));
        }
        for (SonarMark mark : state.activeMarks(now)) {
            project(pod, mark.pos(), radius)
                    .ifPresent(dot -> bracket(graphics, look.markColor(), cx + dot.x(), cy + dot.y(), false));
        }
        frame.get()
                .aimed()
                .flatMap(echo -> project(pod, echo.pos(), radius))
                .ifPresent(dot -> bracket(graphics, look.markColor(), cx + dot.x(), cy + dot.y(), true));
        // The pod: a square at the middle, and its nose pointing up the scope.
        graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, look.podColor());
        graphics.fill(cx, cy - 3, cx + 1, cy - 1, look.podColor());

        int barTop = scope.barTop();
        graphics.fill(left, barTop, left + side, barTop + BAR_HEIGHT, look.chargeBackColor());
        graphics.fill(
                left, barTop, left + Math.round(side * state.charged(now)), barTop + BAR_HEIGHT, look.chargeColor());
        graphics.text(client.font, hint(), left, scope.hintTop(), look.hintColor());
    }

    private static Optional<SonarScope.Dot> project(PodEntity pod, BlockPos pos, int radius) {
        return SonarScope.project(pod.position(), pod.getDirection(), pos, SonarTuning.DEFAULT.rangeBlocks(), radius);
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

    /** The pixels of the ring of each radius, as offsets from the pod packed as {@code dx * 65536 + (dy & 0xFFFF)}, worked out once. */
    private static final Map<Integer, int[]> RING_POINTS = new ConcurrentHashMap<>();

    private static int[] ringPoints(int ring) {
        return RING_POINTS.computeIfAbsent(ring, radius -> {
            int steps = Math.max(32, 8 * radius);
            int[] points = new int[steps];
            for (int i = 0; i < steps; i++) {
                double angle = 2 * Math.PI * i / steps;
                int dx = (int) Math.round(radius * Math.cos(angle));
                int dy = (int) Math.round(radius * Math.sin(angle));
                int low = dy & 0xFFFF;
                points[i] = dx * 65536 + low;
            }
            return points;
        });
    }

    /** Rings at half and the whole range, and the cross through the pod. A ring is a run of single pixels, so no texture is needed. */
    private static void rings(GuiGraphicsExtractor graphics, SonarLook look, int cx, int cy, int radius) {
        graphics.fill(cx - radius, cy, cx + radius + 1, cy + 1, look.ringColor());
        graphics.fill(cx, cy - radius, cx + 1, cy + radius + 1, look.ringColor());
        for (int ring : new int[] {radius / 2, radius}) {
            for (int point : ringPoints(ring)) {
                int x = cx + (point >> 16);
                int y = cy + (short) point;
                graphics.fill(x, y, x + 1, y + 1, look.ringColor());
            }
        }
    }

    /** One echo at ({@code x}, {@code y}): its shape for the kind, in the kind's colour at {@code alpha}, with a tick over it when it is above and under it when below. */
    private static void echo(
            GuiGraphicsExtractor graphics, SonarLook look, SonarKind kind, int x, int y, int rise, int alpha) {
        int colour = Colors.withAlpha(look.colour(kind), alpha);
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
