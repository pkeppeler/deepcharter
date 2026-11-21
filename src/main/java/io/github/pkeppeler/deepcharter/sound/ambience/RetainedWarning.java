package io.github.pkeppeler.deepcharter.sound.ambience;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * When the dark sings the song as a warning (#861), with no client in it. It plays only for a real Retained: in range, unseen for
 * {@link WarningTuning#confirmTicks()}, once per figure encounter and once per {@link WarningTuning#cooldownTicks()}, never over another
 * sound or near a wreck's phonograph, where the tune is learnt. A false positive would teach the wrong thing.
 */
public final class RetainedWarning {
    /** A Retained the client knows of: its entity id, its distance in blocks, and whether the player sees it. */
    public record Figure(int id, double distance, boolean seen) {}

    private final WarningTuning tuning;
    private final Set<Integer> cued = new HashSet<>();
    private final Map<Integer, Integer> unseenFor = new HashMap<>();
    private int cooldown;

    public RetainedWarning(WarningTuning tuning) {
        this.tuning = tuning;
    }

    /** Forgets the figures, for a player who has left the world. The cooldown is kept: leaving does not bring the next cue sooner. */
    public void reset() {
        cued.clear();
        unseenFor.clear();
    }

    /**
     * One client tick, told of every figure the client knows within reach (a farther one is out of the list, and forgotten).
     *
     * @param quiet      whether something holds the ambience back now
     * @param wreckSong  whether a wreck's phonograph sounds, or did a moment ago
     * @return the id of the figure the song comes from, if the warning is given now
     */
    public OptionalInt tick(List<Figure> figures, boolean quiet, boolean wreckSong) {
        if (cooldown > 0) {
            cooldown--;
        }
        Set<Integer> known = new HashSet<>();
        for (Figure figure : figures) {
            known.add(figure.id());
        }
        cued.retainAll(known);
        unseenFor.keySet().retainAll(known);
        Figure choice = null;
        for (Figure figure : figures) {
            boolean waiting = !wreckSong && inRange(figure) && !figure.seen() && !cued.contains(figure.id());
            if (!waiting) {
                unseenFor.remove(figure.id());
                continue;
            }
            int count = unseenFor.merge(figure.id(), 1, Integer::sum);
            if (count >= tuning.confirmTicks() && (choice == null || figure.distance() < choice.distance())) {
                choice = figure;
            }
        }
        if (choice == null || quiet || cooldown > 0) {
            return OptionalInt.empty();
        }
        cued.add(choice.id());
        unseenFor.remove(choice.id());
        cooldown = tuning.cooldownTicks();
        return OptionalInt.of(choice.id());
    }

    private boolean inRange(Figure figure) {
        return figure.distance() >= tuning.minRange() && figure.distance() <= tuning.maxRange();
    }

    /**
     * Whether a target is inside the player's view cone: the angle between where they look and the direction to it is at most
     * {@code halfAngleDegrees}. Both vectors need not be unit length; a zero vector is not in view.
     */
    public static boolean inCone(
            double lookX, double lookY, double lookZ, double toX, double toY, double toZ, double halfAngleDegrees) {
        double look = Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);
        double to = Math.sqrt(toX * toX + toY * toY + toZ * toZ);
        if (look == 0 || to == 0) {
            return false;
        }
        double cosine = (lookX * toX + lookY * toY + lookZ * toZ) / (look * to);
        return cosine >= Math.cos(Math.toRadians(halfAngleDegrees));
    }
}
