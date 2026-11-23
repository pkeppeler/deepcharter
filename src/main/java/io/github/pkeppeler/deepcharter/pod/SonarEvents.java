package io.github.pkeppeler.deepcharter.pod;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.core.BlockPos;

/**
 * The hooks other features use to read and answer the navigator's sonar (#988). Both run on the server only.
 *
 * <ul>
 *   <li>{@link #COLLECT}: what a ping returns. The blocks and the creatures in {@code deepcharter:sonar_echoes} are listeners of it; a creature
 *       signature (#924) is another, and picks one of the {@link SonarKind}s.</li>
 *   <li>{@link #PINGED}: the ping's noise, after the pod has paid and its echoes are set. Nothing in the game listens yet: #924 makes the
 *       Sleeper's creatures answer it, and adds the distance test with its first caller.</li>
 * </ul>
 */
public final class SonarEvents {
    /** What a ping returns. A listener adds its echoes to the sink; the ping sorts and caps the lot. */
    public static final Event<Collect> COLLECT =
            EventFactory.createArrayBacked(Collect.class, listeners -> (ping, sink) -> {
                for (Collect listener : listeners) {
                    listener.collect(ping, sink);
                }
            });

    /** A pod has pinged. The ping carries its noise radius, {@link SonarPing#noiseBlocks()}, and where it came from. */
    public static final Event<Pinged> PINGED = EventFactory.createArrayBacked(Pinged.class, listeners -> ping -> {
        for (Pinged listener : listeners) {
            listener.onPinged(ping);
        }
    });

    private SonarEvents() {}

    @FunctionalInterface
    public interface Collect {
        void collect(SonarPing ping, Sink sink);
    }

    /** Where a {@link Collect} listener puts an echo. */
    @FunctionalInterface
    public interface Sink {
        void add(SonarKind kind, BlockPos pos);
    }

    @FunctionalInterface
    public interface Pinged {
        void onPinged(SonarPing ping);
    }
}
