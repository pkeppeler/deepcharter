package io.github.pkeppeler.deepcharter.client;

import net.fabricmc.api.ClientModInitializer;

import io.github.pkeppeler.deepcharter.client.layer.LayerClientInit;
import io.github.pkeppeler.deepcharter.client.pod.PodClientInit;
import io.github.pkeppeler.deepcharter.client.scanner.ScannerClientInit;

public class DeepCharterClient implements ClientModInitializer {
	// One line per feature. Features register their own parts from their XClientInit.init(), so a
	// new part never edits this class.
	@Override
	public void onInitializeClient() {
		LayerClientInit.init();
		PodClientInit.init();
		ScannerClientInit.init();
	}
}
