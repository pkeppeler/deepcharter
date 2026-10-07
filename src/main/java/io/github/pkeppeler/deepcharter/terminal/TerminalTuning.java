package io.github.pkeppeler.deepcharter.terminal;

/**
 * Tunables for the terminal feature, read as {@code TerminalTuning.DEFAULT.thing()}.
 *
 * @param maxDistance blocks from the player's eyes to the middle of a terminal block: past this the server refuses to open
 *                    a terminal or to run an action at it
 */
public record TerminalTuning(double maxDistance) {
	public static final TerminalTuning DEFAULT = new TerminalTuning(6.0);
}
