package io.github.pkeppeler.deepcharter.client.pod;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook.CameraLook;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

/**
 * The front camera's feed (#448, docs/design/cockpit-concepts-2.md). A pod with the camera shows its pilot the world through a video feed: while the feed is live,
 * the pod's own cab is not drawn from the seat ({@link #hidesCab}), so the view is clear. Three hazards work on the feed, each readable and none a toggle:
 * <ul>
 * <li>a hit to the hull (a gas blast, a hard landing, the crust) leaves static that fades;</li>
 * <li>lava heats the lens: the picture wobbles in a haze while the hull burns, and after a few seconds of it the feed fails, which shows the cab until the lens cools;</li>
 * <li>a hull that is nearly gone makes the feed drop out in bursts, more often the less hull is left;</li>
 * </ul>
 * and a pod with no power has no feed. Whether a pod has the camera is {@link CameraLook#installed}, a stand-in until a build buys the part.
 */
public final class FrontCameraFeed {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "front_camera");

    /**
     * Where the feed is.
     *
     * @param staticLevel how much static is on the picture, 0 to 1
     * @param lens how hot the lens is, 0 to 1
     * @param lensFailed the lens is past its limit and has not cooled enough to come back
     * @param dropTicks how many ticks the feed stays out in the drop-out it is in, 0 when it is not in one
     * @param hull the pod's hull the last tick, to see a hit; meaningful once {@code seen}
     * @param seen whether the feed has watched a tick of this pod yet
     * @param powered whether the pod had power the last tick
     * @param podId the pod the feed has been watching, so that a pilot who changes pods starts a new feed
     */
    public record State(
            double staticLevel,
            double lens,
            boolean lensFailed,
            int dropTicks,
            float hull,
            boolean seen,
            boolean powered,
            int podId) {
        public static final State START = new State(0, 0, false, 0, 0f, false, false, -1);

        /** The feed shows the world: it has power, the lens works and it is not in a drop-out. */
        public boolean live() {
            return powered && !lensFailed && dropTicks == 0;
        }
    }

    /** What the pod does that the feed answers to, as of this tick. */
    public record Inputs(int podId, float hull, float maxHull, boolean burning, boolean powered) {}

    private static final RandomSource TICK_RANDOM = RandomSource.create();
    private static final RandomSource DRAW_RANDOM = RandomSource.create();
    private static State state = State.START;

    private FrontCameraFeed() {}

    /** Called after {@link CockpitHud#init}, so that the feed's static is under the cockpit. */
    public static void init() {
        HudElementRegistry.addFirst(ID, FrontCameraFeed::extract);
        ClientTickEvents.END_CLIENT_TICK.register(FrontCameraFeed::tick);
    }

    /** The next state of the feed, one tick on. */
    public static State step(State watched, Inputs in, CameraLook look, double hullLowFraction, RandomSource random) {
        State previous = watched.podId() == in.podId() ? watched : State.START;
        double hit = previous.seen() && !in.burning() && in.maxHull() > 0f
                ? Math.max(0f, previous.hull() - in.hull()) / in.maxHull()
                : 0.0;
        double staticLevel = Math.min(
                1.0, Math.max(0.0, previous.staticLevel() - look.staticFallPerTick()) + hit * look.staticPerHull());
        double lens = in.burning()
                ? Math.min(1.0, previous.lens() + look.lensHeatPerTick())
                : Math.max(0.0, previous.lens() - look.lensCoolPerTick());
        boolean failed = lens >= look.lensDead() || (previous.lensFailed() && lens > look.lensRecover());
        double hullShare = in.maxHull() > 0f ? in.hull() / in.maxHull() : 0.0;
        int dropTicks = 0;
        if (hullShare <= hullLowFraction) {
            double chance = look.flickerMaxChance() * (1.0 - Math.max(0.0, hullShare) / hullLowFraction);
            if (previous.dropTicks() > 0) {
                dropTicks = previous.dropTicks() - 1;
            } else if (random.nextDouble() < chance) {
                dropTicks =
                        look.flickerMinTicks() + random.nextInt(look.flickerMaxTicks() - look.flickerMinTicks() + 1);
            }
        }
        return new State(staticLevel, lens, failed, dropTicks, in.hull(), true, in.powered(), in.podId());
    }

    private static void tick(Minecraft client) {
        if (client.player == null
                || !(client.player.getVehicle() instanceof PodEntity pod)
                || !CockpitLook.current().camera().installed()) {
            state = State.START;
            return;
        }
        CockpitLook look = CockpitLook.current();
        state = step(
                state,
                new Inputs(pod.getId(), pod.hull(), pod.maxHull(), pod.hullBurning(), PodEvents.isPowered(pod)),
                look.camera(),
                look.hullLowFraction(),
                TICK_RANDOM);
    }

    /** Where the feed is now: the badge, the haze and the static draw from it. */
    public static State state() {
        return state;
    }

    /** Whether the local pilot of {@code pod} sees through a live feed: the camera is on the pod, and the picture is clear of failure. */
    public static boolean live(Minecraft client, PodEntity pod) {
        return CockpitLook.current().camera().installed()
                && client.player != null
                && client.player.getVehicle() == pod
                && state().live();
    }

    /** Whether the pod's cab is left out of the picture: the local pilot sits in {@code pod}, looks out of it in first person, and the feed is live. */
    public static boolean hidesCab(PodEntity pod) {
        Minecraft client = Minecraft.getInstance();
        return live(client, pod) && client.options.getCameraType().isFirstPerson();
    }

    private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        CockpitLook look = CockpitLook.current();
        CameraLook camera = look.camera();
        if (!camera.installed() || client.player == null || !(client.player.getVehicle() instanceof PodEntity pod)) {
            return;
        }
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        boolean live = state().live() && client.options.getCameraType().isFirstPerson();
        if (live) {
            haze(
                    graphics,
                    camera,
                    CockpitHud.heat(),
                    width,
                    height,
                    pod.tickCount + deltaTracker.getGameTimeDeltaPartialTick(false));
            noise(graphics, camera, state().staticLevel(), width, height);
        }
        badge(graphics, client, look, camera, state().live(), width, height);
    }

    /** The heat haze: a warm tint, and bands that drift up and sway, as many as the heat says. */
    private static void haze(
            GuiGraphicsExtractor graphics, CameraLook camera, double heat, int width, int height, float time) {
        if (heat < 0.01) {
            return;
        }
        graphics.fill(
                0, 0, width, height, CameraLook.withAlpha((int) (heat * camera.hazeAlpha() / 2), camera.hazeColor()));
        int lines = (int) Math.ceil(heat * camera.hazeLines());
        for (int k = 0; k < lines; k++) {
            int y = (int) ((k * height / (double) camera.hazeLines() + time * 1.5) % height);
            int sway = (int) (Math.sin(time * 0.35 + k * 1.7) * 6 * heat);
            graphics.fill(
                    sway,
                    y,
                    width + sway,
                    y + 2 + k % 3,
                    CameraLook.withAlpha((int) (heat * camera.hazeAlpha()), camera.hazeColor()));
        }
    }

    /** Static: a veil that darkens the picture, specks of snow and a few torn bands, all in proportion to the static. */
    private static void noise(GuiGraphicsExtractor graphics, CameraLook camera, double level, int width, int height) {
        if (level < 0.01) {
            return;
        }
        graphics.fill(
                0, 0, width, height, CameraLook.withAlpha((int) (level * camera.veilAlpha()), camera.veilColor()));
        int dots = (int) (level * camera.staticDots());
        for (int i = 0; i < dots; i++) {
            int x = DRAW_RANDOM.nextInt(width);
            int y = DRAW_RANDOM.nextInt(height);
            graphics.fill(
                    x,
                    y,
                    x + camera.dotSize(),
                    y + camera.dotSize(),
                    CameraLook.withAlpha(
                            camera.dotAlphaMin() + DRAW_RANDOM.nextInt(256 - camera.dotAlphaMin()),
                            camera.staticColor()));
        }
        int bands = (int) (level * camera.tearBands());
        for (int i = 0; i < bands; i++) {
            int y = DRAW_RANDOM.nextInt(height);
            graphics.fill(
                    0,
                    y,
                    width,
                    y + 1 + DRAW_RANDOM.nextInt(3),
                    CameraLook.withAlpha(camera.tearAlpha(), camera.staticColor()));
        }
    }

    /** The feed's badge: a lamp and its word. Green and CAM when clear, amber when static or haze is on the picture, red and NO FEED when the feed is out. */
    private static void badge(
            GuiGraphicsExtractor graphics,
            Minecraft client,
            CockpitLook look,
            CameraLook camera,
            boolean live,
            int width,
            int height) {
        Font font = client.font;
        Component text =
                Component.translatable(live ? "hud.deepcharter.pod.camera" : "hud.deepcharter.pod.camera_dead");
        int color = !live
                ? look.lowColor()
                : state().staticLevel() > camera.badgeStatic() || CockpitHud.heat() > look.heatWarning()
                        ? look.fuelColor()
                        : camera.liveColor();
        int boxWidth = camera.badgeDot() + camera.badgeGap() + font.width(text);
        int left = camera.badge().left(width, boxWidth);
        int top = camera.badge().top(height, font.lineHeight);
        int pad = camera.badgePad();
        graphics.fill(left - pad, top - pad, left + boxWidth + pad, top + font.lineHeight + pad, camera.badgeBack());
        int dotTop = top + (font.lineHeight - camera.badgeDot()) / 2;
        graphics.fill(left, dotTop, left + camera.badgeDot(), dotTop + camera.badgeDot(), color);
        graphics.text(font, text, left + camera.badgeDot() + camera.badgeGap(), top, color);
    }
}
