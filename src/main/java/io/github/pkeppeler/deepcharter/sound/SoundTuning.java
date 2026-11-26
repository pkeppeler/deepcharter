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
 * @param phonographRange           a phonograph's playback starts for the players within this many blocks of it (the sound's own
 *                                  {@code attenuation_distance} in sounds.json is the same number)
 * @param phonographListenTicks     while a phonograph plays it looks this often for a player who has come into range, and starts the
 *                                  recording again for them from the top
 * @param wreckSongRange            a wreck's phonograph sends its song to the players within this many blocks (the sound's own
 *                                  {@code attenuation_distance} in sounds.json is the same number)
 * @param wreckSongTriggerRange     a player this close to a wreck's phonograph, while it is armed, starts the song
 * @param wreckSongTicks            how long the song lasts, in ticks (the length of its file, rounded up)
 * @param wreckSongRearmTicks       a wreck's phonograph that has played or been switched off plays for the next visitor once no
 *                                  player has been within the trigger range for this long
 * @param dreadFadeInTicks          ticks the Retained's dread bed takes to rise to full once a figure holds the player or their pod (#1450)
 * @param dreadFadeOutTicks         ticks the bed takes to fall to silence once no figure holds them (struck off, or the pod gone)
 * @param droneDuck                 the share of its volume the layer's drone keeps while the bed plays at full; it follows the bed's
 *                                  fade, so the drone dips as the bed rises and comes back as it falls
 */
public record SoundTuning(
        int typewriterLettersPerClick,
        double movingSpeed,
        int movingHoldTicks,
        int engineFadeTicks,
        List<Integer> hymnTicks,
        int phonographRange,
        int phonographListenTicks,
        int wreckSongRange,
        int wreckSongTriggerRange,
        int wreckSongTicks,
        int wreckSongRearmTicks,
        int dreadFadeInTicks,
        int dreadFadeOutTicks,
        float droneDuck) {
    public static final SoundTuning DEFAULT =
            new SoundTuning(2, 0.02, 10, 10, List.of(218, 322, 468), 24, 20, 16, 14, 435, 6_000, 40, 60, 0.35f);

    public SoundTuning {
        if (dreadFadeInTicks < 1 || dreadFadeOutTicks < 1) {
            throw new IllegalArgumentException("the dread bed's fades take at least a tick each");
        }
        if (!(droneDuck >= 0f && droneDuck <= 1f)) {
            throw new IllegalArgumentException("the drone's duck is a share of its volume, 0 to 1, not " + droneDuck);
        }
    }
}
