package io.github.pkeppeler.deepcharter.layer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Keeps a client from being sent a chunk before the light of blocks just placed in it is computed (issue 427). Light is computed off the
 * server thread, after {@code setBlock} returns. A chunk that is sent while that work is pending carries the old light, and vanilla then
 * drops the change that would correct it: {@code ChunkHolder.sectionLightChanged} ignores a chunk that is not yet ticking, so no
 * light packet follows. The client keeps the old light (the sky light of the open air in a sealed room, the glow of lava that is gone).
 *
 * <p>Vanilla's own answer is {@code ChunkMap.waitForLightBeforeSending}, which worldgen uses: a chunk is sent only after the light
 * engine has run the tasks queued for it. A caller that places blocks in a chunk a player may still be sent (a structure drawn as its
 * chunk loads, a pocket carved as a player crosses into it) calls {@link #holdSendUntilLit} after the last block.
 */
public final class LightSync {
	private LightSync() {
	}

	/** Holds the sending of every chunk the box touches, and of their neighbours that the light spreads into, until the light queued so far is computed. Call after the last block is set. */
	public static void holdSendUntilLit(ServerLevel level, BlockPos min, BlockPos max) {
		for (int chunkX = min.getX() >> 4; chunkX <= max.getX() >> 4; chunkX++) {
			for (int chunkZ = min.getZ() >> 4; chunkZ <= max.getZ() >> 4; chunkZ++) {
				holdSendUntilLit(level, new ChunkPos(chunkX, chunkZ));
			}
		}
	}

	/** As {@link #holdSendUntilLit(ServerLevel, BlockPos, BlockPos)}, for one chunk and its neighbours. */
	public static void holdSendUntilLit(ServerLevel level, ChunkPos chunk) {
		level.getChunkSource().chunkMap.waitForLightBeforeSending(chunk, 0);
	}
}
