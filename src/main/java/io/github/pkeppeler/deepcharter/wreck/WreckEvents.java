package io.github.pkeppeler.deepcharter.wreck;

import java.util.OptionalInt;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.pod.PodEntity;

/** Server-side events of the wreck feature. */
public final class WreckEvents {
	/**
	 * A charter was told that a pod of its crew became a wreck: the chat message to its online members has been sent.
	 * Fires once for each charter on the pod when it went down, so a pod with no crew on a charter fires nothing.
	 * {@code layer} is empty on the surface. A listener that throws is logged and does not stop the others.
	 */
	public static final Event<Reported> REPORTED = EventFactory.createArrayBacked(Reported.class, listeners -> (server, charter, pod, layer, pos) -> {
		for (Reported listener : listeners) {
			try {
				listener.onReported(server, charter, pod, layer, pos);
			} catch (RuntimeException e) {
				DeepCharter.LOGGER.error("A wreck report listener failed for pod {}: the other listeners still run", pod.getUUID(), e);
			}
		}
	});

	private WreckEvents() {
	}

	@FunctionalInterface
	public interface Reported {
		void onReported(MinecraftServer server, Charter charter, PodEntity pod, OptionalInt layer, BlockPos pos);
	}
}
