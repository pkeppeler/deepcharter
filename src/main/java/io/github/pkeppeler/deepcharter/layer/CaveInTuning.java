package io.github.pkeppeler.deepcharter.layer;

/**
 * Tunables for the cave-in and the roof bolt that answers it, read as {@code CaveInTuning.DEFAULT.thing()}.
 *
 * @param warnTicks       ticks from the first creak to the fall; 60 is 3 s, the warning every mob and hazard gets (CREATURES section 3)
 * @param cueTicks        ticks between two creaks while the roof is loose
 * @param stretchRadius   a cave-in takes the roof within this many blocks, flat, of the place it was started
 * @param ceilingReach    blocks above the person's feet within which a roof counts: the first solid block over the head, if it is rock, is the roof
 * @param boltReach       a roof block within this many blocks of a roof bolt holds, and a cave-in leaves it standing
 * @param shakeReach      a blast shakes the roof over every person on foot within this many blocks of it
 * @param fallDamage      health a falling rock takes for each block it has fallen
 * @param fallDamageMax   most health one falling rock takes
 */
public record CaveInTuning(
        int warnTicks,
        int cueTicks,
        int stretchRadius,
        int ceilingReach,
        double boltReach,
        double shakeReach,
        float fallDamage,
        int fallDamageMax) {
    public static final CaveInTuning DEFAULT = new CaveInTuning(60, 10, 3, 5, 3.0, 12.0, 1.0f, 6);

    public CaveInTuning {
        if (warnTicks < 1
                || cueTicks < 1
                || cueTicks > warnTicks
                || stretchRadius < 1
                || ceilingReach < 2
                || boltReach <= 0
                || shakeReach <= 0
                || fallDamage < 0
                || fallDamageMax < 0) {
            throw new IllegalArgumentException("cave-in tuning out of range: " + this);
        }
    }
}
