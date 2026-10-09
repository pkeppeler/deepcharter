package io.github.pkeppeler.deepcharter.test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import io.github.pkeppeler.deepcharter.colony.ColonyBlocks;
import io.github.pkeppeler.deepcharter.texture.CasingConnections;
import io.github.pkeppeler.deepcharter.texture.CasingConnections.FaceTiles;
import io.github.pkeppeler.deepcharter.texture.CasingConnections.Tile;

/**
 * Server GameTests for #242 (ADR 0037): which tile each quarter of a connected casing's face takes, on a real level, with the
 * Conduit's casing. Walls are built in the plane of the north face (x and y), so "left" on that face is east (+x): the face is
 * seen from the north, looking south.
 */
public class ConnectedCasingTest {
	private static final BlockState CASING = ColonyBlocks.CONDUIT.defaultBlockState();

	/** Sets casing at each (x, y) of the wall, at z 2, and returns the absolute position of the first. */
	private static BlockPos wall(GameTestHelper helper, int[]... cells) {
		for (int[] cell : cells) {
			helper.setBlock(new BlockPos(cell[0], cell[1], 2), CASING);
		}
		return helper.absolutePos(new BlockPos(cells[0][0], cells[0][1], 2));
	}

	private static FaceTiles north(GameTestHelper helper, int x, int y) {
		return CasingConnections.tiles(helper.getLevel(), helper.absolutePos(new BlockPos(x, y, 2)), CASING, Direction.NORTH);
	}

	private static void expect(GameTestHelper helper, String what, FaceTiles expected, FaceTiles actual) {
		if (!expected.equals(actual)) {
			throw helper.assertionException("%s: expected %s, got %s", what, expected, actual);
		}
	}

	private static FaceTiles all(Tile tile) {
		return new FaceTiles(tile, tile, tile, tile);
	}

	/** The face frame the model lays quarters in, which must match Fabric's QuadEmitter.square. A changed frame mirrors every casing. */
	@GameTest
	public void theFaceFrameIsPinned(GameTestHelper helper) {
		Map<Direction, List<Direction>> expected = new EnumMap<>(Direction.class);
		expected.put(Direction.NORTH, List.of(Direction.WEST, Direction.UP));
		expected.put(Direction.SOUTH, List.of(Direction.EAST, Direction.UP));
		expected.put(Direction.EAST, List.of(Direction.NORTH, Direction.UP));
		expected.put(Direction.WEST, List.of(Direction.SOUTH, Direction.UP));
		expected.put(Direction.UP, List.of(Direction.EAST, Direction.NORTH));
		expected.put(Direction.DOWN, List.of(Direction.EAST, Direction.SOUTH));
		Map<Direction, List<Direction>> actual = new EnumMap<>(Direction.class);
		for (Direction face : Direction.values()) {
			actual.put(face, List.of(CasingConnections.right(face), CasingConnections.up(face)));
		}
		if (!expected.equals(actual)) {
			throw helper.assertionException("face frames (right, up): expected %s, got %s", expected, actual);
		}
		helper.succeed();
	}

	@GameTest
	public void aLoneCasingShowsItsBorderAllRound(GameTestHelper helper) {
		BlockPos lone = wall(helper, new int[] {2, 2});
		for (Direction face : Direction.values()) {
			expect(helper, "the " + face + " face of a lone casing", all(Tile.ALONE), CasingConnections.tiles(helper.getLevel(), lone, CASING, face));
		}
		helper.succeed();
	}

	@GameTest
	public void aRowJoinsSideBySide(GameTestHelper helper) {
		wall(helper, new int[] {1, 2}, new int[] {2, 2}, new int[] {3, 2});
		expect(helper, "the middle of a row", all(Tile.HORIZONTAL), north(helper, 2, 2));
		// The east end (x 3): nothing on its left (east), the row on its right (west).
		expect(helper, "the east end of a row", new FaceTiles(Tile.ALONE, Tile.HORIZONTAL, Tile.ALONE, Tile.HORIZONTAL), north(helper, 3, 2));
		helper.succeed();
	}

	@GameTest
	public void aWallJoinsIntoOnePlate(GameTestHelper helper) {
		wall(helper, new int[] {1, 1}, new int[] {2, 1}, new int[] {3, 1}, new int[] {1, 2}, new int[] {2, 2}, new int[] {3, 2},
				new int[] {1, 3}, new int[] {2, 3}, new int[] {3, 3});
		expect(helper, "the middle of a 3 x 3 wall", all(Tile.CENTRE), north(helper, 2, 2));
		expect(helper, "the top middle", new FaceTiles(Tile.HORIZONTAL, Tile.HORIZONTAL, Tile.CENTRE, Tile.CENTRE), north(helper, 2, 3));
		// The bottom east corner (x 3, y 1): its top right quarter looks across to the middle.
		expect(helper, "the bottom east corner", new FaceTiles(Tile.VERTICAL, Tile.CENTRE, Tile.ALONE, Tile.HORIZONTAL), north(helper, 3, 1));
		helper.succeed();
	}

	@GameTest
	public void theInsideOfAnLKeepsACornerNub(GameTestHelper helper) {
		// An L: (1, 1) with a neighbour to its east (2, 1) and one above (1, 2); nothing across at (2, 2).
		wall(helper, new int[] {1, 1}, new int[] {2, 1}, new int[] {1, 2});
		expect(helper, "the corner of an L", new FaceTiles(Tile.CORNER, Tile.VERTICAL, Tile.HORIZONTAL, Tile.ALONE), north(helper, 1, 1));
		helper.succeed();
	}

	/** A neighbour whose own face here is covered by more casing does not join: the border runs round what can be seen. */
	@GameTest
	public void aNeighbourCoveredByCasingDoesNotJoin(GameTestHelper helper) {
		wall(helper, new int[] {1, 2}, new int[] {2, 2});
		helper.setBlock(new BlockPos(2, 2, 1), CASING);
		expect(helper, "a casing beside one that is covered", all(Tile.ALONE), north(helper, 1, 2));
		helper.succeed();
	}

	@GameTest
	public void anotherBlockDoesNotJoin(GameTestHelper helper) {
		wall(helper, new int[] {2, 2});
		for (BlockPos beside : List.of(new BlockPos(1, 2, 2), new BlockPos(3, 2, 2), new BlockPos(2, 1, 2), new BlockPos(2, 3, 2))) {
			helper.setBlock(beside, Blocks.IRON_BLOCK);
		}
		expect(helper, "a casing among iron blocks", all(Tile.ALONE), north(helper, 2, 2));
		helper.succeed();
	}
}
