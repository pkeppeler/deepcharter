package io.github.pkeppeler.deepcharter.terminal;

/**
 * The data a terminal type adds to its {@link TerminalView}. A type attaches it with {@link TerminalFeatures#register}; the
 * screen reads it with {@link TerminalView#feature}. It is sent whole with every view, so a screen never merges.
 */
public interface TerminalFeature {
}
