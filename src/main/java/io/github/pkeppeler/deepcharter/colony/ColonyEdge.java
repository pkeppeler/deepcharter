package io.github.pkeppeler.deepcharter.colony;

import java.util.Arrays;
import java.util.function.IntBinaryOperator;

/**
 * The shape of the ground around the colony's pad: where the flat pad meets the land, the ground is graded from the pad's level up
 * or down to the natural ground, with no step between neighbouring columns taller than one block, so a player walks out of the
 * town on any side. The shape is a pure function of the natural heights and the columns' X and Z, so the same world builds the
 * same edge every time.
 *
 * <p>Each side has its own margin. It is at least {@link ColonyTuning#edgeMargin()} blocks wide and grows with the height gap the
 * side must absorb, because a slope of one block a column needs a block of margin for each block of gap, plus room for the
 * wander of the flat ground's edge. Where the gap fits within {@link ColonyTuning#edgeMarginCap()}, the margin's outer rim
 * meets the natural ground. <strong>Where it does not, the margin is the cap, the grade is the steepest slope of 1, and the natural
 * relief resumes at the rim as a step, a slope steeper than 1.</strong> A column that holds fluid is not graded: the builder leaves
 * it as it was.
 */
public final class ColonyEdge {
	/** The gap is measured at every this many blocks. */
	private static final int SAMPLE_STEP = 4;

	private ColonyEdge() {
	}

	/**
	 * One side's margin.
	 *
	 * @param width  blocks of margin outside the pad's edge
	 * @param wobble how far the flat ground's edge wanders on this side, in blocks: less than the tuning's where the gap is big
	 */
	public record Side(int width, int wobble) {
	}

	/** The four margins: west is -X, north is -Z. */
	public record Margins(Side west, Side east, Side north, Side south) {
		/** Columns from the west rim to the east rim, the pad's included. */
		public int sizeX() {
			return west.width() + ColonyTuning.DEFAULT.padSize() + east.width();
		}

		/** Columns from the north rim to the south rim, the pad's included. */
		public int sizeZ() {
			return north.width() + ColonyTuning.DEFAULT.padSize() + south.width();
		}
	}

	/**
	 * The margin of a side whose land lies up to {@code gap} blocks above or below the pad: wide enough that a slope of one block a
	 * column, with the edge's wander, covers the gap, and wanders as much as the cap allows.
	 */
	static Side sideFor(int gap) {
		ColonyTuning tuning = ColonyTuning.DEFAULT;
		for (int wobble = tuning.edgeWobble(); wobble > 0; wobble--) {
			// The wander slopes up to wobble / 4 blocks a block, and the grade slopes gap / ramp: together at most 1.
			int width = Math.max(tuning.edgeMargin(), (int) Math.ceil(gap * (1 + wobble / 4.0)) + wobble);
			if (width <= tuning.edgeMarginCap()) {
				return new Side(width, wobble);
			}
		}
		return new Side(Math.min(Math.max(tuning.edgeMargin(), gap), tuning.edgeMarginCap()), 0);
	}

	/**
	 * The margins for a pad whose columns run from {@code minX}, {@code minZ} to {@code maxX}, {@code maxZ}, both inclusive, on
	 * ground at {@code padGround}: each side's margin grows until it holds the height gap of the land it covers, as read through
	 * {@code natural} (the ground height of a column). It reads the land at every 4 blocks, and only as far out as a margin reaches.
	 */
	public static Margins margins(int padGround, int minX, int minZ, int maxX, int maxZ, IntBinaryOperator natural) {
		int[] width = new int[4];
		Arrays.fill(width, ColonyTuning.DEFAULT.edgeMargin());
		int[] gap = new int[4];
		boolean grew = true;
		while (grew) {
			Arrays.fill(gap, 0);
			for (int x = minX - width[WEST]; x <= maxX + width[EAST]; x = next(x, maxX + width[EAST])) {
				for (int z = minZ - width[NORTH]; z <= maxZ + width[SOUTH]; z = next(z, maxZ + width[SOUTH])) {
					int beyondX = x < minX ? minX - x : Math.max(x - maxX, 0);
					int beyondZ = z < minZ ? minZ - z : Math.max(z - maxZ, 0);
					if (beyondX + beyondZ == 0) {
						continue;
					}
					int height = Math.abs(natural.applyAsInt(x, z) - padGround);
					if (beyondX > 0) {
						gap[x < minX ? WEST : EAST] = Math.max(gap[x < minX ? WEST : EAST], height);
					}
					if (beyondZ > 0) {
						gap[z < minZ ? NORTH : SOUTH] = Math.max(gap[z < minZ ? NORTH : SOUTH], height);
					}
				}
			}
			grew = false;
			for (int side = 0; side < 4; side++) {
				int wanted = sideFor(gap[side]).width();
				if (wanted > width[side]) {
					width[side] = wanted;
					grew = true;
				}
			}
		}
		return new Margins(side(WEST, width, gap), side(EAST, width, gap), side(NORTH, width, gap), side(SOUTH, width, gap));
	}

