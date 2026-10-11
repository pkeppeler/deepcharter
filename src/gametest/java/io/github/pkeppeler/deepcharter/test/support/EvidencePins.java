package io.github.pkeppeler.deepcharter.test.support;

/**
 * The switches that hold the client still for evidence stills (issue 309): each one stands for a thing that moves on client ticks or
 * frames and that no wait can settle. A mixin of the gametest mod reads each switch ({@code test/mixin}), so the switches change
 * nothing in the game: this class and the mixins are test code and never ship. A scenario turns a switch on for the stills that need
 * it and off again after. The fields are read on the render thread and set from the test thread, so they are volatile.
 */
public final class EvidencePins {
	/** Every animated texture (lava, water, fire) shows its first frame and no longer advances. */
	public static volatile boolean spritesFrozen;
	/** The HUD vignette is not drawn: its brightness eases over ticks. */
	public static volatile boolean vignetteHidden;
	/** The held item name is not drawn: it fades on client ticks. */
	public static volatile boolean itemNameHidden;
	/** The player's hands and the item in them are not drawn, so a rider's arm is not in a still. */
	public static volatile boolean handsHidden;
	/** Block light does not flicker: vanilla random-walks every lit surface on each client tick. */
	public static volatile boolean lightFlickerOff;
	/** The breach fade shows this alpha (0 clear to 1 black), HUD jitter off; negative lets it run. */
	public static volatile float breachFadeAlpha = -1f;
	/** Entities other than pods are drawn at rest: no arm sway, no walk cycle, whatever their age. */
	public static volatile boolean entitiesPosed;
	/** No lampless figures spawn on the rails: one that spawned between the last wait and the shot would be in the still. */
	public static volatile boolean figureSpawningOff;
	/** The fade (0 whole to 1 gone) of a lampless figure; negative keeps its own. */
	public static volatile float figureFade = -1f;

	private EvidencePins() {
	}

	/** Puts every switch back to the game's own behaviour. */
	public static void reset() {
		spritesFrozen = false;
		vignetteHidden = false;
		lightFlickerOff = false;
		handsHidden = false;
		itemNameHidden = false;
		breachFadeAlpha = -1f;
		entitiesPosed = false;
		figureSpawningOff = false;
		figureFade = -1f;
	}
}
