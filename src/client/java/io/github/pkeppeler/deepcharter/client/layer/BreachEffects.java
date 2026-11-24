package io.github.pkeppeler.deepcharter.client.layer;

import io.github.pkeppeler.deepcharter.client.theme.BreachLook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * What the client does after a crossing: a rumble (a sound and a short HUD jitter) and a fade to black
 * and back. All of it is counted in client ticks from the moment the payload arrives; nothing here
 * touches the camera. The transmission that comes with a crossing is the transmission feature's
 * ({@code client/transmission}), drawn over this fade. How long each effect lasts is the theme's ({@link BreachLook}).
 */
public final class BreachEffects {
    /** A HUD offset in pixels. */
    public record Offset(int x, int y) {}

    private static final Offset NONE = new Offset(0, 0);

    /** Ticks since the last crossing; no effects running is {@code -1}. */
    private static int elapsed = -1;

    /** Whether the running effects include the fade. */
    private static boolean fading;

    /**
     * Whether the fade waits at the end of its black stretch: set when a fading crossing begins and cleared when the hold ends, which is
     * when the client has seen the respawn into the new level and reports itself loaded again, or at the cap.
     */
    private static boolean pending;

    /** Whether the hold is on this tick, so {@link #fadeAlpha} stays black however far the fade had come. */
    private static boolean holdingBlack;

    /** Whether the respawn of the crossing has been seen: it came just before the fade began, or since. */
    private static boolean sawRespawn;

    /** Client ticks since the last respawn; the respawn of a crossing comes just before its payload. */
    private static int ticksSinceRespawn = Integer.MAX_VALUE;

    /** Ticks the latest fade has been held black; kept after it ends. */
    private static int heldTicks;

    /** The most ticks the fade is held black, so a level that never reports itself loaded does not leave the screen black (10 s). */
    public static final int MAX_HOLD_TICKS = 200;

    /** A respawn this many client ticks before the payload is the crossing's own: the server sends the respawn, then the payload. */
    private static final int RESPAWN_WINDOW_TICKS = 40;

    private BreachEffects() {}

    /** The whole fade, in client ticks: 20 ticks is one second. */
    public static int fadeTicks() {
        return BreachLook.current().fadeTicks();
    }

    /**
     * Starts the rumble, and the fade when {@code fades}. A crossing inside one level moves nothing, so it has no fade. Called on the
     * client thread when the payload arrives.
     */
    public static void begin(boolean fades) {
        elapsed = 0;
        fading = fades;
        pending = fades;
        holdingBlack = false;
        heldTicks = 0;
        sawRespawn = fades && ticksSinceRespawn <= RESPAWN_WINDOW_TICKS;
        Minecraft.getInstance()
                .getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.GENERIC_EXPLODE.value(), 0.4f, 0.8f));
    }

    /**
     * The client has handled a respawn ({@code SeamClient.endClaim}, at the end of {@code handleRespawn}, where the client has gone back to
     * not loaded). A crossing's fade that waits for it stops waiting.
     */
    public static void respawned() {
        ticksSinceRespawn = 0;
        if (pending) {
            sawRespawn = true;
        }
    }

    /** Ticks the latest fade was held black, kept after it ends for the timing log of a test. */
    // test-seam: BreachFadeHoldClientTest and SeamAdoptionClientTest read how long the hold lasted
    public static int heldTicks() {
        return heldTicks;
    }

    /** The end of a disconnect: nothing runs on, and a respawn seen before it is not a later crossing's. */
    public static void reset() {
        end();
        ticksSinceRespawn = Integer.MAX_VALUE;
    }

    private static void end() {
        elapsed = -1;
        pending = false;
        holdingBlack = false;
        sawRespawn = false;
    }

    public static void tick() {
        Minecraft client = Minecraft.getInstance();
        ClientPacketListener connection = client.getConnection();
        advance(connection != null && connection.hasClientLoaded());
    }

    /** One tick of the fade with the client's answer to "loaded" given, so a test can stage a slow load. */
    // test-seam: BreachFadeHoldClientTest steps the fade with a chosen loaded answer, which a real client cannot be
    // made to give on demand
    public static void advance(boolean clientLoaded) {
        if (ticksSinceRespawn < Integer.MAX_VALUE) {
            ticksSinceRespawn++;
        }
        if (elapsed < 0) {
            return;
        }
        if (fading && shouldHoldBlack(clientLoaded)) {
            holdingBlack = true;
            heldTicks++;
            return;
        }
        holdingBlack = false;
        pending = false;
        elapsed++;
        if (elapsed > fadeTicks()) {
            end();
        }
    }

    /**
     * Whether the fade waits at the end of its black stretch: until the client has seen the respawn into the level it crossed to and
     * reports itself loaded, which it does once the section it is in is meshed (vanilla's renderer and Sodium's both answer that), so
     * what the fade lifts on is a world that can be drawn (#1185). A respawn that came before the payload is already seen, and a
     * {@code loaded} that was true before the respawn does not count. Never past {@link #MAX_HOLD_TICKS}.
     */
    private static boolean shouldHoldBlack(boolean clientLoaded) {
        BreachLook look = BreachLook.current();
        if (!pending || elapsed < look.fadeInTicks() + look.blackTicks() || heldTicks >= MAX_HOLD_TICKS) {
            return false;
        }
        return !(sawRespawn && clientLoaded);
    }

    /** 0 is clear, 1 is black: up, a short stretch of full black, then down, all within {@link #fadeTicks()}. */
    public static float fadeAlpha(float partialTick) {
        if (elapsed < 0 || !fading) {
            return 0f;
        }
        if (holdingBlack) {
            return 1f;
        }
        BreachLook look = BreachLook.current();
        float t = elapsed + partialTick;
        if (t >= look.fadeTicks()) {
            return 0f;
        }
        if (t < look.fadeInTicks()) {
            return t / look.fadeInTicks();
        }
        int fadeOutStart = look.fadeInTicks() + look.blackTicks();
        if (t < fadeOutStart) {
            return 1f;
        }
        return 1f - (t - fadeOutStart) / (look.fadeTicks() - fadeOutStart);
    }

    /** The HUD offset, shaking hard at first and settling over the look's jitter ticks. */
    public static Offset jitter() {
        BreachLook look = BreachLook.current();
        if (elapsed < 0 || elapsed >= look.jitterTicks()) {
            return NONE;
        }
        double amplitude = look.jitterPixels() * (1.0 - (double) elapsed / look.jitterTicks());
        return new Offset((int) Math.round(amplitude * Math.sin(elapsed * 2.1)), (int)
                Math.round(amplitude * Math.cos(elapsed * 3.3)));
    }
}
