package io.github.pkeppeler.deepcharter.sound;

import java.util.List;

/**
 * Tunables for the sound feature, read as {@code SoundTuning.DEFAULT.thing()}.
 *
 * @param typewriterLettersPerClick a typewriter clicks once for this many letters (spaces do not count), so the 40 letters a
 *                                  second of the CRT screens click 20 times a second
 * @param movingSpeed               blocks per tick a pod must cover before its engine sounds as driving instead of idling
 * @param movingHoldTicks           ticks a pod keeps sounding as driving after its last move, so a pod near the speed does not
 *                                  swap its engine loop every tick
 * @param engineFadeTicks           ticks a pod loop takes to rise from silence to full, and to fall back: a loop never starts or
 *                                  stops at full volume, so a change of engine state is a crossfade, not a cut
 * @param hymnTicks                 how long each version of the Lamp Hymn plays, in ticks, the colony first (the length of its file, rounded up);
 *                                  a player is not given a hymn while their last one is still playing
 */
public record SoundTuning(
        int typewriterLettersPerClick,
        double movingSpeed,
        int movingHoldTicks,
        int engineFadeTicks,
        List<Integer> hymnTicks) {
    public static final SoundTuning DEFAULT = new SoundTuning(2, 0.02, 10, 10, List.of(218, 322, 468));
}
