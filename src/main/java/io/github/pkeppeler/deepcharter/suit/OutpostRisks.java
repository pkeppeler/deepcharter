package io.github.pkeppeler.deepcharter.suit;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.outpost.OutpostData;
import io.github.pkeppeler.deepcharter.outpost.OutpostData.Outpost;
import io.github.pkeppeler.deepcharter.outpost.OutpostHabitability;
import io.github.pkeppeler.deepcharter.outpost.RatingAxis;
import io.github.pkeppeler.deepcharter.outpost.Ratings;
import io.github.pkeppeler.deepcharter.terminal.TerminalNotices;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The risk line of an outpost's terminals (#993, ADR 0083): one line under the header that says whether the shell holds and what it costs
 * if it does not. Green says the shell holds, amber says it leaks or that nothing is rated here; where the depth asks nothing there is no
 * line. Any player who opens the terminal sees it, a guest as well as the owner.
 */
public final class OutpostRisks {
    private OutpostRisks() {}

    static void init() {
        TerminalNotices.register(OutpostRisks::notices);
    }

    private static List<Component> notices(
            MinecraftServer server, ServerPlayer player, Optional<Charter> charter, BlockPos pos) {
        OutpostData data = OutpostData.get(server);
        if (!data.isReadable()) {
            return List.of();
        }
        ServerLevel level = player.level();
        Optional<Outpost> hall = data.enclosing(level.dimension(), pos);
        if (hall.isEmpty()) {
            return List.of();
        }
        Optional<Ratings> required = OutpostHabitability.requiredAt(level, hall.get());
        return lines(required, OutpostHabitability.of(level, hall.get(), required));
    }

    /**
     * The line for a shell in {@code status} against a depth that asks {@code required}: none where the depth asks nothing of a suit
     * ({@link SuitDrain#asks}). Only the holding line is marked good, so the screens draw it in phosphor and the others in amber.
     */
    public static List<Component> lines(Optional<Ratings> required, OutpostHabitability.Status status) {
        if (required.isPresent() && !SuitDrain.asks(required.get())) {
            return List.of();
        }
        if (required.isEmpty()) {
            return List.of(Component.translatable("notice.deepcharter.outpost.unrated"));
        }
        if (status.habitable()) {
            return List.of(TerminalNotices.good(Component.translatable("notice.deepcharter.outpost.holds")));
        }
        return List.of(Component.translatable(
                "notice.deepcharter.outpost.leaks",
                RatingAxis.list(status.shortOn().stream().sorted().toList()),
                status.unrated(),
                Math.round(SuitTuning.DEFAULT.leakShare() * 100)));
    }
}
