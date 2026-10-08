package io.github.pkeppeler.deepcharter.hangar;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import io.github.pkeppeler.deepcharter.pod.PodEntity;

/** Server-side events of the hangar feature. */
public final class HangarEvents {
	/**
	 * A player restored a wreck at the hangar console: the pod is whole and registered to the player's charter, and the money and
	 * catalyst are taken. Fires after the change is made. A listener that throws stops the call, but the restore has taken effect.
	 */
	public static final Event<Restored> RESTORED = EventFactory.createArrayBacked(Restored.class, listeners -> (server, player, pod) -> {
		for (Restored listener : listeners) {
			listener.onRestored(server, player, pod);
		}
	});

	private HangarEvents() {
	}

	@FunctionalInterface
	public interface Restored {
		void onRestored(MinecraftServer server, ServerPlayer player, PodEntity pod);
	}
}
