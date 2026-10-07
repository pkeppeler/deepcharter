package io.github.pkeppeler.deepcharter.layer;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** Events fired by breach crossing. */
public final class BreachEvents {
	/**
	 * Fired after an entity has crossed a breach, once for each entity in the crossing vehicle tree
	 * (the vehicle itself and every passenger, however deep). Vanilla recreates a non-player entity
	 * when it changes dimension, so {@code entity} is the arrived instance, already in {@code to}.
	 */
	public static final Event<Crossed> CROSSED = EventFactory.createArrayBacked(Crossed.class, listeners -> (entity, from, to, fromLayer, toLayer) -> {
		for (Crossed listener : listeners) {
			listener.onCrossed(entity, from, to, fromLayer, toLayer);
		}
	});

	private BreachEvents() {
	}

	@FunctionalInterface
	public interface Crossed {
		/** {@code toLayer > fromLayer} is a descent; {@code toLayer < fromLayer} is an ascent. */
		void onCrossed(Entity entity, ServerLevel from, ServerLevel to, int fromLayer, int toLayer);
	}
}
