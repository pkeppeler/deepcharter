package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import io.github.pkeppeler.deepcharter.scanner.ScanSlice;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice.Cell;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;

/** Server GameTests for the scanner slice grid. */
public class ScanSliceTest {
	private static final ScannerTuning TUNING = ScannerTuning.DEFAULT;
	/** Well above the flat test world, so a 32-block dive stays inside the build limits. */
	private static final int ORIGIN_Y = 100;

	private static BlockPos origin(GameTestHelper helper) {
		BlockPos at = helper.absolutePos(new BlockPos(2, 0, 2));
		return new BlockPos(at.getX(), ORIGIN_Y, at.getZ());
	}

	/** Air in both slice planes (east-west and north-south) and a block beyond each edge, so a test starts from a known world. */
	private static void clearBox(ServerLevel level, BlockPos origin) {
		int reach = TUNING.halfWidth() + 1;
		for (int along = -reach; along <= reach; along++) {
			for (int y = -TUNING.down() - 1; y <= TUNING.up() + 1; y++) {
				level.setBlock(origin.offset(along, y, 0), Blocks.AIR.defaultBlockState(), 2);
				level.setBlock(origin.offset(0, y, along), Blocks.AIR.defaultBlockState(), 2);
			}
		}
		// The off-plane marker of patternProducesTheExpectedGrid.
		level.setBlock(origin.offset(3, -2, 1), Blocks.AIR.defaultBlockState(), 2);
	}

	private static void place(ServerLevel level, BlockPos at, BlockState state) {
		if (!level.setBlock(at, state, 2)) {
			throw new IllegalStateException("could not place " + state + " at " + at);
		}
	}

	private static void expect(GameTestHelper helper, ScanSlice slice, int ahead, int up, Cell expected) {
		Cell actual = slice.cell(ahead, up);
		if (!actual.equals(expected)) {
			throw helper.assertionException("cell (ahead %d, up %d) should be %s, was %s".formatted(ahead, up, expected, actual));
		}
	}

	private static int count(ScanSlice slice, Class<? extends Cell> kind) {
		int n = 0;
		for (int ahead = -TUNING.halfWidth(); ahead <= TUNING.halfWidth(); ahead++) {
			for (int up = -TUNING.down(); up <= TUNING.up(); up++) {
				if (kind.isInstance(slice.cell(ahead, up))) {
					n++;
				}
			}
		}
		return n;
	}

	@GameTest
	public void patternProducesTheExpectedGrid(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = origin(helper);
		clearBox(level, origin);
		try {
			place(level, origin.offset(3, -2, 0), Blocks.GOLD_ORE.defaultBlockState());
			place(level, origin.offset(-1, -1, 0), Blocks.STONE.defaultBlockState());
			place(level, origin.offset(0, -1, 0), Blocks.STONE.defaultBlockState());
			place(level, origin.offset(0, 4, 0), Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState());
			// Off the slice plane for an east or west facing: must not show.
			place(level, origin.offset(3, -2, 1), Blocks.GOLD_ORE.defaultBlockState());

			ScanSlice slice = ScanSlice.scan(level, origin, Direction.EAST);
			expect(helper, slice, 3, -2, new Cell.Ore(Blocks.GOLD_ORE));
			expect(helper, slice, -1, -1, Cell.ROCK);
			expect(helper, slice, 0, -1, Cell.ROCK);
			expect(helper, slice, 0, 4, new Cell.Ore(Blocks.DEEPSLATE_DIAMOND_ORE));
			expect(helper, slice, 0, 0, Cell.AIR);
			expect(helper, slice, 1, -1, Cell.AIR);
			expect(helper, slice, 3, -1, Cell.AIR);
			if (count(slice, Cell.Ore.class) != 2 || count(slice, Cell.Rock.class) != 2) {
				throw helper.assertionException("expected 2 ore and 2 rock cells, got %d ore and %d rock",
						count(slice, Cell.Ore.class), count(slice, Cell.Rock.class));
			}
			helper.succeed();
		} finally {
			clearBox(level, origin);
		}
	}

