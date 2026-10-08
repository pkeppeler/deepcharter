package io.github.pkeppeler.deepcharter.terminal;

/**
 * Tunables for the terminal feature, read as {@code TerminalTuning.DEFAULT.thing()}.
 *
 * @param maxDistance  blocks from the player's eyes to the middle of a terminal block: past this the server refuses to open
 *                     a terminal or to run an action at it
 * @param parkedRadius blocks from the middle of a terminal block to a pod's position within which the pod counts as parked at
 *                     the terminal, for every pod terminal
 */
public record TerminalTuning(double maxDistance, double parkedRadius) {
	public static final TerminalTuning DEFAULT = new TerminalTuning(6.0, 8.0);

	public TerminalTuning {
		if (!(parkedRadius > 0)) {
			throw new IllegalArgumentException("the radius a pod is parked within must be above 0, got " + parkedRadius);
		}
	}
}
