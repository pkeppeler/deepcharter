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
	/** The screen-edge vignette of the HUD, whose brightness eases over ticks to the light at the player, is not drawn. */
	public static volatile boolean vignetteHidden;
	/** The player's hands and the item in them are not drawn, so a rider's arm is not in a still. */
	public static volatile boolean handsHidden;
	/** Block light does not flicker: vanilla moves the brightness of every block-lit surface by a random walk on each client tick. */
	public static volatile boolean lightFlickerOff;
	/** The breach fade shows this alpha (0 clear to 1 black) and the HUD jitter is off; negative leaves the fade running on its ticks. */
	public static volatile float breachFadeAlpha = -1f;
	/** Entities other than pods are drawn at rest: no idle sway of the arms and no walk cycle, whatever the age of the entity or its steps. */
	public static volatile boolean entitiesPosed;
	/** The level does not put lampless figures on the rails of Prospector's Run: a figure that spawns between the last wait and the shot is in the still. */
	public static volatile boolean figureSpawningOff;
	/** The fade (0 whole to 1 gone) a lampless figure is drawn with; negative leaves the fade it has. */
	public static volatile float figureFade = -1f;

	private EvidencePins() {
	}

	/** Puts every switch back to the game's own behaviour. */
	public static void reset() {
		spritesFrozen = false;
		vignetteHidden = false;
		lightFlickerOff = false;
		handsHidden = false;
		breachFadeAlpha = -1f;
		entitiesPosed = false;
		figureSpawningOff = false;
		figureFade = -1f;
	}
}
