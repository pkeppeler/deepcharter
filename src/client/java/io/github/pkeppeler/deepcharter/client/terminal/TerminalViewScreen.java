package io.github.pkeppeler.deepcharter.client.terminal;

import io.github.pkeppeler.deepcharter.terminal.TerminalView;

/**
 * A terminal screen that takes a newer view of its terminal without being replaced, so a text field or a scroll position in it
 * survives the server's answer to an action. {@link TerminalScreen} implements it, and so should the online screen of a feature.
 * A screen that does not implement it is replaced by a new one on every view.
 */
public interface TerminalViewScreen {
	/** True when this screen can show {@code view} in place: the same terminal, and nothing about it that needs a new screen. */
	boolean accepts(TerminalView view);

	/** Shows {@code view}. Only called after {@link #accepts} returned true. */
	void update(TerminalView view);
}
