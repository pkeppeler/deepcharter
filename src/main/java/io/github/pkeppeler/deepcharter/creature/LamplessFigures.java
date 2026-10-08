package io.github.pkeppeler.deepcharter.creature;

import java.util.Optional;

import net.minecraft.server.level.ServerLevel;

import io.github.pkeppeler.deepcharter.layer.StructureSite;

/** Spawns figures on the rail sites of Prospector's Run. */
public final class LamplessFigures {
	private LamplessFigures() {
	}

	public static void init() {
	}

	static void faded(ServerLevel level) {
	}

	public static Optional<LamplessFigure> spawnAt(ServerLevel level, StructureSite site) {
		return Optional.empty();
	}
}
