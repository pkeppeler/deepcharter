package io.github.pkeppeler.deepcharter.client.continuity;

import io.github.pkeppeler.deepcharter.continuity.ContinuityEventPayload;
import io.github.pkeppeler.deepcharter.continuity.ContinuityEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.jspecify.annotations.Nullable;

/**
 * The client's side of a Continuity Event: the numbers the server sent just before the death, and the screen that replaces vanilla's
 * death screen ({@code ClientPacketListenerCombatKillMixin}, ADR 0100). Client thread only.
 */
public final class ContinuityClient {
    private static @Nullable ContinuityEventPayload pending;

    private ContinuityClient() {}

    static void received(ContinuityEventPayload payload) {
        pending = payload;
    }

    static void forget() {
        pending = null;
    }

    /** Whether the Continuity Event's screen is open: the camera does not tilt for the death meanwhile. */
    public static boolean inSequence() {
        return Minecraft.getInstance().gui.screen() instanceof ContinuityScreen;
    }

    /**
     * The screen for {@code player}'s death, written in the numbers the server sent. Without them (a death the server sent no numbers
     * for), the number reads as unknown and the employee number is worked out here, as the server would.
     */
    public static ContinuityScreen screenFor(LocalPlayer player) {
        ContinuityEventPayload numbers = pending;
        pending = null;
        return numbers != null
                ? new ContinuityScreen(player, numbers.number(), numbers.employee())
                : new ContinuityScreen(player, 0, ContinuityEvents.employeeNumber(player.getUUID()));
    }
}
