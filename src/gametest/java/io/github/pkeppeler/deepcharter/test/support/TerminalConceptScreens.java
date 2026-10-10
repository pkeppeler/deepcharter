package io.github.pkeppeler.deepcharter.test.support;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;

import io.github.pkeppeler.deepcharter.client.charter.terminal.ContractScreen;
import io.github.pkeppeler.deepcharter.client.hangar.HangarScreen;
import io.github.pkeppeler.deepcharter.client.market.OreProcessorScreen;
import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeScreen;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.test.support.TerminalConceptScene.Scene;

/** Opens the five screens of the terminal concept round on the real server, and waits until each has typed out its intro. */
public final class TerminalConceptScreens {
	/** The screens of the first world, by the id the stills use. */
	public static final List<String> SCENE_SCREENS = List.of("hangar", "processor", "upgrade", "repair");
	/** The screen of the second world. */
	public static final String CONTRACT = "contract";

	private TerminalConceptScreens() {
	}

	/** Opens the screen {@code id} at its terminal and waits until its intro has typed out and its content is there. Returns the screen. */
	public static Screen open(ClientGameTestContext context, String id, Scene scene, BlockPos contract) {
		BlockPos at = switch (id) {
			case "hangar" -> scene.hangar();
			case "processor" -> scene.processor();
			case "upgrade" -> scene.upgrade();
			case "repair" -> scene.repair();
			case CONTRACT -> contract;
			default -> throw new IllegalArgumentException("no terminal concept screen " + id);
		};
		Class<? extends Screen> type = switch (id) {
			case "hangar" -> HangarScreen.class;
			case "processor" -> OreProcessorScreen.class;
			case "upgrade" -> UpgradeScreen.class;
			case "repair" -> RepairStationScreen.class;
			default -> ContractScreen.class;
		};
		context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(at)));
		ClientWait.screen(context, type);
		ClientWait.until(context, id + " typed out and filled in", client -> client.gui.screen() != null && typed(client.gui.screen()));
		context.waitTicks(4); // tick-wait: lets the last typed letter and the server's follow-up views settle into a still frame
		return context.computeOnClient(client -> client.gui.screen());
	}

	/** True when the screen's intro has typed out and, for the screens that wait for the server, what the server sends has come. */
	private static boolean typed(Screen screen) {
		return switch (screen) {
			case HangarScreen hangar -> done(hangar.typewriter());
			case OreProcessorScreen processor -> done(processor.typewriter());
			case UpgradeScreen upgrade -> done(upgrade.typewriter()) && upgrade.upgrade().flatMap(view -> view.pod()).isPresent();
			case RepairStationScreen repair -> done(repair.typewriter());
			case ContractScreen contract -> done(contract.typewriter()) && contract.state().isPresent();
			default -> false;
		};
	}

	private static boolean done(Typewriter typewriter) {
		return typewriter.done();
	}

	public static void close(ClientGameTestContext context) {
		context.setScreen(() -> null);
	}
}
