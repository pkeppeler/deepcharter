package io.github.pkeppeler.deepcharter.terminal;

/**
 * Tunables for the terminal feature, read as {@code TerminalTuning.DEFAULT.thing()}. Add one
 * component per tunable and give it its value where {@code DEFAULT} is built.
 */
public record TerminalTuning() {
	public static final TerminalTuning DEFAULT = new TerminalTuning();
}
