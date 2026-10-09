package io.github.pkeppeler.deepcharter.texture;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * How a connected casing joins its neighbours (ADR 0037). Each face of the block is drawn as four quarters, and each quarter takes
 * the matching quarter of one of five {@link Tile} textures, picked from the two neighbours beside that corner and the one across
 * it. A wall of casing then shows its border only round the outside, so it reads as one plate.
 *
 * <p>A neighbour joins when it is the same block and its own face on this side is not covered by the same block (so the inside
 * of an L of casing keeps its border). The client model ({@code client/texture/ConnectedBlockModel}) draws what this picks; the
 * rule lives here so a server GameTest can check it on a real level.
 */
public final class CasingConnections {
	/** The five textures of a connected casing. The blockstate file names one texture for each. */
	public enum Tile {
		/** Joined on neither side of the corner: the outer corner of the border. */
		ALONE,
		/** Joined left or right only: the border runs along the top or bottom edge. */
		HORIZONTAL,
		/** Joined above or below only: the border runs along the left or right edge. */
		VERTICAL,
		/** Joined on both sides but not across the corner: the inside corner of an L, a nub of border. */
		CORNER,
		/** Joined on both sides and across: no border. */
		CENTRE
	}

	/** The tile of each quarter of a face, as seen from outside the block. */
	public record FaceTiles(Tile topLeft, Tile topRight, Tile bottomLeft, Tile bottomRight) {
	}

	private CasingConnections() {
	}

	/** The world direction of a face's right edge, seen from outside: the frame Fabric's {@code QuadEmitter.square} lays a face in. */
	public static Direction right(Direction face) {
		return switch (face) {
			case NORTH -> Direction.WEST;
			case SOUTH -> Direction.EAST;
			case EAST -> Direction.NORTH;
			case WEST -> Direction.SOUTH;
			case UP, DOWN -> Direction.EAST;
		};
	}

	/** The world direction of a face's top edge, seen from outside, in the same frame as {@link #right}. */
	public static Direction up(Direction face) {
		return switch (face) {
			case NORTH, SOUTH, EAST, WEST -> Direction.UP;
			case UP -> Direction.NORTH;
			case DOWN -> Direction.SOUTH;
		};
	}

	/** The tiles of the four quarters of {@code face} of the casing {@code state} at {@code pos}. */
	public static FaceTiles tiles(BlockGetter level, BlockPos pos, BlockState state, Direction face) {
		Direction right = right(face);
		Direction up = up(face);
		Direction left = right.getOpposite();
		Direction down = up.getOpposite();
		boolean leftJoined = joins(level, pos.relative(left), state, face);
		boolean rightJoined = joins(level, pos.relative(right), state, face);
		boolean aboveJoined = joins(level, pos.relative(up), state, face);
		boolean belowJoined = joins(level, pos.relative(down), state, face);
		return new FaceTiles(
				tile(leftJoined, aboveJoined, joins(level, pos.relative(up).relative(left), state, face)),
				tile(rightJoined, aboveJoined, joins(level, pos.relative(up).relative(right), state, face)),
				tile(leftJoined, belowJoined, joins(level, pos.relative(down).relative(left), state, face)),
				tile(rightJoined, belowJoined, joins(level, pos.relative(down).relative(right), state, face)));
	}

	private static Tile tile(boolean beside, boolean aboveOrBelow, boolean across) {
		if (beside && aboveOrBelow) {
			return across ? Tile.CENTRE : Tile.CORNER;
		}
		if (beside) {
			return Tile.HORIZONTAL;
		}
		return aboveOrBelow ? Tile.VERTICAL : Tile.ALONE;
	}

	private static boolean joins(BlockGetter level, BlockPos neighbour, BlockState casing, Direction face) {
		return level.getBlockState(neighbour).is(casing.getBlock()) && !level.getBlockState(neighbour.relative(face)).is(casing.getBlock());
	}
}
