package io.github.pkeppeler.deepcharter.client.suit;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.client.theme.SuitLook;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.suit.SuitRegistry;
import io.github.pkeppeler.deepcharter.suit.SuitState;
import io.github.pkeppeler.deepcharter.suit.SuitState.Mode;
import io.github.pkeppeler.deepcharter.suit.SuitTracks;
import io.github.pkeppeler.deepcharter.suit.SuitTuning;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The suit gauge (#993, ADR 0083): a line "SUIT 1:42", a segmented bar and the axes that drain it, at the top left, under the altimeter's
 * row. Vanilla's centred lines (the action bar, the held item's name, the title and the subtitle) can be as wide as a narrow GUI, and on
 * one 240 high no right-hand place between them, or beside the hotbar, holds the gauge; the top right is where vanilla shows potion effects.
 * It reads the player's own suit attachment, which the server sends to its owner alone. It is hidden where the suit is inert (the
 * depth asks nothing), while seated in a pod, and for a creative player or a spectator; a suit log that cannot be read shows one red line.
 * What is drawn and what is kept clear are the one box {@link #box}.
 */
public final class SuitGaugeHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "suit_gauge");

    /** How the gauge reads. */
    public enum Tone {
        /** A suit that holds or refills. */
        OK,
        /** Under the amber line. */
        AMBER,
        /** Under the red line, breached, or unreadable. */
        RED,
        /** At rest. */
        DIM
    }

    /**
     * What the gauge shows.
     *
     * @param label    the line over the bar
     * @param axes     the axes that drain the suit, or empty
     * @param fraction how full the bar is, 0 to 1
     * @param tone     the colour it is drawn in
     * @param blinks   whether the bar blinks
     */
    public record Gauge(Component label, Component axes, float fraction, Tone tone, boolean blinks) {}

    private SuitGaugeHud() {}

    static void init() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.AIR_BAR, ID, SuitGaugeHud::extract);
        HudKeepClear.register(SuitGaugeHud::keepClear);
    }

    /** What the gauge shows for the local player, or empty when it shows nothing. Public so tests can check it without reading pixels. */
    public static Optional<Gauge> gauge(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || player.isSpectator() || player.isCreative() || player.getVehicle() instanceof PodEntity) {
            return Optional.empty();
        }
        Versioned<SuitState> held = player.getAttached(SuitRegistry.SUIT);
        Optional<SuitTracks> tracks = Versioned.readable(player, SuitRegistry.TRACKS);
        if (held instanceof Versioned.Unreadable<SuitState> || tracks.isEmpty()) {
            return Optional.of(new Gauge(
                    Component.translatable("hud.deepcharter.suit.unreadable"), Component.empty(), 0f, Tone.RED, false));
        }
        if (!(held instanceof Versioned.Readable<SuitState>(SuitState state))) {
            return Optional.empty();
        }
        return gauge(state, tracks.get());
    }

    /**
     * What the gauge shows for a suit in {@code state} fitted with {@code tracks}, for a person on foot: empty for a suit that is inert,
     * and for a full one that refills (a sound hall in layer 1 shelters all the time). The bar is the reserve over the suit's own
     * capacity.
     */
    public static Optional<Gauge> gauge(SuitState state, SuitTracks tracks) {
        SuitTuning tuning = SuitTuning.DEFAULT;
        float capacity = tracks.capacity(tuning);
        if (state.mode() == Mode.INERT || (state.mode().refills() && state.reserve() >= capacity)) {
            return Optional.empty();
        }
        float fraction = Math.clamp(state.reserve() / capacity, 0f, 1f);
        float secondsLeft = state.rate() > 0f ? state.reserve() / state.rate() : state.reserve();
        Component axes = state.shortText(" ");
        return Optional.of(
                switch (state.mode()) {
                    case BREACHED ->
                        new Gauge(Component.translatable("hud.deepcharter.suit.breached"), axes, 0f, Tone.RED, true);
                    case SHELTERED, POD_REFILL ->
                        new Gauge(
                                Component.translatable("hud.deepcharter.suit.refilling"),
                                Component.empty(),
                                fraction,
                                Tone.OK,
                                false);
                    case POD_HOLD ->
                        new Gauge(
                                Component.translatable("hud.deepcharter.suit.holds"),
                                Component.empty(),
                                fraction,
                                Tone.DIM,
                                false);
                    case EXPOSED, LEAKING, INERT ->
                        new Gauge(
                                Component.translatable("hud.deepcharter.suit.time", clock(secondsLeft)),
                                axes,
                                fraction,
                                secondsLeft <= tuning.urgentSeconds()
                                        ? Tone.RED
                                        : secondsLeft <= tuning.warnSeconds() ? Tone.AMBER : Tone.OK,
                                secondsLeft <= tuning.urgentSeconds());
                });
    }

    /** {@code seconds} as minutes and seconds, "1:42". */
    private static String clock(float seconds) {
        int whole = (int) Math.ceil(seconds);
        return String.format(Locale.ROOT, "%d:%02d", whole / 60, whole % 60);
    }

    /** The box of the gauge, the same on every GUI size: as wide as the bar or its wider line, at the top left margins. */
    public static HudKeepClear.Rect box(Font font, Gauge gauge) {
        SuitLook look = SuitLook.current();
        int width = Math.max(look.barWidth(), Math.max(font.width(gauge.label()), font.width(gauge.axes())));
        int height = font.lineHeight
                + look.lineGap()
                + look.barHeight()
                + (gauge.axes().getString().isEmpty() ? 0 : look.lineGap() + font.lineHeight);
        return new HudKeepClear.Rect("suit gauge", look.leftMargin(), look.topMargin(), width, height);
    }

    private static void keepClear(Minecraft client, int guiWidth, int guiHeight, List<HudKeepClear.Rect> into) {
        gauge(client).ifPresent(gauge -> into.add(box(client.font, gauge)));
    }

    public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        Optional<Gauge> shown = gauge(client);
        if (shown.isEmpty() || client.player == null) {
            return;
        }
        Gauge gauge = shown.get();
        SuitLook look = SuitLook.current();
        Font font = client.font;
        HudKeepClear.Rect box = box(font, gauge);
        int colour = colour(gauge.tone(), look);
        graphics.text(font, gauge.label(), box.x(), box.y(), colour, true);
        int barY = box.y() + font.lineHeight + look.lineGap();
        boolean hidden = gauge.blinks() && (client.player.tickCount / look.blinkTicks()) % 2 == 1;
        if (!hidden) {
            bar(graphics, look, box.x(), barY, gauge.fraction(), colour);
        }
        if (!gauge.axes().getString().isEmpty()) {
            graphics.text(font, gauge.axes(), box.x(), barY + look.barHeight() + look.lineGap(), colour, true);
        }
    }

    private static int colour(Tone tone, SuitLook look) {
        return switch (tone) {
            case OK -> look.okColor();
            case AMBER -> look.amberColor();
            case RED -> look.redColor();
            case DIM -> look.dimColor();
        };
    }

    /** The cells of the bar, full ones in {@code colour}, the rest dim. */
    private static void bar(GuiGraphicsExtractor graphics, SuitLook look, int x, int y, float fraction, int colour) {
        int cell = (look.barWidth() - (look.segments() - 1) * look.segmentGap()) / look.segments();
        int full = (int) Math.ceil(fraction * look.segments());
        for (int i = 0; i < look.segments(); i++) {
            int left = x + i * (cell + look.segmentGap());
            graphics.fill(left, y, left + cell, y + look.barHeight(), i < full ? colour : look.dimColor());
        }
    }
}
