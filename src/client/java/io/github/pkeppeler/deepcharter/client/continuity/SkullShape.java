package io.github.pkeppeler.deepcharter.client.continuity;

import java.util.ArrayList;
import java.util.List;

/**
 * The Company's skull and crossbones (#1337, the user's pick C4): our own geometry in a unit square, x to the right and y down. The
 * skull is filled with letters, so its shape is a mask: {@link #cell} says whether a point is skull, bone or nothing, with the eye
 * sockets, the nose and the gap of the open mouth as holes. The crossbones are two femurs behind the jaw. {@link #skullLines}, {@link #boneLines} and {@link #knobs} are the faint
 * lines drawn round both. {@code jaw} from 0 (shut) to 1 (wide) drops the mandible with the laugh.
 *
 * <p>The shape is art, but not the kind a pack restyles with a number: it lives here as the model of the skull, as a model's cubes live
 * in its geometry file. Its sizes and colours on screen are the {@code continuity} theme's.
 */
final class SkullShape {
    static final int NONE = 0;
    static final int BONE = 1;
    static final int SKULL = 2;

    /** How far the jaw drops at its widest, in units of the square. */
    private static final double JAW_DROP = 0.075;
    /** Below this the mouth is shut: no gap. */
    private static final double MOUTH_OPEN = 0.05;

    private static final double MOUTH_LEFT = 0.38;
    private static final double MOUTH_RIGHT = 0.62;
    private static final double MOUTH_TOP = 0.705;
    /** The crown's centre and radii. */
    private static final double CRANIUM_X = 0.5;

    private static final double CRANIUM_Y = 0.37;
    private static final double CRANIUM_RX = 0.25;
    private static final double CRANIUM_RY = 0.27;
    private static final double TEETH_LEFT = 0.39;
    private static final double TEETH_STEP = 0.022;
    private static final int TEETH = 11;
    /** The femur's half-widths at its middle and its ends, and the radius of its knobs. */
    private static final double FEMUR_MID = 0.017;

    private static final double FEMUR_END = 0.034;
    private static final double KNOB = 0.03;

    /** A closed polygon or an open polyline, in the unit square. */
    @SuppressWarnings("ArrayRecordComponent") // built once by the shape and only read; never compared
    record Line(double[] xs, double[] ys, boolean closed) {}

    /** A circle in the unit square: a femur's knob. */
    record Circle(double x, double y, double r) {}

    private final double[][] head;
    private final double[][] jaw;
    private final List<double[][]> holes = new ArrayList<>();
    private final List<double[][]> shafts = new ArrayList<>();
    private final List<Circle> knobs = new ArrayList<>();
    private final List<Line> skullLines = new ArrayList<>();
    private final List<Line> boneLines = new ArrayList<>();
    private final double mouthBottom;
    private final boolean mouthOpen;

    SkullShape(double jawOpen) {
        double d = jawOpen * JAW_DROP;
        List<double[]> outline = new ArrayList<>();
        for (int a = 160; a <= 376; a += 8) {
            double r = Math.toRadians(a);
            outline.add(new double[] {CRANIUM_X + CRANIUM_RX * Math.cos(r), CRANIUM_Y + CRANIUM_RY * Math.sin(r)});
        }
        outline.addAll(List.of(p(0.735, 0.465), p(0.715, 0.55), p(0.66, 0.6), p(0.635, 0.665)));
        for (int k = 10; k >= 0; k--) {
            outline.add(p(0.365 + k * 0.027, 0.665));
        }
        outline.addAll(List.of(p(0.365, 0.665), p(0.34, 0.6), p(0.285, 0.55), p(0.265, 0.465)));
        head = outline.toArray(double[][]::new);
        double[][] jawline = {
            p(0.37, 0.67 + d),
            p(0.375, 0.75 + d),
            p(0.42, 0.80 + d),
            p(0.58, 0.80 + d),
            p(0.625, 0.75 + d),
            p(0.63, 0.67 + d)
        };
        jaw = concat(jawline, new double[][] {p(0.63, 0.665), p(0.37, 0.665)});
        double[][] eyeLeft = {
            p(0.355, 0.43), p(0.40, 0.395), p(0.46, 0.41), p(0.47, 0.47), p(0.43, 0.515), p(0.37, 0.505), p(0.345, 0.47)
        };
        double[][] eyeRight = new double[eyeLeft.length][];
        for (int i = 0; i < eyeLeft.length; i++) {
            eyeRight[i] = p(1 - eyeLeft[i][0], eyeLeft[i][1]);
        }
        double[][] nose = {p(0.5, 0.52), p(0.465, 0.6), p(0.5, 0.585), p(0.535, 0.6)};
        holes.addAll(List.of(eyeLeft, eyeRight, nose));
        mouthOpen = jawOpen > MOUTH_OPEN;
        mouthBottom = MOUTH_TOP + d;

        skullLines.add(line(head, true));
        skullLines.add(line(eyeLeft, true));
        skullLines.add(line(eyeRight, true));
        skullLines.add(line(nose, true));
        skullLines.add(line(jawline, false));
        for (int k = 0; k < TEETH; k++) {
            double x = TEETH_LEFT + k * TEETH_STEP;
            skullLines.add(line(new double[][] {p(x, 0.665), p(x, 0.70)}, false));
            skullLines.add(line(new double[][] {p(x, 0.675 + d), p(x, 0.71 + d)}, false));
        }
        skullLines.add(line(new double[][] {p(0.56, 0.11), p(0.585, 0.17), p(0.57, 0.21), p(0.6, 0.26)}, false));

        femur(p(0.15, 0.60), p(0.85, 0.93));
        femur(p(0.85, 0.60), p(0.15, 0.93));
    }

