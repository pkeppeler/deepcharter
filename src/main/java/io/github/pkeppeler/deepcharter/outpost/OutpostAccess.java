package io.github.pkeppeler.deepcharter.outpost;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.outpost.OutpostData.Outpost;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Who may use a terminal at depth. A pump or a sell terminal in a layer is an outpost's (SPEC section 11): the charter whose hall it
 * stands in is always served, and another charter only while the owner's Director has opened the charter's outposts. A guest gets the
 * outpost's own rates ({@link OutpostRates}); SPEC section 6 allows no money between charters. A dormant owner's outposts are closed to
 * guests (nobody can close them), and the setting is kept. A terminal in no hall, such as one placed by hand, serves no one.
 */
public final class OutpostAccess {
    private OutpostAccess() {}

    /** {@code action}, run only when {@link #refusal} has none. */
    public static TerminalAction guarded(TerminalAction action) {
        return context -> refusal(context).or(() -> action.run(context));
    }

    /**
     * Why the acting charter may not use the terminal at {@code context.pos()}, or empty at the colony, in the charter's own hall and in a
     * hall its owner has shared.
     */
    public static Optional<Component> refusal(TerminalAction.Context context) {
        OptionalInt layer =
                OutpostRates.layerOf(context.player().level(), context.pos().getY());
        if (layer.isEmpty()) {
            return Optional.empty();
        }
        MinecraftServer server = context.server();
        OutpostData data = OutpostData.get(server);
        if (!data.isReadable()) {
            return Optional.of(Component.translatable("message.deepcharter.outpost.unreadable"));
        }
        Optional<CharterId> owner =
                data.containing(layer.getAsInt(), context.pos()).map(Outpost::charter);
        if (owner.isEmpty()) {
            return Optional.of(Component.translatable("message.deepcharter.outpost.not_yours"));
        }
        Optional<CharterId> acting = context.charter().map(Charter::id);
        if (acting.equals(owner)) {
            return Optional.empty();
        }
        boolean shared = acting.isPresent()
                && Charters.readableFind(server, owner.get())
                        .filter(Charter::outpostsShared)
                        .filter(charter -> !charter.dormant())
                        .isPresent();
        return shared ? Optional.empty() : Optional.of(Component.translatable("message.deepcharter.outpost.closed"));
    }
}
