package io.github.pkeppeler.deepcharter.layer;

import java.util.SplittableRandom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import io.github.pkeppeler.deepcharter.colony.ColonyBlocks;

/**
 * Draws one structure into one chunk. A structure is drawn in its own coordinates: {@code u} along its long axis, {@code v}
 * across it, {@code y} up from the floor of its hollow. Only the blocks inside the chunk are set, so every chunk that a
 * structure touches draws its own part, and the parts meet. A random choice ({@link #roll}) depends on the coordinates
 * only, never on the order of drawing, so the parts always agree.
 *
 * <p>Blocks are set without neighbour updates and without {@code onPlace}, so a rail or a candle never reaches into a
 * neighbouring chunk that is not loaded. The Conduit's casing is never overwritten. {@link #seal} runs before the carve.
 */
final class StructurePlan {
	private static final int FLAGS = Block.UPDATE_SKIP_ON_PLACE;

	private final StructureSite site;
	private final ServerLevel level;
	private final LevelChunk chunk;
	private final ChunkPos chunkPos;

	StructurePlan(StructureSite site, ServerLevel level, LevelChunk chunk) {
		this.site = site;
		this.level = level;
		this.chunk = chunk;
		this.chunkPos = chunk.getPos();
	}

	/**
	 * Replaces every fluid and gas pocket in the structure's bounds and the one-block shell round them (the part in this chunk)
	 * with stone, before the carve: the walls, roof and floor are rock that the zone fill (ADR 0015) may have left lava or gas
	 * in, and lava has no fluid tick until a neighbour changes. The neighbour chunk seals its own part of the shell. The flags
	 * wake nothing, so no lava beside the shell is told that its neighbour changed. See {@link RoomSeal}.
	 */
	void seal() {
		BoundingBox bounds = site.bounds();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = Math.max(bounds.minX() - 1, chunkPos.getMinBlockX()); x <= Math.min(bounds.maxX() + 1, chunkPos.getMaxBlockX()); x++) {
			for (int z = Math.max(bounds.minZ() - 1, chunkPos.getMinBlockZ()); z <= Math.min(bounds.maxZ() + 1, chunkPos.getMaxBlockZ()); z++) {
				for (int y = Math.max(bounds.minY() - 1, level.getMinY()); y <= Math.min(bounds.maxY() + 1, level.getMaxY()); y++) {
					pos.set(x, y, z);
					if (RoomSeal.needsSealing(chunk.getBlockState(pos))) {
						chunk.setBlockState(pos, Blocks.STONE.defaultBlockState(), FLAGS);
						level.getLightEngine().checkBlock(pos);
					}
				}
			}
		}
	}

	void set(int u, int y, int v, BlockState state) {
		int x = site.origin().getX() + (site.alongZ() ? v : u);
		int z = site.origin().getZ() + (site.alongZ() ? u : v);
		if (x < chunkPos.getMinBlockX() || x > chunkPos.getMaxBlockX() || z < chunkPos.getMinBlockZ() || z > chunkPos.getMaxBlockZ()) {
			return;
		}
		BlockPos pos = new BlockPos(x, site.origin().getY() + y, z);
		BlockState old = chunk.getBlockState(pos);
		if (old.is(ColonyBlocks.CONDUIT) || old.equals(state)) {
			return;
		}
		chunk.setBlockState(pos, state, FLAGS);
		// The chunk was lit when it generated, so a change to the light is told to the light engine.
		level.getLightEngine().checkBlock(pos);
	}

	void box(int u1, int y1, int v1, int u2, int y2, int v2, BlockState state) {
		for (int u = Math.min(u1, u2); u <= Math.max(u1, u2); u++) {
			for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
				for (int v = Math.min(v1, v2); v <= Math.max(v1, v2); v++) {
					set(u, y, v, state);
				}
			}
		}
	}

	void air(int u1, int y1, int v1, int u2, int y2, int v2) {
		box(u1, y1, v1, u2, y2, v2, Blocks.AIR.defaultBlockState());
	}

	/** True with the given chance, and the same answer for the same place in the same structure every time. */
	boolean roll(int u, int y, int v, double chance) {
		return new SplittableRandom(site.seed() ^ u * 73856093L ^ y * 19349663L ^ v * 83492791L).nextDouble() < chance;
	}

	/** A rail that runs along the structure's long axis. */
	BlockState railAlongU() {
		return Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, site.alongZ() ? RailShape.NORTH_SOUTH : RailShape.EAST_WEST);
	}

	/** The world direction of a direction given in the structure's own axes: east is {@code +u}, south is {@code +v}. */
	Direction facing(Direction local) {
		if (!site.alongZ()) {
			return local;
		}
		return switch (local) {
			case EAST -> Direction.SOUTH;
			case SOUTH -> Direction.EAST;
			case WEST -> Direction.NORTH;
			case NORTH -> Direction.WEST;
			default -> local;
		};
	}
}