    /** What the point ({@code x}, {@code y}) of the unit square is: {@link #SKULL}, {@link #BONE} or {@link #NONE}. */
    int cell(double x, double y) {
        if (inside(head, x, y) || inside(jaw, x, y)) {
            for (double[][] hole : holes) {
                if (inside(hole, x, y)) {
                    return NONE;
                }
            }
            if (mouthOpen && x > MOUTH_LEFT && x < MOUTH_RIGHT && y > MOUTH_TOP && y < mouthBottom) {
                return NONE;
            }
            return SKULL;
        }
        for (double[][] shaft : shafts) {
            if (inside(shaft, x, y)) {
                return BONE;
            }
        }
        for (Circle knob : knobs) {
            if (Math.hypot(x - knob.x(), y - knob.y()) <= knob.r()) {
                return BONE;
            }
        }
        return NONE;
    }

    /** Whether the point is under the skull, which hides the bones' outline where they cross behind it. */
    boolean coversBones(double x, double y) {
        return inside(head, x, y) || inside(jaw, x, y);
    }

    List<Line> skullLines() {
        return skullLines;
    }

    List<Line> boneLines() {
        return boneLines;
    }

    List<Circle> knobs() {
        return knobs;
    }

    /** A thigh bone from {@code a} to {@code b}: a shaft that narrows to the middle and flares to each end, and two knobs at each end. */
    private void femur(double[] a, double[] b) {
        double ux = b[0] - a[0];
        double uy = b[1] - a[1];
        double length = Math.hypot(ux, uy);
        ux /= length;
        uy /= length;
        double px = -uy;
        double py = ux;
        int steps = 40;
        double[][] shaft = new double[2 * (steps + 1)][];
        for (int k = 0; k <= steps; k++) {
            double t = 0.06 + 0.88 * k / steps;
            double w = FEMUR_MID + (FEMUR_END - FEMUR_MID) * Math.pow(Math.abs(2 * t - 1), 3.5);
            double cx = a[0] + ux * length * t;
            double cy = a[1] + uy * length * t;
            shaft[k] = p(cx + px * w, cy + py * w);
            shaft[2 * (steps + 1) - 1 - k] = p(cx - px * w, cy - py * w);
        }
        shafts.add(shaft);
        boneLines.add(line(shaft, true));
        for (double[] end : new double[][] {{0.035, 1}, {0.965, -1}}) {
            double cx = a[0] + ux * length * end[0];
            double cy = a[1] + uy * length * end[0];
            for (int side : new int[] {1, -1}) {
                knobs.add(new Circle(
                        cx + px * KNOB * 0.95 * side - ux * KNOB * 0.25 * end[1],
                        cy + py * KNOB * 0.95 * side - uy * KNOB * 0.25 * end[1],
                        KNOB));
            }
        }
    }

    private static double[] p(double x, double y) {
        return new double[] {x, y};
    }

    private static double[][] concat(double[][] a, double[][] b) {
        double[][] out = new double[a.length + b.length][];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static Line line(double[][] points, boolean closed) {
        double[] xs = new double[points.length];
        double[] ys = new double[points.length];
        for (int i = 0; i < points.length; i++) {
            xs[i] = points[i][0];
            ys[i] = points[i][1];
        }
        return new Line(xs, ys, closed);
    }

    /** Even-odd point in polygon. */
    private static boolean inside(double[][] polygon, double x, double y) {
        boolean in = false;
        for (int i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
            double xi = polygon[i][0];
            double yi = polygon[i][1];
            double xj = polygon[j][0];
            double yj = polygon[j][1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                in = !in;
            }
        }
        return in;
    }
}
