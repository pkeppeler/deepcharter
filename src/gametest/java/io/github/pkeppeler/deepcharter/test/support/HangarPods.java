package io.github.pkeppeler.deepcharter.test.support;

import java.util.Optional;

import net.minecraft.server.MinecraftServer;

import io.github.pkeppeler.deepcharter.hangar.HangarData;
import io.github.pkeppeler.deepcharter.pod.PodEntity;

/** The hangar's founding Mole as the world holds it: the saved record names it, the overworld has the entity. */
public final class HangarPods {
	private HangarPods() {
	}

	/** The founding Mole, if the hangar names one and it is loaded; empty while its data is unreadable. */
	public static Optional<PodEntity> derelict(MinecraftServer server) {
		// entity-query: a test reads the founding Mole by the id the saved hangar names; a miss means it is not loaded
		return HangarData.readable(server).flatMap(data -> data.state().derelict())
				.map(id -> server.overworld().getEntity(id)).filter(PodEntity.class::isInstance).map(PodEntity.class::cast);
	}
}
