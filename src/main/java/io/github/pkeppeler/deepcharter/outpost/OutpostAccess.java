package io.github.pkeppeler.deepcharter.outpost;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Who may use a terminal at depth. A pump or a sell terminal in a layer is an outpost's (SPEC section 11), and an outpost belongs to its
 * charter: sharing it is a toggle that is off (a later change adds it), so only the charter whose hall the terminal stands in is served.
 * A terminal at depth that stands in no hall of the acting charter, such as one placed by hand, serves no one.
 */
public final class OutpostAccess {
    private OutpostAccess() {}

    /** {@code action}, run only when {@link #refusal} has none. */
    public static TerminalAction ownerOnly(TerminalAction action) {
        return context -> refusal(context).or(() -> action.run(context));
    }

    /** Why the acting charter may not use the terminal at {@code context.pos()}, or empty at the colony and in the charter's own hall. */
    public static Optional<Component> refusal(TerminalAction.Context context) {
        OptionalInt layer = OutpostRates.layerOf(context.player().level());
        if (layer.isEmpty()) {
            return Optional.empty();
        }
        MinecraftServer server = context.server();
        OutpostData data = OutpostData.get(server);
        if (!data.isReadable()) {
            return Optional.of(Component.translatable("message.deepcharter.outpost.unreadable"));
        }
        boolean mine = context.charter()
                .map(Charter::id)
                .flatMap(charter -> data.of(charter, layer.getAsInt()))
                .filter(outpost -> outpost.contains(context.pos()))
                .isPresent();
        return mine ? Optional.empty() : Optional.of(Component.translatable("message.deepcharter.outpost.not_yours"));
    }
}
