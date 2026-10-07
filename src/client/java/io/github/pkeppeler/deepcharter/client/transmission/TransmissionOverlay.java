package io.github.pkeppeler.deepcharter.client.transmission;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.transmission.Transmission;
import io.github.pkeppeler.deepcharter.transmission.TransmissionCatalog;
import io.github.pkeppeler.deepcharter.transmission.TransmissionPayload;

/**
 * The transmission on screen, and those waiting behind it. The header (who sends it and how the signal is framed) types out first,
 * in a colour that depends on the framing, then the text, in phosphor green. The finished transmission stays up for
 * {@link #HOLD_TICKS}, then the next one starts. Time is counted in client ticks, and only while no screen is open, so a
 * transmission that arrives behind the "Loading terrain" screen of a crossing is not typed out unseen.
 *
 * <p>Read and written on the client thread. {@link TransmissionHud} draws it.
 */
public final class TransmissionOverlay {
	/** Ticks the finished transmission stays up. */
	public static final int HOLD_TICKS = 100;
	/** Phosphor green for the text. */
	public static final int TEXT_COLOR = 0xFF7CFC9A;
	/** The header colour of a live transmission: the same green. */
	public static final int LIVE_COLOR = TEXT_COLOR;
	/** The header colour of a relayed one: amber. */
	public static final int RELAY_COLOR = 0xFFFFC857;
	/** The header colour of one from an unknown sender: red. */
	public static final int UNKNOWN_COLOR = 0xFFFF5A4F;

	private static final double SECONDS_PER_TICK = 1.0 / 20.0;
	private static final String CHARTER_FIELD = "[CHARTER]";
	private static final String DIRECTOR_FIELD = "[DIRECTOR]";
	private static final float SOUND_VOLUME = 0.5f;
	private static final float TYPEWRITER_PITCH = 1.0f;

	/** One transmission being typed. */
	private record Current(Transmission transmission, Typewriter header, Typewriter body) {
	}

	private static final Deque<TransmissionPayload> PENDING = new ArrayDeque<>();
	/** The transmissions already reported as dropped, so that one broken id logs once. */
	private static final Set<Identifier> DROPPED = new HashSet<>();
	private static Current current;
	private static int heldTicks;
	/** Set by the letter hook, which can fire several times in a tick, and played once at the end of the tick. */
	private static boolean letterTyped;

	private TransmissionOverlay() {
	}

	/** The header colour of a framing. */
	public static int headerColor(Transmission.Framing framing) {
		return switch (framing) {
			case LIVE -> LIVE_COLOR;
			case RELAY -> RELAY_COLOR;
			case UNKNOWN -> UNKNOWN_COLOR;
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
			play(current.transmission().framing() == Transmission.Framing.UNKNOWN ? DeepSound.TRANSMISSION_MENACE : DeepSound.TRANSMISSION_INCOMING, 1.0f);
		}
		if (!current.header().done()) {
			current.header().advance(SECONDS_PER_TICK);
		} else if (!current.body().done()) {
			current.body().advance(SECONDS_PER_TICK);
		} else if (++heldTicks >= HOLD_TICKS) {
			current = null;
		}
		if (letterTyped) {
			letterTyped = false;
			play(DeepSound.UI_TYPEWRITER, TYPEWRITER_PITCH);
		}
	}

	/** True while a transmission is on screen. */
	public static boolean active() {
		return current != null;
	}

	/** How many transmissions wait behind the one on screen. */
	public static int waiting() {
		return PENDING.size();
	}

	/** The transmission on screen, if any. */
	public static Optional<Transmission> transmission() {
		return Optional.ofNullable(current).map(Current::transmission);
	}

	/** The header typed so far, or empty when nothing is on screen. */
	public static String headerShown() {
		return current == null ? "" : current.header().visible();
	}

	public static String headerFull() {
		return current == null ? "" : current.header().text();
	}

	/** The text typed so far, or empty when nothing is on screen. */
	public static String bodyShown() {
		return current == null ? "" : current.body().visible();
	}

	/** The whole text, with its fields filled, whether or not it has finished typing. */
	public static String bodyFull() {
		return current == null ? "" : current.body().text();
	}

	/** True when the header and the text are both fully typed. */
	public static boolean typed() {
		return current != null && current.header().done() && current.body().done();
	}

	private static Current build(TransmissionPayload payload) {
		Transmission transmission = TransmissionCatalog.require(payload.transmission());
		String sender = translate(transmission.senderKey(), Component.translatable(transmission.senderKey()));
		String header = translate(transmission.framing().headerKey(), Component.translatable(transmission.framing().headerKey(), sender));
		String body = fill(translate(transmission.bodyKey(), Component.translatable(transmission.bodyKey())), payload.charter(), payload.director());
		Typewriter.LetterHook hook = (index, letter) -> letterTyped |= !Character.isWhitespace(letter);
		return new Current(transmission, new Typewriter(header, hook), new Typewriter(body, hook));
	}

	private static String translate(String key, Component component) {
		if (!Language.getInstance().has(key)) {
			throw new IllegalStateException("Missing lang key " + key);
		}
		return component.getString();
	}

	private static void play(DeepSound sound, float pitch) {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound.event(), pitch, SOUND_VOLUME));
	}
}
