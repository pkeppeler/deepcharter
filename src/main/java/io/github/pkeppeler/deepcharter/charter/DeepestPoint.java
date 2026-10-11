package io.github.pkeppeler.deepcharter.charter;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import io.github.pkeppeler.deepcharter.layer.Depth;

/**
 * Raises each charter's deepest point from play: a member standing deeper than the charter has ever been, on foot or in a pod,
 * sets a new record. The depth is the one the HUD altimeter shows ({@link Depth#of}), so a teleport or a fall counts as well as a bore.
 */
public final class DeepestPoint {
	private DeepestPoint() {
	}

	/** Checks every online member against their charter's record. A player on no charter, or unreadable saved charters, change nothing. */
	public static void poll(MinecraftServer server) {
		if (!Charters.isReadable(server)) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.isSpectator()) {
				continue;
			}
			Charters.readableCharterOf(server, player.getUUID()).ifPresent(charter -> {
				int depth = Depth.of(player.level(), player.getBlockY());
				if (depth > charter.deepestPoint()) {
					Charters.recordDeepestPoint(server, charter.id(), depth);
				}
			});
		}
	}
}
