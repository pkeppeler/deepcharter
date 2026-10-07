package io.github.pkeppeler.deepcharter.scanner;

/**
 * Tunables for the scanner feature. Add one component per tunable and give it its value in
 * {@link #DEFAULT}; read it as {@code ScannerTuning.DEFAULT.thing()}.
 */
public record ScannerTuning() {
	public static final ScannerTuning DEFAULT = new ScannerTuning();
}
