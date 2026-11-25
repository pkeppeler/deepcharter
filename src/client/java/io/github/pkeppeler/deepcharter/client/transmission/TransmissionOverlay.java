package io.github.pkeppeler.deepcharter.client.transmission;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.TransmissionLook;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.transmission.Transmission;
import io.github.pkeppeler.deepcharter.transmission.TransmissionCatalog;
import io.github.pkeppeler.deepcharter.transmission.TransmissionPayload;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The transmission on screen, and those waiting behind it. The header (who sends it and how the signal is framed) types out first,
 * in a colour that depends on the framing, then the text, in the look's text colour, closed by the framing's last line. A relay's
 * header counts its loops: one a day of the world, from the count in the data file. The finished transmission stays up for
 * {@link TransmissionLook#holdTicks()}, then the next one starts. Time is counted in client ticks, and only while no screen is open, so a
 * transmission that arrives behind the "Loading terrain" screen of a crossing is not typed out unseen.
 *
 * <p>Read and written on the client thread. {@link TransmissionHud} draws it.
 */
public final class TransmissionOverlay {
    private static final double SECONDS_PER_TICK = 1.0 / 20.0;
    private static final String CHARTER_FIELD = "[CHARTER]";
    private static final String DIRECTOR_FIELD = "[DIRECTOR]";
    private static final float SOUND_VOLUME = 0.5f;
    private static final float PITCH = 1.0f;
    private static final long TICKS_PER_DAY = 24_000L;
    private static final String PARAGRAPH = "\n\n";

    /** One transmission being typed. */
    private record Current(Transmission transmission, Typewriter header, Typewriter body) {}

    private static final Deque<TransmissionPayload> PENDING = new ArrayDeque<>();
    /** The transmissions already reported as dropped, so that one broken id logs once. */
    private static final Set<Identifier> DROPPED = new HashSet<>();

    private static @Nullable Current current;
    private static int heldTicks;
    /** Set by the letter hook, which can fire several times in a tick, and played once at the end of the tick. */
    private static boolean letterTyped;

    private TransmissionOverlay() {}

    /** The header colour of a framing. */
    public static int headerColor(Transmission.Framing framing) {
        TransmissionLook look = TransmissionLook.current();
        return switch (framing) {
            case LIVE -> look.liveColor();
            case RELAY -> look.relayColor();
            case UNKNOWN -> look.unknownColor();
        };
    }

    /** Replaces {@code [CHARTER]} and {@code [DIRECTOR]} in {@code text}. */
    public static String fill(String text, String charter, String director) {
        return text.replace(CHARTER_FIELD, charter).replace(DIRECTOR_FIELD, director);
    }

    /**
     * Queues a transmission the server sent. It starts when the one on screen has finished. A transmission that cannot be shown (the data
     * file does not list it, or a lang key is missing) is dropped with one log line: this runs in the packet handler, and a throw there
     * would disconnect the player.
     */
    public static void enqueue(TransmissionPayload payload) {
        try {
            // Found now, not when the player is looking at the screen.
            build(payload);
        } catch (RuntimeException e) {
            if (DROPPED.add(payload.transmission())) {
                DeepCharter.LOGGER.error("Dropped transmission {}: it cannot be shown", payload.transmission(), e);
            }
            return;
        }
        PENDING.add(payload);
    }

    /** Forgets everything on screen and waiting. */
    public static void reset() {
        PENDING.clear();
        current = null;
        heldTicks = 0;
        letterTyped = false;
    }

    /** One client tick. */
    public static void tick() {
        Minecraft client = Minecraft.getInstance();
        if (client.gui.screen() != null) {
            return;
        }
        if (current == null) {
            TransmissionPayload next = PENDING.poll();
            if (next == null) {
                return;
            }
            current = build(next);
            heldTicks = 0;
            play(
                    current.transmission().framing() == Transmission.Framing.UNKNOWN
                            ? DeepSound.TRANSMISSION_MENACE
                            : DeepSound.TRANSMISSION_INCOMING);
        }
        if (!current.header().done()) {
            current.header().advance(SECONDS_PER_TICK);
        } else if (!current.body().done()) {
            current.body().advance(SECONDS_PER_TICK);
        } else if (++heldTicks >= TransmissionLook.current().holdTicks()) {
            current = null;
        }
        if (letterTyped) {
            letterTyped = false;
            play(DeepSound.UI_TYPEWRITER);
        }
    }

    /** True while a transmission is on screen. */
    public static boolean active() {
        return current != null;
    }

    /** The transmission on screen, if any. */
    public static Optional<Transmission> transmission() {
        return Optional.ofNullable(current).map(Current::transmission);
    }

    /** The header typed so far, or empty when nothing is on screen. */
    public static String headerShown() {
        return current == null ? "" : current.header().visible();
    }

    /** The text typed so far, or empty when nothing is on screen. */
    public static String bodyShown() {
        return current == null ? "" : current.body().visible();
    }

    /** The whole text, with its fields filled and the framing's closing line, whether or not it has finished typing. */
    public static String bodyFull() {
        return current == null ? "" : current.body().text();
    }

    /** True when the header and the text are both fully typed. */
    public static boolean typed() {
        return current != null && current.header().done() && current.body().done();
    }

    /**
     * The header of {@code transmission} as the player sees it on {@code day}: the framing's wording with the sender's name, and for a relay
     * the loop counter, grouped in thousands as the script writes it.
     */
    private static String header(Transmission transmission, long day) {
        String sender = translate(transmission.senderKey());
        String key = transmission.framing().headerKey();
        Component header = transmission.framing() == Transmission.Framing.RELAY
                ? Component.translatable(key, sender, String.format(Locale.ROOT, "%,d", transmission.loopOn(day)))
                : Component.translatable(key, sender);
        return translate(key, header);
    }

    /** The day of the world the client is in, counted from 0; 0 before a world is loaded. */
    private static long day() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? 0 : level.getGameTime() / TICKS_PER_DAY;
    }

    private static Current build(TransmissionPayload payload) {
        Transmission transmission = TransmissionCatalog.require(payload.transmission());
        String header = header(transmission, day());
        String body = fill(translate(transmission.bodyKey()), payload.charter(), payload.director())
                + PARAGRAPH
                + translate(transmission.framing().endKey());
        Typewriter.LetterHook hook = (index, letter) -> letterTyped |= !Character.isWhitespace(letter);
        return new Current(transmission, new Typewriter(header, hook), new Typewriter(body, hook));
    }

    private static String translate(String key) {
        return translate(key, Component.translatable(key));
    }

    private static String translate(String key, Component component) {
        if (!Language.getInstance().has(key)) {
            throw new IllegalStateException("Missing lang key " + key);
        }
        return component.getString();
    }

    private static void play(DeepSound sound) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound.event(), PITCH, SOUND_VOLUME));
    }
}
