package io.github.pkeppeler.deepcharter.colony;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import net.minecraft.server.MinecraftServer;

/** Server-side events of the colony feature. */
public final class ColonyEvents {
	/**
	 * The colony was built, recorded in {@link ColonySite} and the world spawn set to the Continuity Office. Fires once per
	 * world, at server start, and never for a world whose colony was built before. A listener that throws stops the server start.
	 */
	public static final Event<Built> BUILT = EventFactory.createArrayBacked(Built.class, listeners -> (server, colony) -> {
		for (Built listener : listeners) {
			listener.onBuilt(server, colony);
		}
	});

	private ColonyEvents() {
	}

	@FunctionalInterface
	public interface Built {
		void onBuilt(MinecraftServer server, ColonySite.Placed colony);
	}
}
