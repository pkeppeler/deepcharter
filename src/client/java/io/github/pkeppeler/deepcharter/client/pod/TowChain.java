package io.github.pkeppeler.deepcharter.client.pod;

/**
 * The curve of one tow line: points along a hanging rope between two hitch points, in world coordinates. The rope has a fixed length; when
 * the hitches are nearer than that it hangs in a parabola whose depth is about {@code sqrt(3 * chord * (rope - chord) / 8)}, and when
 * they are as far apart as the rope is long (or farther) it is straight. A hitch lies {@code radius} from the pod's middle toward the
 * other pod, the same measure the server's line uses ({@code TowTuning#trailDistance}), so a pod hanging taut shows a taut line.
 *
 * <p>The object is reused every frame: {@link #build} fills fixed arrays and allocates nothing.
 */
public final class TowChain {
	/** The most links in one line, so that a stretched or very long line costs a bounded amount; a longer arc has longer links. */
	public static final int MAX_LINKS = 96;
	private static final double EPSILON = 1e-6;

	/** Point {@code i} is at {@code x[i], y[i], z[i]}; there are {@link #links} + 1 of them. */
	public final double[] x = new double[MAX_LINKS + 1];
	public final double[] y = new double[MAX_LINKS + 1];
	public final double[] z = new double[MAX_LINKS + 1];
	public int links;

	/**
	 * Fills the points of the line between two pods whose middles are given, hitched {@code towerRadius} and {@code towedRadius} from them.
	 *
	 * @param rope blocks of line between the hitches when it is taut
	 * @param linkLength blocks of arc one link takes up, at most; the line has at least one link
	 */
	public void build(double towerX, double towerY, double towerZ, double towerRadius, double towedX, double towedY, double towedZ, double towedRadius,
			double rope, double linkLength) {
		double dx = towedX - towerX;
		double dy = towedY - towerY;
		double dz = towedZ - towerZ;
		double between = Math.sqrt(dx * dx + dy * dy + dz * dz);
		double nx = between < EPSILON ? 0 : dx / between;
		double ny = between < EPSILON ? 1 : dy / between;
		double nz = between < EPSILON ? 0 : dz / between;
		double ax = towerX + nx * towerRadius;
		double ay = towerY + ny * towerRadius;
		double az = towerZ + nz * towerRadius;
		double bx = towedX - nx * towedRadius;
		double by = towedY - ny * towedRadius;
		double bz = towedZ - nz * towedRadius;
		// Pods that overlap have their hitches crossed over: the line is the short way between them, never a negative length.
		double chord = Math.max(0.0, between - towerRadius - towedRadius);
		double sag = Math.sqrt(3.0 * chord * Math.max(0.0, rope - chord) / 8.0);
		double arc = chord < EPSILON ? 2.0 * sag : chord + 8.0 * sag * sag / (3.0 * chord);
		links = Math.max(1, Math.min(MAX_LINKS, (int) Math.ceil(arc / linkLength)));
		for (int i = 0; i <= links; i++) {
			double s = (double) i / links;
			x[i] = ax + (bx - ax) * s;
			y[i] = ay + (by - ay) * s - 4.0 * sag * s * (1.0 - s);
			z[i] = az + (bz - az) * s;
		}
	}
}
