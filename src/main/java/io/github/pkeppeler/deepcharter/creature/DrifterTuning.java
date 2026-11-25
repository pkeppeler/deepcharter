package io.github.pkeppeler.deepcharter.creature;

import io.github.pkeppeler.deepcharter.layer.Zones;
import java.util.List;

/**
 * Tunables for the Drifters and the alarm (#973), read as {@code DrifterTuning.DEFAULT.thing()}. The numbers and why are in
 * {@code docs/design/mechanics.md}, "Drifters and the alarm".
 *
 * @param maxHealth       health of a bladder: an arrow from a bow pops it, a fist takes four blows
 * @param driftSpeed      blocks a tick it drifts toward a noise
 * @param wanderSpeed     blocks a tick it drifts when nothing makes a noise
 * @param noise           what each noise draws them from
 * @param armTicks        ticks it must be drawn without a break before it can burst on touch: the tell, at least 3 seconds
 * @param calmTicks       ticks after the last noise it heard that it counts as drawn; then it drifts on, harmless
 * @param touchBlocks     how far past its skin a pod or a person must be to touch it
 * @param drilledBeside   a drifter this close to the hull (the gap between the two boxes) of a pod that is drilling bursts
 * @param hissTicks       ticks between hisses, at most; the hiss is the tell by ear
 * @param cloud           the spore gas
 * @param spawn           how they appear
 * @param alarm           what a kill does to the zone
 */
public record DrifterTuning(
        float maxHealth,
        double driftSpeed,
        double wanderSpeed,
        Noise noise,
        int armTicks,
        int calmTicks,
        double touchBlocks,
        double drilledBeside,
        int hissTicks,
        Cloud cloud,
        Spawn spawn,
        Alarm alarm) {
    public static final DrifterTuning DEFAULT = new DrifterTuning(
            4f, 0.08, 0.02, Noise.DEFAULT, 60, 40, 0.2, 2.5, 80, Cloud.DEFAULT, Spawn.DEFAULT, Alarm.DEFAULT);

    public DrifterTuning {
        if (maxHealth <= 0
                || driftSpeed <= 0
                || wanderSpeed < 0
                || wanderSpeed >= driftSpeed
                || armTicks < 60
                || calmTicks < 1
                || touchBlocks < 0
                || drilledBeside <= 0
                || hissTicks < 1) {
            throw new IllegalArgumentException("drifter tuning out of range: " + this);
        }
    }

    /**
     * How far each noise carries to a drifter, in blocks.
     *
     * @param drill       a pod drilling: the loudest thing in the hollows
     * @param sprint      a person on foot sprinting
     * @param footstep    a person on foot walking; one who sneaks, or stands still, makes none
     * @param footstepMin blocks a tick a person must cover to be heard walking
     * @param footstepMax blocks a tick a person can cover on foot: more is a teleport, which makes no noise
     * @param lookTicks   ticks between two looks at what makes a noise
     */
    public record Noise(
            double drill, double sprint, double footstep, double footstepMin, double footstepMax, int lookTicks) {
        public static final Noise DEFAULT = new Noise(28, 20, 10, 0.1, 1.0, 5);

        public Noise {
            if (!(drill > sprint
                    && sprint > footstep
                    && footstep > 0
                    && footstepMin > 0
                    && footstepMax > footstepMin
                    && lookTicks > 0)) {
                throw new IllegalArgumentException("noise tuning out of range: " + this);
            }
        }
    }

    /**
     * The gas a burst leaves.
     *
     * @param radius        blocks from where it burst
     * @param ticks         how long it hangs
     * @param hullPerSecond hull a pod loses a second while its box is in the gas, for each cloud
     * @param poisonTicks   a person on foot in the gas is poisoned for this long, renewed each look
     * @param blindTicks    and blinded for this long
     * @param lookTicks     ticks between two looks at who is in the gas
     */
    public record Cloud(double radius, int ticks, float hullPerSecond, int poisonTicks, int blindTicks, int lookTicks) {
        public static final Cloud DEFAULT = new Cloud(4.5, 160, 3f, 80, 40, 10);

        public Cloud {
            if (radius <= 0 || ticks < 1 || hullPerSecond <= 0 || poisonTicks < 1 || blindTicks < 1 || lookTicks < 1) {
                throw new IllegalArgumentException("cloud tuning out of range: " + this);
            }
        }
    }

    /**
     * How a pod draws them. A source of noise is a pod drilling with a person aboard, once however many people sit in it, plus one for
     * every crew on foot that makes a noise: the count is of pods, not of people (CREATURES.md section 4).
     *
     * @param checkTicks     ticks between two looks at the sources of a level
     * @param perMinute      for a source, the drifters a minute placed in the top, middle and bottom zone of the layer
     * @param zoneCap        the most drifters alive in the top, middle and bottom zone of a layer, whatever the sources
     * @param perSource      the most alive in a zone for each source in it
     * @param minBlocks      the nearest a drifter is placed to a source
     * @param maxBlocks      and the farthest
     * @param tries          places tried for each placement
     */
    public record Spawn(
            int checkTicks,
            List<Double> perMinute,
            List<Integer> zoneCap,
            int perSource,
            double minBlocks,
            double maxBlocks,
            int tries) {
        public static final Spawn DEFAULT =
                new Spawn(100, List.of(0.25, 1.0 / 3.0, 0.5), List.of(2, 3, 4), 1, 18, 36, 24);

        public Spawn {
            perMinute = List.copyOf(perMinute);
            zoneCap = List.copyOf(zoneCap);
            if (checkTicks < 1
                    || perMinute.size() != Zones.COUNT
                    || zoneCap.size() != Zones.COUNT
                    || perMinute.stream().anyMatch(rate -> !(rate > 0))
                    || zoneCap.stream().anyMatch(cap -> cap < 1)
                    || perSource < 1
                    || minBlocks <= 0
                    || maxBlocks <= minBlocks
                    || tries < 1) {
                throw new IllegalArgumentException("drifter spawn tuning out of range: " + this);
            }
        }

        /** The chance that one look at a source places a drifter in zone {@code zone} (0 is the top), before the alarm. */
        public double chancePerCheck(int zone) {
            return perMinute.get(zone) * checkTicks / (60.0 * 20.0);
        }
    }

    /**
     * What a killed drifter does to its zone.
     *
     * @param ticks      how long the alarm lasts from the last kill; a kill while it rings starts it again, never longer
     * @param chanceMult how much more often a source places a drifter
     * @param extraEach  drifters more that one source may have alive
     * @param senseMult  how much farther the drifters of the zone hear
     * @param droneTicks ticks between two beats of the drone
     */
    public record Alarm(int ticks, double chanceMult, int extraEach, double senseMult, int droneTicks) {
        public static final Alarm DEFAULT = new Alarm(3600, 3, 1, 1.5, 100);

        public Alarm {
            if (ticks < 1 || chanceMult < 1 || extraEach < 0 || senseMult < 1 || droneTicks < 1) {
                throw new IllegalArgumentException("alarm tuning out of range: " + this);
            }
        }
    }

    /** The hull a pod loses for each tick it sits in one cloud. */
    public float hullPerTick() {
        return cloud.hullPerSecond() / 20f;
    }
}
