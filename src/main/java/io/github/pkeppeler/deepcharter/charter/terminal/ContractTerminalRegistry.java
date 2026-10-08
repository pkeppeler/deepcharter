package io.github.pkeppeler.deepcharter.charter.terminal;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterEvents;
import io.github.pkeppeler.deepcharter.terminal.TerminalEvents;

/**
 * Registers the contract terminal, its payload, and the listeners that keep an open screen current. The charter events fire
 * for every route (the screen, the operator commands, another mod), so a Director's screen shows an application the moment it
 * is made, whoever made it.
 */
public final class ContractTerminalRegistry {
	private ContractTerminalRegistry() {
	}

	public static void register() {
		ContractTerminal.register();
		ContractStatePayload.register();
		TerminalEvents.OPENED.register((server, type, player) -> {
			if (type == ContractTerminal.TYPE) {
				ContractTerminal.refresh(server, player.getUUID());
			}
		});
		CharterEvents.FOUNDED.register((server, charter) -> {
			charter.director().ifPresent(director -> ContractTerminal.refresh(server, director));
			ContractTerminal.refreshUnaffiliated(server);
		});
		CharterEvents.WENT_DORMANT.register((server, charter) -> ContractTerminal.refreshUnaffiliated(server));
		CharterEvents.REVIVED.register((server, charter, director) -> {
			ContractTerminal.refresh(server, director);
			ContractTerminal.refreshUnaffiliated(server);
		});
		CharterEvents.APPLIED.register((server, charter, applicant) -> refreshAround(server, charter, applicant));
		CharterEvents.JOINED.register((server, charter, player) -> refreshAround(server, charter, player));
		CharterEvents.LEFT.register((server, charter, player) -> refreshAround(server, charter, player));
	}

	/** Refreshes the Director of {@code charter} and {@code player}, whose application, roster place or absence just changed. */
	private static void refreshAround(MinecraftServer server, Charter charter, UUID player) {
		ContractTerminal.refresh(server, player);
		charter.director().ifPresent(director -> ContractTerminal.refresh(server, director));
	}
}
