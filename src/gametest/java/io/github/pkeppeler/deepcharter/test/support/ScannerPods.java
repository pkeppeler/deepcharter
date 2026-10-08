package io.github.pkeppeler.deepcharter.test.support;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/** Gives a pod a scanner that counts: the player's charter owns the pod, and the part is stamped with it. */
public final class ScannerPods {
	private ScannerPods() {
	}

	/** Server only. Founds a charter for {@code owner} if they have none, registers {@code pod} to it and installs a scanner of {@code tier} (1 or more). */
	public static void fit(MinecraftServer server, ServerPlayer owner, PodEntity pod, int tier) {
		if (Charters.charterOfOrThrow(server, owner.getUUID()).isEmpty() && Charters.found(server, owner.getUUID(), "Scanner " + owner.getScoreboardName()).isPresent()) {
			throw new AssertionError("the player could not found a charter");
		}
		CharterId charter = Charters.charterOfOrThrow(server, owner.getUUID()).orElseThrow().id();
		PodComponents.register(pod, charter);
		PodComponents.install(pod, ComponentItems.mint(server, ComponentTrack.SCANNER, tier, charter));
	}
}
