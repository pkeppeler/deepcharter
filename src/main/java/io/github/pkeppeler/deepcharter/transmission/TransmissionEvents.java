package io.github.pkeppeler.deepcharter.transmission;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import io.github.pkeppeler.deepcharter.charter.Charter;

/** Server-side events of the transmission feature. */
public final class TransmissionEvents {
	/**
	 * A transmission was sent to one online charter member. Fires once per member per transmission, in queue order, whether or not the
	 * player's client can show it (a player without the mod gets the event and no packet). The queue has been emptied before it fires.
	 */
	public static final Event<Delivered> DELIVERED = EventFactory.createArrayBacked(Delivered.class, listeners -> (server, charter, player, transmission) -> {
		for (Delivered listener : listeners) {
			listener.onDelivered(server, charter, player, transmission);
		}
	});

	private TransmissionEvents() {
	}

	@FunctionalInterface
	public interface Delivered {
		void onDelivered(MinecraftServer server, Charter charter, ServerPlayer player, Transmission transmission);
	}
}
