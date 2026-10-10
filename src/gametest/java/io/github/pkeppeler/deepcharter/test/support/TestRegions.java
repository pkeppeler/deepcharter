package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TestInstanceBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The regions that GameTests hold in a level, read from the test instance blocks that mark them, so that a test which reads a
 * stretch of the world another test may be using can say whose cell it found. Only chunks that are already loaded are read.
 */
public final class TestRegions {
	/** How far outside the columns a test's block may sit and still be found: every test here uses the default structure, which is small. */
	public static final int BLOCK_REACH = 32;

	private TestRegions() {
	}

	/** One test's region: its structure box, the test's own walls and floor included. */
	public record Region(String test, BoundingBox box) {
		@Override
		public String toString() {
			return test + " " + box.minX() + " " + box.minY() + " " + box.minZ() + " to " + box.maxX() + " " + box.maxY() + " " + box.maxZ();
		}
	}

	/** The regions of the tests whose block lies in a loaded chunk within {@link #BLOCK_REACH} of the columns {@code x1..x2}, {@code z1..z2}. */
	public static List<Region> in(ServerLevel level, int x1, int x2, int z1, int z2) {
		List<Region> regions = new ArrayList<>();
		for (int chunkX = (x1 - BLOCK_REACH) >> 4; chunkX <= (x2 + BLOCK_REACH) >> 4; chunkX++) {
			for (int chunkZ = (z1 - BLOCK_REACH) >> 4; chunkZ <= (z2 + BLOCK_REACH) >> 4; chunkZ++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity entity : chunk.getBlockEntities().values()) {
					if (entity instanceof TestInstanceBlockEntity test) {
						regions.add(new Region(test.getTestName().getString(), test.getStructureBoundingBox()));
					}
				}
			}
		}
		return regions;
	}

	/** The test regions that hold the column {@code x}, {@code z}, as text; "no test block within " + BLOCK_REACH + " blocks" when none does. */
	public static String ownerOfColumn(List<Region> regions, int x, int z) {
		List<Region> owners = new ArrayList<>();
		for (Region region : regions) {
			if (x >= region.box().minX() && x <= region.box().maxX() && z >= region.box().minZ() && z <= region.box().maxZ()) {
				owners.add(region);
			}
		}
		return owners.isEmpty() ? "no test block within " + BLOCK_REACH + " blocks" : "test region " + owners;
	}
}
