package io.github.pkeppeler.deepcharter.scanner;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A vertical slice of blocks in a pod's facing plane: one block thick, running along {@code facing}
 * and centred on {@code origin}. Shared code over any {@link BlockGetter}.
 *
 * <p>Cells are addressed by {@code ahead} (blocks along the facing, negative behind) and {@code up}
 * (blocks above the origin, negative below). An unloaded chunk reads as air.
 */
public final class ScanSlice {
	/** Ore blocks, by the common convention tag. */
	public static final TagKey<Block> ORES = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("c", "ores"));

	private static final ScannerTuning TUNING = ScannerTuning.DEFAULT;

	private final Cell[] cells;

	private ScanSlice(Cell[] cells) {
		this.cells = cells;
	}

	/** Reads the slice around {@code origin}, the pod's feet. */
	public static ScanSlice scan(BlockGetter level, BlockPos origin, Direction facing) {
		if (facing.getAxis().isVertical()) {
			throw new IllegalArgumentException("a slice runs along a horizontal facing, not " + facing);
		}
		Cell[] cells = new Cell[TUNING.columns() * TUNING.rows()];
		for (int up = TUNING.up(); up >= -TUNING.down(); up--) {
			for (int ahead = -TUNING.halfWidth(); ahead <= TUNING.halfWidth(); ahead++) {
				BlockPos pos = origin.relative(facing, ahead).above(up);
				cells[index(ahead, up)] = classify(level, pos, level.getBlockState(pos));
			}
		}
		return new ScanSlice(cells);
	}

	public Cell cell(int ahead, int up) {
		if (Math.abs(ahead) > TUNING.halfWidth() || up > TUNING.up() || up < -TUNING.down()) {
			throw new IllegalArgumentException("cell (ahead %d, up %d) is outside the slice".formatted(ahead, up));
		}
		return cells[index(ahead, up)];
	}

	/** Row-major, top row first, so the HUD can walk the array in draw order. */
	private static int index(int ahead, int up) {
		return (TUNING.up() - up) * TUNING.columns() + ahead + TUNING.halfWidth();
	}

	private static Cell classify(BlockGetter level, BlockPos pos, BlockState state) {
		if (state.is(ORES)) {
			return new Cell.Ore(state.getBlock());
		}
		// Fluids are named explicitly so the rule does not depend on their collision shapes.
		if (state.getBlock() instanceof LiquidBlock) {
			return Cell.AIR;
		}
		return state.getCollisionShape(level, pos).isEmpty() ? Cell.AIR : Cell.ROCK;
	}

	/** What a cell holds. */
	public sealed interface Cell {
		Cell AIR = new Air();
		Cell ROCK = new Rock();

		/**
		 * Anything without a collision shape, and any fluid: air, water, lava, plants. Fluids read as open space in
		 * scanner v1; hazards arrive with a later tier (SPEC section 7).
		 */
		record Air() implements Cell {
		}

		/** Solid, and not ore. */
		record Rock() implements Cell {
		}

		record Ore(Block block) implements Cell {
		}
	}
}
