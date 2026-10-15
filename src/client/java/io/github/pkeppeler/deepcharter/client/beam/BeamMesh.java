package io.github.pkeppeler.deepcharter.client.beam;

import io.github.pkeppeler.deepcharter.client.theme.BeamStyle;

/**
 * The shape of a fake light beam (#248): two cones of translucent quads from the lamp along its direction, a wide faint one and a
 * narrow brighter core inside it. A cone widens with distance and fades from {@code alpha} at the lamp to nothing at the lamp's full
 * reach, so a beam cut short by a wall is narrower and still bright where it ends, as light is where it meets a wall. The shape is
 * written to a {@link Sink} and allocates nothing.
 */
public final class BeamMesh {
    private BeamMesh() {}

    /** Takes the vertices of a beam, four to a quad, in the order they must be drawn. {@code u} runs round the cone, {@code v} along it. */
    public interface Sink {
        void vertex(double x, double y, double z, double alpha, float u, float v);
    }

    /**
     * Writes the beam from (ox, oy, oz) along the unit vector (dx, dy, dz): {@code length} blocks of a beam whose reach in open air is
     * {@code fullLength}. Returns the number of vertices, 0 for a beam of no length.
     */
    public static int emit(
            Sink sink,
            BeamStyle style,
            int sides,
            double fullLength,
            double length,
            double ox,
            double oy,
            double oz,
            double dx,
            double dy,
            double dz) {
        if (length <= 0 || fullLength <= 0) {
            return 0;
        }
        // A basis across the beam: the direction crossed with up, or with east when the beam is upright.
        double ux = dz;
        double uy = 0;
        double uz = -dx;
        double ul = Math.sqrt(ux * ux + uz * uz);
        if (ul < 1e-4) {
            ux = 1;
            uy = 0;
            uz = 0;
            ul = 1;
        }
        ux /= ul;
        uy /= ul;
        uz /= ul;
        double vx = dy * uz - dz * uy;
        double vy = dz * ux - dx * uz;
        double vz = dx * uy - dy * ux;
        double endRadius = style.startRadius() + (style.endRadius() - style.startRadius()) * length / fullLength;
        double endFade = 1.0 - length / fullLength;
        int count = layer(
                sink,
                sides,
                style.startRadius(),
                endRadius,
                style.alpha(),
                endFade,
                length,
                ox,
                oy,
                oz,
                dx,
                dy,
                dz,
                ux,
                uy,
                uz,
                vx,
                vy,
                vz);
        count += layer(
                sink,
                sides,
                style.startRadius() * style.coreScale(),
                endRadius * style.coreScale(),
                style.coreAlpha(),
                endFade,
                length,
                ox,
                oy,
                oz,
                dx,
                dy,
                dz,
                ux,
                uy,
                uz,
                vx,
                vy,
                vz);
        return count;
    }

    private static int layer(
            Sink sink,
            int sides,
            double startRadius,
            double endRadius,
            double alpha,
            double endFade,
            double length,
            double ox,
            double oy,
            double oz,
            double dx,
            double dy,
            double dz,
            double ux,
            double uy,
            double uz,
            double vx,
            double vy,
            double vz) {
        double endAlpha = alpha * endFade;
        double ex = ox + dx * length;
        double ey = oy + dy * length;
        double ez = oz + dz * length;
        double step = 2 * Math.PI / sides;
        for (int i = 0; i < sides; i++) {
            double a0 = i * step;
            double a1 = (i + 1) * step;
            double c0 = Math.cos(a0);
            double s0 = Math.sin(a0);
            double c1 = Math.cos(a1);
            double s1 = Math.sin(a1);
            float u0 = (float) i / sides;
            float u1 = (float) (i + 1) / sides;
            sink.vertex(
                    ox + (ux * c0 + vx * s0) * startRadius,
                    oy + (uy * c0 + vy * s0) * startRadius,
                    oz + (uz * c0 + vz * s0) * startRadius,
                    alpha,
                    u0,
                    0f);
            sink.vertex(
                    ox + (ux * c1 + vx * s1) * startRadius,
                    oy + (uy * c1 + vy * s1) * startRadius,
                    oz + (uz * c1 + vz * s1) * startRadius,
                    alpha,
                    u1,
                    0f);
            sink.vertex(
                    ex + (ux * c1 + vx * s1) * endRadius,
                    ey + (uy * c1 + vy * s1) * endRadius,
                    ez + (uz * c1 + vz * s1) * endRadius,
                    endAlpha,
                    u1,
                    1f);
            sink.vertex(
                    ex + (ux * c0 + vx * s0) * endRadius,
                    ey + (uy * c0 + vy * s0) * endRadius,
                    ez + (uz * c0 + vz * s0) * endRadius,
                    endAlpha,
                    u0,
                    1f);
        }
        return sides * 4;
    }
}
