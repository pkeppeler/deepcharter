package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Scans generated chunks for a block, as the salvage tests and the gather scenario do. */
public final class BlockSurvey {
	private BlockSurvey() {
	}

	/** Every position of {@code wanted} in the chunks within {@code chunks} of the origin, generating them. */
	public static List<BlockPos> find(ServerLevel level, int chunks, Block wanted) {
		List<BlockPos> found = new ArrayList<>();
		Predicate<BlockState> isIt = state -> state.is(wanted);
		for (int chunkX = -chunks; chunkX <= chunks; chunkX++) {
			for (int chunkZ = -chunks; chunkZ <= chunks; chunkZ++) {
				LevelChunk chunk = level.getChunk(chunkX, chunkZ);
				LevelChunkSection[] sections = chunk.getSections();
				for (int index = 0; index < sections.length; index++) {
					if (!sections[index].maybeHas(isIt)) {
						continue;
					}
					int baseY = chunk.getSectionYFromSectionIndex(index) << 4;
					for (int x = 0; x < 16; x++) {
						for (int y = 0; y < 16; y++) {
							for (int z = 0; z < 16; z++) {
								if (isIt.test(sections[index].getBlockState(x, y, z))) {
									found.add(new BlockPos((chunkX << 4) + x, baseY + y, (chunkZ << 4) + z));
								}
							}
						}
					}
				}
			}
		}
		return found;
	}
}
