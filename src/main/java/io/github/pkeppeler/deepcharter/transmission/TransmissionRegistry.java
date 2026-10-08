package io.github.pkeppeler.deepcharter.transmission;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import io.github.pkeppeler.deepcharter.charter.CharterEvents;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;

/**
 * Registers the transmission payload and every hook that fires or delivers a transmission: login, founding, joining and leaving a charter
 * (delivery, replay and each member's place), and the zone and breach triggers. The saved data needs no registration.
 */
public final class TransmissionRegistry {
	private TransmissionRegistry() {
	}

	public static void register() {
		// Read the data file now: a broken file stops the game at start-up, never in a tick, a login or a crossing.
		TransmissionCatalog.all();
		TransmissionPayload.register();
		ServerLifecycleEvents.SERVER_STARTED.register(Transmissions::started);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> Transmissions.stopped());
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> Transmissions.deliverOnLogin(server, handler.getPlayer()));
		CharterEvents.FOUNDED.register((server, charter) -> Transmissions.replayTo(server, TransmissionData.get(server), charter.id()));
		CharterEvents.REVIVED.register((server, charter, director) -> Transmissions.deliver(server, TransmissionData.get(server), charter.id()));
		CharterEvents.JOINED.register((server, charter, player) -> Transmissions.deliver(server, TransmissionData.get(server), charter.id()));
		CharterEvents.LEFT.register((server, charter, player) -> Transmissions.forget(server, TransmissionData.get(server), charter.id(), player));
		BreachEvents.CROSSED.register(TransmissionTriggers::onCrossed);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % TransmissionTuning.DEFAULT.zonePollTicks() == 0) {
				TransmissionTriggers.pollZones(server);
			}
		});
	}
}
