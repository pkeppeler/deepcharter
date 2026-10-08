package io.github.pkeppeler.deepcharter.sound;

/**
 * Tunables for the sound feature, read as {@code SoundTuning.DEFAULT.thing()}.
 *
 * @param typewriterLettersPerClick a typewriter clicks once for this many letters (spaces do not count), so the 40 letters a
 *                                  second of the CRT screens click 20 times a second
 * @param movingSpeed               blocks per tick a pod must cover before its engine sounds as driving instead of idling
 * @param movingHoldTicks           ticks a pod keeps sounding as driving after its last move, so a pod near the speed does not
 *                                  swap its engine loop every tick
 */
public record SoundTuning(int typewriterLettersPerClick, double movingSpeed, int movingHoldTicks) {
	public static final SoundTuning DEFAULT = new SoundTuning(2, 0.02, 10);
}
