package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.layer.BreachEffects;
import io.github.pkeppeler.deepcharter.client.layer.FadeHold;
import io.github.pkeppeler.deepcharter.client.theme.BreachLook;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

/**
 * Client GameTest of the breach fade's hold (#1185, ADR 0089): the fade stays black from the end of its black stretch while the client
 * has not both seen the respawn into the new level and reported itself loaded, and lets go at once when it has, or after
 * {@link FadeHold#MAX_HOLD_TICKS} ticks if that never comes. The fade's own ticks are driven here with a chosen "loaded" answer
 * ({@link BreachEffects#advance}), each scenario inside one client-thread call so the client's own ticks cannot step it, since a slow
 * load cannot be staged for real.
 */
public class BreachFadeHoldClientTest implements FabricClientGameTest {
    // The world is only here to have a connection and a sound manager for begin; the scenarios do not touch it.
    @SuppressWarnings("try")
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext ignored = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the client in the world", client -> client.player != null && client.level != null);
            context.runOnClient(client -> {
                BreachEffects.reset();
                aCrossingSoundsAsTheCrustGivingWayNotAsAnExplosion(client);
                holdsWhileLoadingAndReleasesWhenItFlips();
                heldAtTheCapWhenNoRespawnComes();
                aLateRespawnLiftsTheHoldOnceLoaded();
                aRespawnBeforeTheFadeAndAlreadyLoadedHoldsNothing();
                aStaleRespawnDoesNotCount();
                BreachEffects.reset();
            });
        }
    }

    /** A crossing plays the breach's own sound to the pilot, never the vanilla explosion it played before (#1293). */
    private static void aCrossingSoundsAsTheCrustGivingWayNotAsAnExplosion(Minecraft client) {
        List<Identifier> started = new ArrayList<>();
        SoundEventListener listener = (sound, events, range) -> started.add(sound.getIdentifier());
        client.getSoundManager().addListener(listener);
        BreachEffects.reset();
        BreachEffects.begin(true);
        client.getSoundManager().removeListener(listener);
        BreachEffects.reset();
        require(
                started.contains(DeepSound.BREACH_RUMBLE.event().location()),
                "A crossing should play " + DeepSound.BREACH_RUMBLE.event().location() + ", it started " + started);
        require(
                !started.contains(SoundEvents.GENERIC_EXPLODE.value().location()),
                "A crossing should not play the vanilla explosion, it started " + started);
    }

    private static int blackEnd() {
        BreachLook look = BreachLook.current();
        return look.fadeInTicks() + look.blackTicks();
    }

    private static void advance(int ticks, boolean loaded) {
        for (int i = 0; i < ticks; i++) {
            BreachEffects.advance(loaded);
        }
    }

    /** Ticks with {@code loaded} until the fade has ended; fails when it has not within {@code limit}. */
    private static int untilOver(int limit, boolean loaded, String what) {
        for (int i = 0; i < limit; i++) {
            if (BreachEffects.fadeAlpha(0f) == 0f) {
                return i;
            }
            BreachEffects.advance(loaded);
        }
        throw new AssertionError("The fade had not ended " + limit + " ticks after " + what);
    }

    private static void holdsWholeBlack(String what) {
        require(
                BreachEffects.fadeAlpha(0f) == 1f,
                "The fade should be black " + what + ", was " + BreachEffects.fadeAlpha(0f));
    }

    /** The respawn is seen and the client is still loading: black holds however long that takes, then the fade lifts. */
    private static void holdsWhileLoadingAndReleasesWhenItFlips() {
        BreachEffects.reset();
        BreachEffects.respawned();
        BreachEffects.begin(true);
        advance(blackEnd() + 50, false);
        holdsWholeBlack("while the client is still loading");
        require(
                BreachEffects.heldTicks() >= 50,
                "The hold should have counted its ticks, counted " + BreachEffects.heldTicks());
        BreachEffects.advance(true);
        int lifted = untilOver(BreachLook.current().fadeTicks() + 2, true, "the client reported itself loaded");
        require(lifted > 0, "The fade should lift over some ticks, not vanish");
        require(
                BreachEffects.heldTicks() < FadeHold.MAX_HOLD_TICKS,
                "A load that finished should not run into the cap");
    }

    /** No respawn ever comes, whatever the loaded flag says: the hold lasts to the cap and the fade then goes. */
    private static void heldAtTheCapWhenNoRespawnComes() {
        BreachEffects.reset();
        BreachEffects.begin(true);
        advance(blackEnd() + FadeHold.MAX_HOLD_TICKS / 2, true);
        holdsWholeBlack("with no respawn seen, however loaded the client says it is");
        untilOver(FadeHold.MAX_HOLD_TICKS + BreachLook.current().fadeTicks() + 2, true, "the cap");
        require(
                BreachEffects.heldTicks() == FadeHold.MAX_HOLD_TICKS,
                "The hold should end at the cap of " + FadeHold.MAX_HOLD_TICKS + ", it held "
                        + BreachEffects.heldTicks());
    }

    /** The respawn arrives after the black stretch: the fade is held until then, and until the load that follows it is done. */
    private static void aLateRespawnLiftsTheHoldOnceLoaded() {
        BreachEffects.reset();
        BreachEffects.begin(true);
        advance(blackEnd() + 10, true);
        holdsWholeBlack("before the respawn");
        BreachEffects.respawned();
        advance(5, false);
        holdsWholeBlack("while the level the respawn made loads");
        BreachEffects.advance(true);
        untilOver(BreachLook.current().fadeTicks() + 2, true, "the late respawn's level loaded");
    }

    /** The respawn came just before the payload and the level is loaded: nothing waits, the fade runs its length. */
    private static void aRespawnBeforeTheFadeAndAlreadyLoadedHoldsNothing() {
        BreachEffects.reset();
        BreachEffects.respawned();
        BreachEffects.begin(true);
        untilOver(BreachLook.current().fadeTicks() + 2, true, "a fade that waits for nothing");
        require(
                BreachEffects.heldTicks() == 0,
                "A level that was loaded should hold no black, held " + BreachEffects.heldTicks());
    }

    /** A respawn long before the fade (a death, say) is not this crossing's: the fade waits for its own. */
    private static void aStaleRespawnDoesNotCount() {
        BreachEffects.reset();
        BreachEffects.respawned();
        advance(200, true);
        BreachEffects.begin(true);
        advance(blackEnd() + 5, true);
        holdsWholeBlack("with only a stale respawn seen");
        BreachEffects.reset();
    }
}