	private static final int WEST = 0;
	private static final int EAST = 1;
	private static final int NORTH = 2;
	private static final int SOUTH = 3;

	private static Side side(int side, int[] width, int[] gap) {
		return new Side(width[side], Math.min(sideFor(gap[side]).wobble(), width[side] - 1));
	}

	/** The next sample along an axis: a step on, and always the last column. */
	private static int next(int at, int last) {
		return at == last ? last + 1 : Math.min(at + SAMPLE_STEP, last);
	}

	/**
	 * The ground height of every column of the pad and its margins.
	 *
	 * @param natural   the natural ground height of each column, {@code [x][z]} from the north-west corner of the margins: a grid
	 *                  of {@link Margins#sizeX()} by {@link Margins#sizeZ()} columns
	 * @param originX   the world X of index 0
	 * @param originZ   the world Z of index 0
	 * @param padGround the pad's ground, which the columns of the pad keep
	 */
	public static int[][] heights(int[][] natural, Margins margins, int originX, int originZ, int padGround) {
		int pad = ColonyTuning.DEFAULT.padSize();
		int sizeX = margins.sizeX();
		int sizeZ = margins.sizeZ();
		int[][] ground = new int[sizeX][sizeZ];
		for (int i = 0; i < sizeX; i++) {
			int beyondX = i < margins.west().width() ? margins.west().width() - i : Math.max(i - (margins.west().width() + pad - 1), 0);
			Side sideX = i < margins.west().width() ? margins.west() : margins.east();
			for (int j = 0; j < sizeZ; j++) {
				int beyondZ = j < margins.north().width() ? margins.north().width() - j : Math.max(j - (margins.north().width() + pad - 1), 0);
				Side sideZ = j < margins.north().width() ? margins.north() : margins.south();
				int steps = beyondX + beyondZ;
				if (steps == 0) {
					ground[i][j] = padGround;
					continue;
				}
				double wander = wander(originX + i, originZ + j);
				double along = Math.min(1, Math.hypot(reach(beyondX, sideX, wander), reach(beyondZ, sideZ, wander)));
				int wanted = (int) Math.round(padGround + (natural[i][j] - padGround) * along);
				// A step of one block a column is the steepest a player climbs: no column is more than its distance from the pad above or below the pad.
				ground[i][j] = Math.clamp(wanted, padGround - steps, padGround + steps);
			}
		}
		lowerToSlope(ground);
		return ground;
	}

	/** How far along its side's grade a column {@code beyond} the pad's edge is, from 0 to 1 (more with the wander); 0 where it is not beyond. */
	private static double reach(int beyond, Side side, double wander) {
		return beyond == 0 ? 0 : Math.max(0, beyond + wander * side.wobble()) / (side.width() - side.wobble());
	}

	/** Lowers every column to at most one block above its neighbour: the pad's ground stays, hills grade down to it. */
	private static void lowerToSlope(int[][] ground) {
		int sizeX = ground.length;
		int sizeZ = ground[0].length;
		for (int x = 0; x < sizeX; x++) {
			for (int z = 0; z < sizeZ; z++) {
				if (x > 0) {
					ground[x][z] = Math.min(ground[x][z], ground[x - 1][z] + 1);
				}
				if (z > 0) {
					ground[x][z] = Math.min(ground[x][z], ground[x][z - 1] + 1);
				}
			}
		}
		for (int x = sizeX - 1; x >= 0; x--) {
			for (int z = sizeZ - 1; z >= 0; z--) {
				if (x < sizeX - 1) {
					ground[x][z] = Math.min(ground[x][z], ground[x + 1][z] + 1);
				}
				if (z < sizeZ - 1) {
					ground[x][z] = Math.min(ground[x][z], ground[x][z + 1] + 1);
				}
			}
		}
	}

	/** Smooth noise from -1 to 1, the same for the same X and Z in every world, changing over {@link ColonyTuning#edgeLattice()} blocks. */
	private static double wander(int x, int z) {
		int lattice = ColonyTuning.DEFAULT.edgeLattice();
		int cellX = Math.floorDiv(x, lattice);
		int cellZ = Math.floorDiv(z, lattice);
		double fx = (x - cellX * lattice) / (double) lattice;
		double fz = (z - cellZ * lattice) / (double) lattice;
		fx = fx * fx * (3 - 2 * fx);
		fz = fz * fz * (3 - 2 * fz);
		double north = lerp(corner(cellX, cellZ), corner(cellX + 1, cellZ), fx);
		double south = lerp(corner(cellX, cellZ + 1), corner(cellX + 1, cellZ + 1), fx);
		return lerp(north, south, fz) * 2 - 1;
	}

	private static double lerp(double from, double to, double by) {
		return from + (to - from) * by;
	}

	private static double corner(int x, int z) {
		int h = x * 374761393 + z * 668265263;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) & 0xFFFF) / 65535.0;
	}
}
