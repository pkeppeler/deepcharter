package io.github.pkeppeler.deepcharter.outpost;

import java.util.Arrays;
import java.util.List;

/**
 * A level on each {@link RatingAxis}: what a block holds, or what a depth asks of one. Level 0 is no rating. A block holds at a depth when
 * it reaches the depth's level on every axis ({@link #meets}).
 *
 * @param heat     how much heat it holds against
 * @param pressure how much pressure it holds against
 * @param flooding how much seeping water it holds against
 * @param quake    how much shaking it is braced against
 */
public record Ratings(int heat, int pressure, int flooding, int quake) {
    /** Nothing rated; also what the surface asks. */
    public static final Ratings NONE = new Ratings(0, 0, 0, 0);

    public int of(RatingAxis axis) {
        return switch (axis) {
            case HEAT -> heat;
            case PRESSURE -> pressure;
            case FLOODING -> flooding;
            case QUAKE -> quake;
        };
    }

    /** True when these ratings reach {@code required} on every axis. */
    public boolean meets(Ratings required) {
        return shortfalls(required).isEmpty();
    }

    /** The axes on which these ratings are below {@code required}, in axis order. */
    public List<RatingAxis> shortfalls(Ratings required) {
        return Arrays.stream(RatingAxis.values())
                .filter(axis -> of(axis) < required.of(axis))
                .toList();
    }

    public Ratings {
        if (heat < 0 || pressure < 0 || flooding < 0 || quake < 0) {
            throw new IllegalArgumentException(
                    "a rating level cannot be negative: " + heat + ", " + pressure + ", " + flooding + ", " + quake);
        }
    }
}
