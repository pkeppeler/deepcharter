package io.github.pkeppeler.deepcharter.scanner;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * A vertical slice of blocks in a pod's facing plane: one block thick, running along {@code facing}
 * and centred on {@code origin}. How far it reaches and what it tells apart depend on the scanner's tier
 * ({@link ScannerTuning}). Shared code over any {@link BlockGetter}.
 *
 * <p>Cells are addressed by {@code ahead} (blocks along the facing, negative behind) and {@code up}
 * (blocks above the origin, negative below). An unloaded chunk reads as air.
 */
public final class ScanSlice {
	/** Ore blocks, by the common convention tag. */
	public static final TagKey<Block> ORES = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("c", "ores"));

	private static final ScannerTuning TUNING = ScannerTuning.DEFAULT;

	private final int tier;
	private final ScanArea area;
	private final Cell[] cells;

	private ScanSlice(int tier, ScanArea area, Cell[] cells) {
		this.tier = tier;
		this.area = area;
		this.cells = cells;
	}

	/**
	 * What the pod's scanner sees, or empty when the pod has no working scanner (none fitted, another charter's, or a pod
	 * whose parts are unreadable). Never throws on unreadable pod state.
	 */
	public static Optional<ScanSlice> scan(BlockGetter level, PodEntity pod) {
		int tier = PodComponents.effectiveTier(pod, ComponentTrack.SCANNER);
		return tier == 0 ? Optional.empty() : Optional.of(scan(level, pod.blockPosition(), pod.getDirection(), tier));
	}

	/** Reads the slice of a scanner of {@code tier} (1 or more) around {@code origin}, the pod's feet. */
	public static ScanSlice scan(BlockGetter level, BlockPos origin, Direction facing, int tier) {
		if (facing.getAxis().isVertical()) {
			throw new IllegalArgumentException("a slice runs along a horizontal facing, not " + facing);
		}
		ScanArea area = TUNING.area(tier);
		boolean showsGas = TUNING.showsGas(tier);
		Cell[] cells = new Cell[area.columns() * area.rows()];
		for (int up = area.up(); up >= -area.down(); up--) {
			for (int ahead = -area.halfWidth(); ahead <= area.halfWidth(); ahead++) {
				BlockPos pos = origin.relative(facing, ahead).above(up);
				cells[index(area, ahead, up)] = classify(level, pos, level.getBlockState(pos), showsGas);
			}
		}
		return new ScanSlice(tier, area, cells);
	}

	public int tier() {
		return tier;
	}

	public ScanArea area() {
		return area;
	}

	public Cell cell(int ahead, int up) {
		if (!area.contains(ahead, up)) {
			throw new IllegalArgumentException("cell (ahead %d, up %d) is outside the slice".formatted(ahead, up));
		}
		return cells[index(area, ahead, up)];
	}

	/** Row-major, top row first, so the HUD can walk the array in draw order. */
	private static int index(ScanArea area, int ahead, int up) {
		return (area.up() - up) * area.columns() + ahead + area.halfWidth();
	}

	private static Cell classify(BlockGetter level, BlockPos pos, BlockState state, boolean showsGas) {
		if (state.is(ORES)) {
			return new Cell.Ore(state.getBlock());
		}
		if (showsGas && state.is(HazardBlocks.GAS_POCKET)) {
			return Cell.GAS;
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
		Cell GAS = new Gas();

		/**
		 * Anything without a collision shape, and any fluid: air, water, lava, plants. Fluids read as open space at every
		 * tier until the user decides otherwise (docs/BLOCKERS.md).
		 */
		record Air() implements Cell {
		}

		/** Solid, and not ore. Below {@link ScannerTuning#gasTier()} this includes gas pockets, which look like stone. */
		record Rock() implements Cell {
		}

		/** A gas pocket, seen by a scanner of {@link ScannerTuning#gasTier()} or better. */
		record Gas() implements Cell {
		}

		record Ore(Block block) implements Cell {
		}
	}
}
