package io.github.pkeppeler.deepcharter.test.support;

import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

/**
 * The parts of a running client that a test reads once it is in a world, failing loud when it is not. Call them where the test already
 * waited for the world; a predicate that is waiting for the world to appear tests the field for null itself.
 */
public final class ClientSession {
    private ClientSession() {}

    public static LocalPlayer player(Minecraft client) {
        return Objects.requireNonNull(client.player, "the client has no player");
    }

    public static MultiPlayerGameMode gameMode(Minecraft client) {
        return Objects.requireNonNull(client.gameMode, "the client has no game mode");
    }

    public static ClientPacketListener connection(Minecraft client) {
        return Objects.requireNonNull(client.getConnection(), "the client is not connected");
    }

    /** The entity the client's camera follows: the player, unless a test moved it. */
    public static Entity camera(Minecraft client) {
        return Objects.requireNonNull(client.getCameraEntity(), "the client has no camera entity");
    }

    /** The screen the client shows, which must be a {@code type}. */
    public static <T extends Screen> T screen(Minecraft client, Class<T> type) {
        return type.cast(
                Objects.requireNonNull(client.gui.screen(), () -> "the client shows no " + type.getSimpleName()));
    }
}
