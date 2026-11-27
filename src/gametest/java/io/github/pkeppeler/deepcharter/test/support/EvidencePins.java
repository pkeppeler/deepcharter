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
    /** Entities other than pods are drawn at rest: no arm sway, no walk cycle, whatever their age. */
    public static volatile boolean entitiesPosed;
    /** Block faces are shaded as {@code cardinal_light: nether} shades them, whatever the dimension type says; set before the world opens, since sections are meshed with it. */
    public static volatile boolean cardinalLightNether;
    /** No lampless figures spawn on the rails: one that spawned between the last wait and the shot would be in the still. */
    public static volatile boolean figureSpawningOff;
    /**
     * The sky is not drawn (no stars, sun, moon, sunrise or sunset), so where a view runs past what the client has
     * drawn it shows the fog colour the frame is cleared to; and {@link #SKY_HIDDEN_CAPTION} is written in a corner of
     * the frame, so no still passes the hidden sky off as the game's look. For a cavern that the overworld sky shows
     * behind (#1161).
     */
    public static volatile boolean skyHidden;

    /** The corner caption of a still taken with {@link #skyHidden} on. */
    public static final String SKY_HIDDEN_CAPTION = "sky hidden for review (#1161)";

    /**
     * The age in ticks that GeckoLib reads as the clock of a lampless figure's animation (0 or more); negative keeps the figure's own.
     * Pods keep their own.
     */
    public static volatile float figureAnimationAge = -1f;

    /**
     * The age in ticks that GeckoLib reads as the clock of a drifter's animation (0 or more); negative keeps the
     * drifter's own, which runs on the entity's ticks and the frame's partial tick.
     */
    public static volatile float drifterAnimationAge = -1f;

    /**
     * The attack button counts as held, whatever the mouse does. The test client's mouse is not grabbed, so vanilla
     * sees the button up on every tick and aborts the block being mined; with this on, the game's own mining runs as
     * it does for a player who holds the button.
     */
    public static volatile boolean attackHeld;

    private EvidencePins() {}

    /** Puts every switch back to the game's own behaviour. */
    public static void reset() {
        spritesFrozen = false;
        vignetteHidden = false;
        lightFlickerOff = false;
        handsHidden = false;
        itemNameHidden = false;
        entitiesPosed = false;
        figureSpawningOff = false;
        cardinalLightNether = false;
        skyHidden = false;
        figureAnimationAge = -1f;
        drifterAnimationAge = -1f;
        attackHeld = false;
    }
}
