package io.github.pkeppeler.deepcharter.client;

import net.fabricmc.api.ClientModInitializer;

import io.github.pkeppeler.deepcharter.client.charter.CharterClientInit;
import io.github.pkeppeler.deepcharter.client.creature.CreatureClientInit;
import io.github.pkeppeler.deepcharter.client.fuel.FuelClientInit;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookClientInit;
import io.github.pkeppeler.deepcharter.client.hangar.HangarClientInit;
import io.github.pkeppeler.deepcharter.client.layer.LayerClientInit;
import io.github.pkeppeler.deepcharter.client.market.MarketClientInit;
import io.github.pkeppeler.deepcharter.client.ore.OreClientInit;
import io.github.pkeppeler.deepcharter.client.pod.PodClientInit;
import io.github.pkeppeler.deepcharter.client.repair.RepairClientInit;
import io.github.pkeppeler.deepcharter.client.scanner.ScannerClientInit;
import io.github.pkeppeler.deepcharter.client.sound.SoundClientInit;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalClientInit;
import io.github.pkeppeler.deepcharter.client.theme.ThemeClientInit;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionClientInit;
import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeClientInit;
import io.github.pkeppeler.deepcharter.client.wreck.WreckClientInit;

public class DeepCharterClient implements ClientModInitializer {
	// One line per feature. Features register their own parts from their XClientInit.init(), so a
	// new part never edits this class.
	@Override
	public void onInitializeClient() {
		CharterClientInit.init();
		CreatureClientInit.init();
		FuelClientInit.init();
		HandbookClientInit.init();
		HangarClientInit.init();
		LayerClientInit.init();
		MarketClientInit.init();
		OreClientInit.init();
		PodClientInit.init();
		RepairClientInit.init();
		ScannerClientInit.init();
		SoundClientInit.init();
		TerminalClientInit.init();
		ThemeClientInit.init();
		TransmissionClientInit.init();
		UpgradeClientInit.init();
		WreckClientInit.init();
	}
}
