package io.github.pkeppeler.deepcharter.colony;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.Optional;
import java.util.OptionalInt;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * Refuses to start a world made in an older world format (ADR 0059). A world that has a built colony and no {@link WorldFormat}
 * stamp, or a stamp lower than {@link WorldFormat#CURRENT}, or a stamp that cannot be read, is refused. A world with no colony
 * passes: it has no saved terrain that a new format could not hold, and its colony gets the stamp when it is built.
 *
 * <p>The check runs on {@link ServerLifecycleEvents#SERVER_STARTING}, which Fabric fires from {@code MinecraftServer.runServer}
 * just before {@code initServer}. That is before {@code createLevels} makes the first level, so no chunk has been generated, loaded
 * or saved: {@code saveAllChunks} only reaches a level that exists. It reads the saved data storage, which the server builds in its
 * constructor.
 *
 * <p>A singleplayer world never reaches this check for an old world: the client refuses it earlier, with a screen
 * ({@code client/colony}), because a server that fails to start is a crash report. Here an integrated server throws, as a backstop
 * for a way into a world that skips the screen. A dedicated server logs the message and halts the JVM. It does not use
 * {@code System.exit}: that runs the server's shutdown hook, which waits for the server thread, and this is the server thread.
 */
public final class WorldFormatGate {
    private WorldFormatGate() {}

    /**
     * Why a world is refused: {@code message} is what the player reads; {@code reason} is the English detail for the log, because a
     * dedicated server may not translate the message.
     */
    public record Refusal(Component message, String reason) {}

    static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(WorldFormatGate::check);
    }

    private static void check(MinecraftServer server) {
        Optional<Refusal> refusal = refusal(server);
        if (refusal.isEmpty()) {
            return;
        }
        String text = refusal.get().message().getString() + " (" + refusal.get().reason() + ")";
        if (!server.isDedicatedServer()) {
            throw new IllegalStateException("Refusing to start this world: " + text);
        }
        DeepCharter.LOGGER.error("Refusing to start this world: {}", text);
        Runtime.getRuntime().halt(1);
    }

    /** Why the world of {@code server} may not start, or empty when it may. */
    public static Optional<Refusal> refusal(MinecraftServer server) {
        return refusal(server.getDataStorage());
    }

    /**
     * Why the world in {@code storage} may not start, or empty when it may. It reads with {@code get}, never {@code computeIfAbsent}:
     * that would make a default record, which is dirty, and the next save would write a file into a world that is refused.
     */
    public static Optional<Refusal> refusal(SavedDataStorage storage) {
        ColonySite site = storage.get(ColonySite.TYPE);
        // An unreadable colony is another build's data: its own accessors log it and skip.
        if (site == null || !site.isReadable() || !site.isBuilt()) {
            return Optional.empty();
        }
        WorldFormat stamp = storage.get(WorldFormat.TYPE);
        if (stamp == null) {
            return Optional.of(refused("the world has a colony and no world-format stamp"));
        }
        if (!stamp.isReadable()) {
            return Optional.of(new Refusal(
                    Component.translatable("deepcharter.world_format.unreadable"),
                    "the world-format stamp has a version this build cannot read"));
        }
        OptionalInt format = stamp.format();
        if (format.isEmpty()) {
            return Optional.of(refused("the world has a colony and an empty world-format stamp"));
        }
        if (format.getAsInt() < WorldFormat.CURRENT) {
            return Optional.of(
                    refused("the world is format " + format.getAsInt() + ", this build needs " + WorldFormat.CURRENT));
        }
        return Optional.empty();
    }

    private static Refusal refused(String reason) {
        return new Refusal(Component.translatable("deepcharter.world_format.refused"), reason);
    }
}
