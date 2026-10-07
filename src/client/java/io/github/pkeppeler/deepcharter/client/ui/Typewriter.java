package io.github.pkeppeler.deepcharter.client.ui;

/**
 * Reveals a text one letter at a time, at a fixed rate, and tells a hook about each letter. Pure logic with
 * no game state, so the timing is testable with explicit time steps.
 *
 * <p>Every character counts as a letter, spaces included. A hook that must stay silent for spaces checks the
 * letter it is given.
 */
public final class Typewriter {
	/** Guards against {@code 0.05 * 20} adding up to {@code 0.9999999}, which would hold a letter back a step. */
	private static final double EPSILON = 1e-9;

	/** Called once per letter, in order, as the letter appears. */
	@FunctionalInterface
	public interface LetterHook {
		/**
		 * @param index position of the letter in the text, from 0
		 * @param letter the letter that just appeared
		 */
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
		revealTo((int) Math.min(text.length(), Math.floor(elapsed * lettersPerSecond + EPSILON)));
	}

	/** Reveals the rest of the text now. The hook still fires once for each remaining letter. */
	public void skip() {
		revealTo(text.length());
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

	private void revealTo(int target) {
		while (revealed < target) {
			char letter = text.charAt(revealed);
			hook.onLetter(revealed, letter);
			revealed++;
		}
	}
}
