package io.github.pkeppeler.deepcharter.test.support;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import java.lang.reflect.Field;
import java.util.function.IntSupplier;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;

/** What a player must see while a pick brute-forces a block (#858): the pick worn on the client and the crack well along. */
public final class HandBreakChecks {
    private HandBreakChecks() {}

    /**
     * Fails unless, after {@code ticks} of unbroken mining with a pick that bites, the client's held pick shows wear and the client's own
     * destroy progress, which draws the crack, is about {@code ticks / 1200}. A swing the client restarted would show neither.
     * {@code serverWear} reads the damage of the server's held pick, to tell a pick never worn from wear never shown.
     */
    public static void requireWorkShows(ClientGameTestContext context, int ticks, IntSupplier serverWear) {
        int serverWorn = serverWear.getAsInt();
        require(serverWorn > 0, "the server has not worn the pick after " + ticks + " ticks of mining");
        int worn = context.computeOnClient(
                client -> ClientSession.player(client).getMainHandItem().getDamageValue());
        require(
                worn > 0,
                "the server wore the pick by " + serverWorn + " but the client's pick shows no wear after " + ticks
                        + " ticks of mining");
        float progress = context.computeOnClient(HandBreakChecks::destroyProgress);
        float expected = ticks / 1200.0F;
        require(
                Math.abs(progress - expected) < 0.1F,
                "the client's destroy progress is " + progress + " after " + ticks + " ticks of mining, expected about "
                        + expected);
    }

    /** The client's own progress on the block it is mining, whose tenths are the stage of the crack. */
    private static float destroyProgress(Minecraft client) {
        try {
            Field field = MultiPlayerGameMode.class.getDeclaredField("destroyProgress");
            field.setAccessible(true);
            return field.getFloat(ClientSession.gameMode(client));
        } catch (ReflectiveOperationException e) {
            throw new LinkageError("the client's destroy progress could not be read", e);
        }
    }
}
