package io.github.pkeppeler.deepcharter.scanner;

/**
 * Server entry point for the scanner, called from DeepCharter.
 * Each part of the scanner feature registers itself from here, one line per part.
 */
public final class ScannerInit {
	private ScannerInit() {
	}

	public static void init() {
		ScannerRegistry.register();
	}
}
