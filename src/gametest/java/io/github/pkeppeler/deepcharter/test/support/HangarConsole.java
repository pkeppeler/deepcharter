package io.github.pkeppeler.deepcharter.test.support;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;

/** The hangar console where the world holds it: the placed block, found near the hangar anchor, not a place computed from the layout. */
public final class HangarConsole {
	/** How far from the hangar anchor the console may stand and still be found. */
	private static final int REACH = 8;

	private HangarConsole() {
	}

	/** The placed console, loading the chunk the hangar anchor is in; empty before the colony is built or when no console stands near the bay. */
	public static Optional<BlockPos> find(MinecraftServer server) {
		return Colony.anchor(server, ColonyAnchor.HANGAR).flatMap(anchor -> {
			ServerLevel overworld = server.overworld();
			overworld.getChunk(anchor);
			return BlockPos.betweenClosedStream(anchor.offset(-REACH, -REACH, -REACH), anchor.offset(REACH, REACH, REACH))
					.filter(pos -> overworld.getBlockState(pos).is(HangarTerminal.TYPE.block())).findFirst().map(BlockPos::immutable);
		});
	}
}
