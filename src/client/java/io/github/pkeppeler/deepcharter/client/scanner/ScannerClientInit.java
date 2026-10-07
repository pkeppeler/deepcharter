package io.github.pkeppeler.deepcharter.client.scanner;

/**
 * Client entry point for the scanner, called from DeepCharterClient.
 * Each part of the scanner feature registers itself from here, one line per part.
 */
public final class ScannerClientInit {
	private ScannerClientInit() {
	}

	public static void init() {
		ScannerClientRegistry.register();
		ScannerHud.init();
	}
}
