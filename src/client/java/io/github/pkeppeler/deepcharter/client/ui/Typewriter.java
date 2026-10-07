package io.github.pkeppeler.deepcharter.client.ui;

/**
 * Reveals a text one letter at a time at a fixed rate. Pure logic with no game state, so the timing is testable
 * with explicit time steps. Every character counts as a letter, spaces included.
 */
public final class Typewriter {
	/** Guards against {@code 0.05 * 20} adding up to {@code 0.9999999}, which would hold a letter back a step. */
	private static final double EPSILON = 1e-9;

	/**
	 * Called once per letter, in order, as the letter appears. A tick can reveal several letters (the tuned rate
	 * is 2 per tick), so the hook can fire several times in a row: a sound consumer must throttle. The letter is
	 * given so that a hook can stay silent for spaces.
	 */
	@FunctionalInterface
	public interface LetterHook {
		void onLetter(int index, char letter);
	}

	private final String text;
	private final double lettersPerSecond;
	private final LetterHook hook;
	private double elapsed;
	private int revealed;

	public Typewriter(String text, double lettersPerSecond, LetterHook hook) {
		if (lettersPerSecond <= 0) {
			throw new IllegalArgumentException("lettersPerSecond must be positive, got " + lettersPerSecond);
		}
		this.text = text;
		this.lettersPerSecond = lettersPerSecond;
		this.hook = hook;
	}

	/** A typewriter at the tuned rate, {@link CrtTuning#lettersPerSecond()}. */
	public Typewriter(String text, LetterHook hook) {
		this(text, CrtTuning.DEFAULT.lettersPerSecond(), hook);
	}

	/** Moves time forward and reveals the letters it uncovers, firing the hook for each, in order. */
	public void advance(double seconds) {
		if (seconds < 0) {
			throw new IllegalArgumentException("seconds must not be negative, got " + seconds);
		}
		elapsed += seconds;
		int target = (int) Math.min(text.length(), Math.floor(elapsed * lettersPerSecond + EPSILON));
		while (revealed < target) {
			hook.onLetter(revealed, text.charAt(revealed));
			revealed++;
		}
	}

	/** Reveals the rest of the text now. No letter hook fires for the skipped letters: a burst of sound would be noise. */
	public void skip() {
		revealed = text.length();
	}

	public int revealed() {
		return revealed;
	}

	public boolean done() {
		return revealed >= text.length();
	}

	/** The part of the text revealed so far. */
	public String visible() {
		return text.substring(0, revealed);
	}

	/** The whole text, revealed or not. */
	public String text() {
		return text;
	}
}
