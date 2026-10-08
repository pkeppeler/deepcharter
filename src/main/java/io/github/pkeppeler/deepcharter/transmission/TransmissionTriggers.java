package io.github.pkeppeler.deepcharter.transmission;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.Zones;

/**
 * The triggers this feature watches itself: a charter member descending through a breach, and a charter member standing in a zone.
 * A player on no charter fires nothing. The third kind, the event, is fired by the feature that owns the event, through
 * {@link Transmissions#fire}.
 */
public final class TransmissionTriggers {
	private TransmissionTriggers() {
	}

	/** A descent fires the transmissions of the breach into the layer, and the climb out into the surface fires those of the surface. Another ascent fires nothing. */
	public static void onCrossed(Entity entity, ServerLevel from, ServerLevel to, int fromLayer, int toLayer) {
		if (!(entity instanceof ServerPlayer player) || (toLayer < fromLayer && toLayer != LayerChain.SURFACE)) {
			return;
		}
		MinecraftServer server = from.getServer();
		if (!Charters.isReadable(server)) {
			return;
		}
		Charters.charterOf(server, player.getUUID()).ifPresent(charter ->
				TransmissionCatalog.forBreach(toLayer).forEach(transmission -> Transmissions.fire(server, charter.id(), transmission.id())));
	}

	/** Fires the transmissions of the zone each charter member stands in. */
	public static void pollZones(MinecraftServer server) {
		if (!Charters.isReadable(server)) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Charters.charterOf(server, player.getUUID()).ifPresent(charter -> {
				// The poll is also where a bonus that a full account refused is tried again.
				Transmissions.payPending(server, TransmissionData.get(server), charter.id());
				Zones.of(player.level(), player.getBlockY()).ifPresent(zone ->
						TransmissionCatalog.forZone(zone.layer(), zone.index())
								.forEach(transmission -> Transmissions.fire(server, charter.id(), transmission.id())));
			});
		}
	}
}
