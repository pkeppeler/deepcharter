package io.github.pkeppeler.deepcharter.colony;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * Refuses to start a world made in an older world format (ADR 0059). A world that has a built colony and a format lower than
 * {@link WorldFormat#CURRENT}, or a stamp that cannot be read, is refused. A world with no stamp is {@link WorldFormat#LEGACY}. A
 * world with no colony passes: it has no saved terrain that a new format could not hold, and its colony gets the stamp when it is
 * built.
 *
 * <p>The check runs on {@link ServerLifecycleEvents#SERVER_STARTING}, before the first level exists (the ordering is in the ADR).
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
        return refusal(server.getDataStorage(), WorldFormat.CURRENT);
    }

    /** Why the world in {@code storage} may not start, or empty when it may: the client asks it before a server exists. */
    public static Optional<Refusal> refusal(SavedDataStorage storage) {
        return refusal(storage, WorldFormat.CURRENT);
    }

    /**
     * Why the world in {@code storage} may not start in format {@code current}, or empty when it may. A world with no stamp, or an
     * empty one, is {@link WorldFormat#LEGACY}. It reads with {@code get}, never {@code computeIfAbsent}: that would make a default
     * record, which is dirty, and the next save would write a file into a world that is refused.
     */
    // const-param: the tests pass the format the gate runs at (LEGACY); production passes CURRENT
    // test-seam: a test asks the gate at the legacy format, to show that a world with no stamp file starts there
    public static Optional<Refusal> refusal(SavedDataStorage storage, int current) {
        ColonySite site = storage.get(ColonySite.TYPE);
        // An unreadable colony is another build's data: its own accessors log it and skip.
        if (site == null || !site.isReadable() || !site.isBuilt()) {
            return Optional.empty();
        }
        WorldFormat stamp = storage.get(WorldFormat.TYPE);
        if (stamp != null && !stamp.isReadable()) {
            return Optional.of(new Refusal(
                    Component.translatable("deepcharter.world_format.unreadable"),
                    "the world-format stamp has a version this build cannot read"));
        }
        int format = stamp == null ? WorldFormat.LEGACY : stamp.format().orElse(WorldFormat.LEGACY);
        if (format < current) {
            return Optional.of(refused("the world is format " + format + " (no stamp counts as " + WorldFormat.LEGACY
                    + "), this build needs " + current));
        }
        return Optional.empty();
    }

    private static Refusal refused(String reason) {
        return new Refusal(Component.translatable("deepcharter.world_format.refused"), reason);
    }
}
