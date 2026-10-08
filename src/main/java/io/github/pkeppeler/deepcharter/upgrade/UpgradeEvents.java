package io.github.pkeppeler.deepcharter.upgrade;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import io.github.pkeppeler.deepcharter.pod.PodEntity;

/** Server-side events of the upgrade feature. */
public final class UpgradeEvents {
	/**
	 * A player bought a part at the upgrade terminal, paid for it and installed it in {@code pod}. Fires after the change is made,
	 * from inside the buy action, so before the acting player's screen is refreshed and before {@code TerminalEvents.ACTED}. A
	 * listener that throws stops the call: the purchase has taken effect, but the screen is not refreshed and the {@code ACTED}
	 * listeners (the sale's sound, the handbook's purchase directives) do not run.
	 */
	public static final Event<Bought> BOUGHT = EventFactory.createArrayBacked(Bought.class, listeners -> (server, player, pod, track, tier) -> {
		for (Bought listener : listeners) {
			listener.onBought(server, player, pod, track, tier);
		}
	});

	private UpgradeEvents() {
	}

	@FunctionalInterface
	public interface Bought {
		void onBought(MinecraftServer server, ServerPlayer player, PodEntity pod, ComponentTrack track, int tier);
	}
}