	@GameTest
	public void sliceBoundsAreTwentyFourAcrossAndEightUpThirtyTwoDown(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = origin(helper);
		clearBox(level, origin);
		try {
			BlockState stone = Blocks.STONE.defaultBlockState();
			// The four extreme corners are inside the slice...
			place(level, origin.offset(24, 8, 0), stone);
			place(level, origin.offset(-24, 8, 0), stone);
			place(level, origin.offset(24, -32, 0), stone);
			place(level, origin.offset(-24, -32, 0), stone);
			// ...and one block past each edge is outside it.
			place(level, origin.offset(25, 0, 0), stone);
			place(level, origin.offset(-25, 0, 0), stone);
			place(level, origin.offset(0, 9, 0), stone);
			place(level, origin.offset(0, -33, 0), stone);

			ScanSlice slice = ScanSlice.scan(level, origin, Direction.EAST);
			expect(helper, slice, 24, 8, Cell.ROCK);
			expect(helper, slice, -24, 8, Cell.ROCK);
			expect(helper, slice, 24, -32, Cell.ROCK);
			expect(helper, slice, -24, -32, Cell.ROCK);
			if (count(slice, Cell.Rock.class) != 4) {
				throw helper.assertionException("only the 4 corner blocks are inside the slice, got %d rock cells",
						count(slice, Cell.Rock.class));
			}
			for (int[] outside : new int[][] {{25, 0}, {-25, 0}, {0, 9}, {0, -33}}) {
				try {
					slice.cell(outside[0], outside[1]);
				} catch (IllegalArgumentException expected) {
					continue;
				}
				throw helper.assertionException("cell (ahead %d, up %d) is outside the slice and must be refused",
						outside[0], outside[1]);
			}
			helper.succeed();
		} finally {
			clearBox(level, origin);
		}
	}

	@GameTest
	public void aheadFollowsTheFacing(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = origin(helper);
		clearBox(level, origin);
		try {
			place(level, origin.offset(5, 0, 0), Blocks.GOLD_ORE.defaultBlockState());
			place(level, origin.offset(0, 0, 7), Blocks.STONE.defaultBlockState());

			Cell gold = new Cell.Ore(Blocks.GOLD_ORE);
			expect(helper, ScanSlice.scan(level, origin, Direction.EAST), 5, 0, gold);
			expect(helper, ScanSlice.scan(level, origin, Direction.WEST), -5, 0, gold);
			expect(helper, ScanSlice.scan(level, origin, Direction.SOUTH), 7, 0, Cell.ROCK);
			expect(helper, ScanSlice.scan(level, origin, Direction.NORTH), -7, 0, Cell.ROCK);
			// The other axis is not in the plane.
			expect(helper, ScanSlice.scan(level, origin, Direction.SOUTH), 5, 0, Cell.AIR);
			expect(helper, ScanSlice.scan(level, origin, Direction.EAST), 7, 0, Cell.AIR);
			helper.succeed();
		} finally {
			clearBox(level, origin);
		}
	}

	@GameTest
	public void orePlainAndDeepslateAndCoalAreOreButABlockOfGoldIsRock(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = origin(helper);
		clearBox(level, origin);
		try {
			place(level, origin.offset(1, 0, 0), Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState());
			place(level, origin.offset(2, 0, 0), Blocks.COAL_ORE.defaultBlockState());
			// A gold block is solid but not in #c:ores.
			place(level, origin.offset(3, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState());
			ScanSlice slice = ScanSlice.scan(level, origin, Direction.EAST);
			expect(helper, slice, 1, 0, new Cell.Ore(Blocks.DEEPSLATE_GOLD_ORE));
			expect(helper, slice, 2, 0, new Cell.Ore(Blocks.COAL_ORE));
			expect(helper, slice, 3, 0, Cell.ROCK);
			helper.succeed();
		} finally {
			clearBox(level, origin);
		}
	}

	/** Pins scanner v1: fluids and plants are open space until a hazard tier says otherwise. */
	@GameTest
	public void waterLavaAndPlantsReadAsAir(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = origin(helper);
		clearBox(level, origin);
		try {
			place(level, origin.offset(1, 0, 0), Blocks.WATER.defaultBlockState());
			// Apart from the water: lava next to water turns to stone.
			place(level, origin.offset(6, 0, 0), Blocks.LAVA.defaultBlockState());
			place(level, origin.offset(3, 0, 0), Blocks.SHORT_GRASS.defaultBlockState());
			ScanSlice slice = ScanSlice.scan(level, origin, Direction.EAST);
			expect(helper, slice, 1, 0, Cell.AIR);
			expect(helper, slice, 6, 0, Cell.AIR);
			expect(helper, slice, 3, 0, Cell.AIR);
			helper.succeed();
		} finally {
			clearBox(level, origin);
		}
	}

	@GameTest
	public void scanningVerticallyRefusesAFacingThatIsNotHorizontal(GameTestHelper helper) {
		try {
			ScanSlice.scan(helper.getLevel(), origin(helper), Direction.DOWN);
		} catch (IllegalArgumentException expected) {
			helper.succeed();
			return;
		}
		throw helper.assertionException("a vertical facing has no side view and must be refused");
	}
}
