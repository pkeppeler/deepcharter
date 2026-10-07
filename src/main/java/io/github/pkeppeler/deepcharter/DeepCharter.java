package io.github.pkeppeler.deepcharter;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pkeppeler.deepcharter.layer.LayerInit;
import io.github.pkeppeler.deepcharter.pod.PodInit;
import io.github.pkeppeler.deepcharter.scanner.ScannerInit;

public class DeepCharter implements ModInitializer {
	public static final String MOD_ID = "deepcharter";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// One line per feature. Features register their own parts from their XInit.init(), so a new
	// part never edits this class.
	@Override
	public void onInitialize() {
		LayerInit.init();
		PodInit.init();
		ScannerInit.init();
		LOGGER.info("Deep Charter initialised");
	}
}
