package io.github.pkeppeler.deepcharter.scanner;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.Chassis;
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
		return tier == 0 ? Optional.empty() : Optional.of(scan(level, pod.blockPosition(), pod.getDirection(), tier, pod.chassis()));
	}

	/**
	 * Whether {@link #scan(BlockGetter, PodEntity)} would show an ore: the same tier, area and facing, but it only asks each block
	 * whether it is an ore, and stops at the first. False when the pod has no working scanner. Never throws on unreadable pod state.
	 */
	public static boolean hasOre(BlockGetter level, PodEntity pod) {
		int tier = PodComponents.effectiveTier(pod, ComponentTrack.SCANNER);
		if (tier == 0) {
			return false;
		}
		ScanArea area = TUNING.area(tier);
		BlockPos origin = pod.blockPosition();
		Direction facing = pod.getDirection();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int up = area.up(); up >= -area.down(); up--) {
			for (int ahead = -area.halfWidth(); ahead <= area.halfWidth(); ahead++) {
				pos.set(origin).move(facing, ahead).move(Direction.UP, up);
				if (level.getBlockState(pos).is(ORES)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Reads the slice of a scanner of {@code tier} (1 or more) around {@code origin}, the feet of a pod of {@code chassis}, whose bore sets how far beside the plane lava counts. */
	public static ScanSlice scan(BlockGetter level, BlockPos origin, Direction facing, int tier, Chassis chassis) {
		if (facing.getAxis().isVertical()) {
			throw new IllegalArgumentException("a slice runs along a horizontal facing, not " + facing);
		}
		ScanArea area = TUNING.area(tier);
		boolean showsLava = TUNING.showsLava(tier);
		boolean showsGas = TUNING.showsGas(tier);
		int lavaSpread = TUNING.lavaSpread(chassis);
		Cell[] cells = new Cell[area.columns() * area.rows()];
		for (int up = area.up(); up >= -area.down(); up--) {
			for (int ahead = -area.halfWidth(); ahead <= area.halfWidth(); ahead++) {
				BlockPos pos = origin.relative(facing, ahead).above(up);
				cells[index(area, ahead, up)] = classify(level, pos, level.getBlockState(pos), facing, showsLava, showsGas, lavaSpread);
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

	private static Cell classify(BlockGetter level, BlockPos pos, BlockState state, Direction facing, boolean showsLava, boolean showsGas, int lavaSpread) {
		if (state.is(ORES)) {
			return new Cell.Ore(state.getBlock());
		}
		if (showsGas && state.is(HazardBlocks.GAS_POCKET)) {
			return Cell.GAS;
		}
		if (showsLava) {
			Cell lava = lavaReading(level, pos, facing, lavaSpread);
			if (lava != null) {
				return lava;
			}
		}
		// Fluids are named explicitly so the rule does not depend on their collision shapes.
		if (state.getBlock() instanceof LiquidBlock) {
			return Cell.AIR;
		}
		return state.getCollisionShape(level, pos).isEmpty() ? Cell.AIR : Cell.ROCK;
	}

	/**
	 * {@link Cell#LAVA} when the block at {@code pos} is lava, {@link Cell#LAVA_NEAR} when only a block within {@link ScannerTuning#lavaSpread(Chassis)}
	 * blocks either side of the plane at that spot is, and null for neither. The pod's bore is wider than the one-block plane, and lava beside
	 * the plane is lava the pod can touch.
	 */
	private static Cell lavaReading(BlockGetter level, BlockPos pos, Direction facing, int lavaSpread) {
		if (level.getFluidState(pos).is(FluidTags.LAVA)) {
			return Cell.LAVA;
		}
		Direction side = facing.getClockWise();
		for (int distance = 1; distance <= lavaSpread; distance++) {
			if (level.getFluidState(pos.relative(side, distance)).is(FluidTags.LAVA)
					|| level.getFluidState(pos.relative(side, -distance)).is(FluidTags.LAVA)) {
				return Cell.LAVA_NEAR;
			}
		}
		return null;
	}

	/** What a cell holds. */
	public sealed interface Cell {
		Cell AIR = new Air();
		Cell ROCK = new Rock();
		Cell LAVA = new Lava();
		Cell LAVA_NEAR = new LavaNear();
		Cell GAS = new Gas();

		/**
		 * Anything without a collision shape, and any fluid: air, water, plants, and lava on a scanner below {@link ScannerTuning#lavaTier()}.
		 * Water reads as open space at every tier: there is no water hazard (docs/BLOCKERS.md).
		 */
		record Air() implements Cell {
		}

		/** Solid, and not ore. Below {@link ScannerTuning#gasTier()} this includes gas pockets, which look like stone. */
		record Rock() implements Cell {
		}

		/** A cell with lava in it, seen by a scanner of {@link ScannerTuning#lavaTier()} or better. */
		record Lava() implements Cell {
		}

		/** A cell with no lava in it but lava within {@link ScannerTuning#lavaSpread(Chassis)} blocks beside the plane, seen by a scanner of {@link ScannerTuning#lavaTier()} or better. */
		record LavaNear() implements Cell {
		}

		/** A gas pocket, seen by a scanner of {@link ScannerTuning#gasTier()} or better. */
		record Gas() implements Cell {
		}

		record Ore(Block block) implements Cell {
		}
	}
}
