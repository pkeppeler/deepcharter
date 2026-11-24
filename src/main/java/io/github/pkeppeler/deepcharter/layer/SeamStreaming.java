package io.github.pkeppeler.deepcharter.layer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/** Stub: filled in after the failing tests (#1159). */
public final class SeamStreaming {
    private SeamStreaming() {}

    /** One vehicle's approach to the floor of a level with a level below it. */
    public record Approach(
            UUID root,
            List<UUID> riders,
            ResourceKey<Level> upper,
            ResourceKey<Level> lower,
            ChunkPos centre,
            long startedTick,
            boolean ready) {}

    public static void init() {}

    public static int lead(int lowerHeight, LayerTuning tuning) {
        return 0;
    }

    public static boolean shouldStream(
            double depthAboveFloor, double descentPerTick, int lowerHeight, LayerTuning tuning) {
        return false;
    }

    public static Optional<Approach> approachOf(UUID root) {
        return Optional.empty();
    }
}
