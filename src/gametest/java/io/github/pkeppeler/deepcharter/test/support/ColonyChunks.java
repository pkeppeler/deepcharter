package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;

import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.colony.ColonyTuning;

/** The chunks of the colony's pad, for a test that waits for them to tick (an entity in a chunk that does not tick is not found). */
public final class ColonyChunks {
	private ColonyChunks() {
	}

	/** One position, at the pad's ground, in each chunk the pad touches. */
	public static List<BlockPos> of(ColonySite.Placed colony) {
		int half = ColonyTuning.DEFAULT.padSize() / 2;
		BlockPos centre = colony.center();
		Set<Long> seen = new HashSet<>();
		List<BlockPos> chunks = new ArrayList<>();
		for (int x = centre.getX() - half; x < centre.getX() + half + 16; x += 16) {
			for (int z = centre.getZ() - half; z < centre.getZ() + half + 16; z += 16) {
				int chunkX = Math.min(x, centre.getX() + half - 1) >> 4;
				int chunkZ = Math.min(z, centre.getZ() + half - 1) >> 4;
				if (seen.add(((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL))) {
					chunks.add(new BlockPos(chunkX << 4, centre.getY(), chunkZ << 4));
				}
			}
		}
		return chunks;
	}
}
