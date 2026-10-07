package io.github.pkeppeler.deepcharter.transmission;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import io.github.pkeppeler.deepcharter.charter.CharterEvents;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;

/**
 * Registers the transmission payload and every hook that fires or delivers a transmission: login, founding and joining a charter
 * (delivery and replay), and the zone and breach triggers. The saved data needs no registration.
 */
public final class TransmissionRegistry {
	private TransmissionRegistry() {
	}

	public static void register() {
		TransmissionPayload.register();
		ServerLifecycleEvents.SERVER_STARTED.register(Transmissions::started);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> Transmissions.stopped());
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> Transmissions.deliverOnLogin(server, handler.getPlayer()));
		CharterEvents.FOUNDED.register((server, charter) -> Transmissions.replayTo(server, TransmissionData.get(server), charter.id()));
		CharterEvents.JOINED.register((server, charter, player) -> Transmissions.deliver(server, TransmissionData.get(server), charter.id()));
		BreachEvents.CROSSED.register(TransmissionTriggers::onCrossed);
		ServerTickEvents.END_SERVER_TICK.register(TransmissionTriggers::pollZones);
	}
}
