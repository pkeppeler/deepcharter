package io.github.pkeppeler.deepcharter.colony;

/**
 * The shape of the ground around the colony's pad: where the flat pad meets the land, the ground is graded from the pad's level up
 * or down to the natural ground over {@link ColonyTuning#edgeMargin()} blocks, with no step between neighbouring columns taller
 * than one block, so a player walks out of the town on any side. The shape is a pure function of the natural heights and the
 * columns' X and Z, so the same world builds the same edge every time.
 */
public final class ColonyEdge {
	private static final int LATTICE = 12;

	private ColonyEdge() {
	}

	/**
	 * The ground height of every column of the pad and its margin.
	 *
	 * @param natural   the natural ground height of each column, {@code [x][z]} from the south-west corner of the margin; a square
	 *                  of {@code pad + 2 * margin} columns a side
	 * @param originX   the world X of index 0
	 * @param originZ   the world Z of index 0
	 * @param padGround the pad's ground, which the columns of the pad keep
	 */
	public static int[][] heights(int[][] natural, int originX, int originZ, int padGround) {
		int pad = ColonyTuning.DEFAULT.padSize();
		int margin = ColonyTuning.DEFAULT.edgeMargin();
		int size = pad + 2 * margin;
		int[][] ground = new int[size][size];
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) {
				int distanceToPad = distanceToPad(x, z, margin, pad);
				if (distanceToPad == 0) {
					ground[x][z] = padGround;
					continue;
				}
				double wobble = (valueNoise(originX + x, originZ + z) - 0.5) * 2 * ColonyTuning.DEFAULT.edgeWobble();
				double along = Math.clamp((distanceFromPad(x, z, margin, pad) + wobble) / ColonyTuning.DEFAULT.edgeRamp(), 0, 1);
				double smooth = along * along * (3 - 2 * along);
				int wanted = (int) Math.round(padGround + (natural[x][z] - padGround) * smooth);
				// A step of one block a column is the steepest a player climbs, so the ground may be no more than its distance from the pad away from the pad's level.
				ground[x][z] = Math.clamp(wanted, padGround - distanceToPad, padGround + distanceToPad);
			}
		}
		lowerToSlope(ground);
		return ground;
	}

	/** The straight-line distance from a column to the pad's nearest edge column; 0 on the pad, so a corner is rounded. */
	private static double distanceFromPad(int x, int z, int margin, int pad) {
		return Math.hypot(Math.max(Math.max(margin - x, 0), x - (margin + pad - 1)),
				Math.max(Math.max(margin - z, 0), z - (margin + pad - 1)));
	}

	/** The distance in steps (north, south, east or west) from a column to the pad; 0 on the pad. */
	private static int distanceToPad(int x, int z, int margin, int pad) {
		int dx = Math.max(Math.max(margin - x, 0), x - (margin + pad - 1));
		int dz = Math.max(Math.max(margin - z, 0), z - (margin + pad - 1));
		return dx + dz;
	}

	/** Lowers every column to at most one block above its neighbour: the pad's ground stays, hills grade down to it. */
	private static void lowerToSlope(int[][] ground) {
		int size = ground.length;
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) {
				if (x > 0) {
					ground[x][z] = Math.min(ground[x][z], ground[x - 1][z] + 1);
				}
				if (z > 0) {
					ground[x][z] = Math.min(ground[x][z], ground[x][z - 1] + 1);
				}
			}
		}
		for (int x = size - 1; x >= 0; x--) {
			for (int z = size - 1; z >= 0; z--) {
				if (x < size - 1) {
					ground[x][z] = Math.min(ground[x][z], ground[x + 1][z] + 1);
				}
				if (z < size - 1) {
					ground[x][z] = Math.min(ground[x][z], ground[x][z + 1] + 1);
				}
			}
		}
	}

	/** Smooth noise from 0 to 1, the same for the same X and Z in every world. */
	private static double valueNoise(int x, int z) {
		int cellX = Math.floorDiv(x, LATTICE);
		int cellZ = Math.floorDiv(z, LATTICE);
		double fx = (x - cellX * LATTICE) / (double) LATTICE;
		double fz = (z - cellZ * LATTICE) / (double) LATTICE;
		fx = fx * fx * (3 - 2 * fx);
		fz = fz * fz * (3 - 2 * fz);
		double south = lerp(lattice(cellX, cellZ), lattice(cellX + 1, cellZ), fx);
		double north = lerp(lattice(cellX, cellZ + 1), lattice(cellX + 1, cellZ + 1), fx);
		return lerp(south, north, fz);
	}

	private static double lerp(double from, double to, double by) {
		return from + (to - from) * by;
	}

	private static double lattice(int x, int z) {
		int h = x * 374761393 + z * 668265263;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) & 0xFFFF) / 65535.0;
	}
}
