package io.github.pkeppeler.deepcharter;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pkeppeler.deepcharter.charter.CharterInit;
import io.github.pkeppeler.deepcharter.colony.ColonyInit;
import io.github.pkeppeler.deepcharter.creature.CreatureInit;
import io.github.pkeppeler.deepcharter.fuel.FuelInit;
import io.github.pkeppeler.deepcharter.handbook.HandbookInit;
import io.github.pkeppeler.deepcharter.hangar.HangarInit;
import io.github.pkeppeler.deepcharter.layer.LayerInit;
import io.github.pkeppeler.deepcharter.market.MarketInit;
import io.github.pkeppeler.deepcharter.ore.OreInit;
import io.github.pkeppeler.deepcharter.pod.PodInit;
import io.github.pkeppeler.deepcharter.repair.RepairInit;
import io.github.pkeppeler.deepcharter.scanner.ScannerInit;
import io.github.pkeppeler.deepcharter.sound.SoundInit;
import io.github.pkeppeler.deepcharter.surface.SurfaceInit;
import io.github.pkeppeler.deepcharter.terminal.TerminalInit;
import io.github.pkeppeler.deepcharter.transmission.TransmissionInit;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeInit;
import io.github.pkeppeler.deepcharter.wreck.WreckInit;

public class DeepCharter implements ModInitializer {
	public static final String MOD_ID = "deepcharter";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// One line per feature. Features register their own parts from their XInit.init(), so a new
	// part never edits this class.
	@Override
	public void onInitialize() {
		CharterInit.init();
		ColonyInit.init();
		CreatureInit.init();
		FuelInit.init();
		HandbookInit.init();
		HangarInit.init();
		LayerInit.init();
		MarketInit.init();
		OreInit.init();
		PodInit.init();
		RepairInit.init();
		ScannerInit.init();
		SoundInit.init();
		SurfaceInit.init();
		TerminalInit.init();
		TransmissionInit.init();
		UpgradeInit.init();
		WreckInit.init();
		LOGGER.info("Deep Charter initialised");
	}
}
