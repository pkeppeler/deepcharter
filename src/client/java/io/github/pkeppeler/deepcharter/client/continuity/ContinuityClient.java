package io.github.pkeppeler.deepcharter.client.continuity;

import io.github.pkeppeler.deepcharter.continuity.ContinuityEventPayload;
import io.github.pkeppeler.deepcharter.continuity.ContinuityEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.jspecify.annotations.Nullable;

/**
 * The client's side of a Continuity Event: the numbers the server sent just before the death, and the one live sequence. Client thread
 * only.
 *
 * <p>The sequence outlives its screen. Vanilla builds a new death screen whenever the screen is cleared while the player is dead (a
 * container the server closes, a screen opened over it and shut), and {@code GuiSetScreenMixin} hands it this same screen back each time
 * (ADR 0100), so the laugh and the silence go on and the timeline keeps the wall clock of the death. The sequence ends only when the
 * client has another player (RETURN TO WORK, or any respawn) or leaves the world.
 */
public final class ContinuityClient {
    private static @Nullable ContinuityEventPayload pending;
    private static @Nullable ContinuityScreen live;

    private ContinuityClient() {}

    static void received(ContinuityEventPayload payload) {
        pending = payload;
    }

    /** Leaving a world: the numbers and the sequence go with it. */
    static void forget() {
        pending = null;
        end();
    }

    /** Whether a Continuity Event is under way: the camera does not tilt for the death meanwhile. */
    public static boolean inSequence() {
        return live != null;
    }

    /**
     * The screen for {@code player}'s death, in place of vanilla's: the live one if it is this player's, so a screen cleared while the
     * person is dead shows the same sequence where it was; otherwise a new sequence, written in the numbers the server sent. Without them
     * (a death the server sent no numbers for, or a world joined dead), the number reads as unknown and the employee number is worked out
     * here, as the server would.
     */
    public static ContinuityScreen screenFor(LocalPlayer player) {
        ContinuityScreen screen = live;
        if (screen != null && screen.belongsTo(player)) {
            return screen;
        }
        end();
        ContinuityEventPayload numbers = pending;
        pending = null;
        screen = numbers != null
                ? new ContinuityScreen(player, numbers.number(), numbers.employee())
                : new ContinuityScreen(player, 0, ContinuityEvents.employeeNumber(player.getUUID()));
        live = screen;
        return screen;
    }

    /**
     * Each client tick: the sequence plays on whether or not its screen is the one shown, and ends, closing its screen, once the client
     * has another player.
     */
    static void tick(Minecraft client) {
        ContinuityScreen screen = live;
        if (screen == null) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || !screen.belongsTo(player)) {
            end();
            if (player != null && client.gui.screen() == screen) {
                client.gui.setScreen(null);
            }
            return;
        }
        screen.advance();
    }

    private static void end() {
        ContinuityScreen screen = live;
        live = null;
        if (screen != null) {
            screen.end();
        }
    }
}
