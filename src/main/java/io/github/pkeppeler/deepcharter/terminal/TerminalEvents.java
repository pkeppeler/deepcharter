package io.github.pkeppeler.deepcharter.terminal;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import io.github.pkeppeler.deepcharter.charter.Charter;

/** Server-side events of the terminal feature. They fire from {@link Terminals} after the change is made and saved. */
public final class TerminalEvents {
	/**
	 * The last part went into a terminal, which is now repaired for every charter. Fires once per terminal type per world, with
	 * the charter and player that made the repair. A listener must not insert parts from inside the event.
	 * A listener that throws stops the call, so the acting player's screen is not refreshed.
	 */
	public static final Event<Repaired> REPAIRED = EventFactory.createArrayBacked(Repaired.class, listeners -> (server, type, charter, player) -> {
		for (Repaired listener : listeners) {
			listener.onRepaired(server, type, charter, player);
		}
	});

	/** A player opened a terminal: it passed every check and the server is sending its screen. Not fired for a refusal. */
	public static final Event<Opened> OPENED = EventFactory.createArrayBacked(Opened.class, listeners -> (server, type, player) -> {
		for (Opened listener : listeners) {
			listener.onOpened(server, type, player);
		}
	});

	private TerminalEvents() {
	}

	@FunctionalInterface
	public interface Opened {
		void onOpened(MinecraftServer server, TerminalType type, ServerPlayer player);
	}

	@FunctionalInterface
	public interface Repaired {
		void onRepaired(MinecraftServer server, TerminalType type, Charter charter, ServerPlayer player);
	}
}
