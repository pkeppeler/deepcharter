package io.github.pkeppeler.deepcharter.outpost;

import io.github.pkeppeler.deepcharter.outpost.OutpostData.Outpost;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/** STUB. */
public final class OutpostRiskNotices {
    private OutpostRiskNotices() {}

    /** What a creature would find. */
    public record Unattended(int exposed, int gaps, boolean dark) {}

    public static void init() {}

    public static Optional<Unattended> unattended(ServerLevel level, Outpost outpost) {
        return Optional.empty();
    }

    public static List<Component> lines(Optional<Ratings> required, Unattended unattended) {
        return List.of();
    }
}
