package io.github.pkeppeler.deepcharter.colony;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The API of the colony for the rest of the mod. Every method is safe on a tick, join or callback path: before the colony is
 * built, or when its saved data is unreadable, it answers empty, and the unreadable data is logged once. Call on the server thread.
 */
public final class Colony {
	private Colony() {
	}

	/** The colony site if its saved data is readable; otherwise empty, after logging that once per site. */
	public static Optional<ColonySite> readable(MinecraftServer server) {
		ColonySite site = ColonySite.get(server);
		if (site.isReadable()) {
			return Optional.of(site);
		}
		if (site.firstUnreadableReport()) {
			DeepCharter.LOGGER.error("The saved colony has version {} that this build cannot read: the colony is not built or changed until the world is opened by a build that reads it",
					site.unreadableVersion().orElse("?"));
		}
		return Optional.empty();
	}

	/** The built colony, or empty before it is built or when its data is unreadable. */
	public static Optional<ColonySite.Placed> placed(MinecraftServer server) {
		return readable(server).flatMap(ColonySite::placed);
	}

	/** Where {@code anchor} is in the overworld, or empty before the colony is built or when its data is unreadable. */
	public static Optional<BlockPos> anchor(MinecraftServer server, ColonyAnchor anchor) {
		return placed(server).map(placed -> placed.anchors().get(anchor));
	}

	/**
	 * The colony's respawn point, the Continuity Office, for a crew that has no other. Empty before the colony is built or
	 * when its data is unreadable. Wrecks move a respawned crew here one tick after the respawn.
	 */
	public static Optional<GlobalPos> respawnPoint(MinecraftServer server) {
		return anchor(server, ColonyAnchor.CONTINUITY_OFFICE).map(pos -> GlobalPos.of(Level.OVERWORLD, pos));
	}
}
