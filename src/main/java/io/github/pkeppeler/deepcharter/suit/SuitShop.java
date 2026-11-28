package io.github.pkeppeler.deepcharter.suit;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;
import io.github.pkeppeler.deepcharter.terminal.TerminalActions;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalWares;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * The suit tracks' shop (#1064, ADR 0091): the repair station sells the next tier of a track to the person at its screen, who is the one
 * who wears it. Every refusal comes before the spend, and a refused buy changes nothing and charges nothing.
 */
public final class SuitShop {
    /** Buy tier {@code args.tier} of the track {@code args.track}, which must be the one after the tier the person has. */
    public static final Identifier SUIT_BUY = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "suit_buy");

    public static final String TRACK_KEY = "track";
    public static final String TIER_KEY = "tier";

    private SuitShop() {}

    static void init() {
        TerminalActions.register(TerminalTypes.REPAIR_STATION, SUIT_BUY, SuitShop::buy);
        for (SuitTrack track : SuitTrack.values()) {
            TerminalWares.register(
                    TerminalTypes.REPAIR_STATION,
                    "suit:" + track.id(),
                    "screen.deepcharter.repair.about.suit." + track.id());
        }
    }

    private static Optional<Component> buy(TerminalAction.Context context) {
        ServerPlayer player = context.player();
        Optional<SuitTrack> track = context.args().getString(TRACK_KEY).flatMap(SuitTrack::byId);
        Optional<Integer> tier = context.args().getInt(TIER_KEY);
        if (track.isEmpty() || tier.isEmpty()) {
            return refusal("unknown_track");
        }
        Optional<SuitTracks> held = Versioned.readable(player, SuitRegistry.TRACKS);
        Optional<SuitState> state = Versioned.readable(player, SuitRegistry.SUIT);
        if (held.isEmpty() || state.isEmpty()) {
            return refusal("tracks_unreadable");
        }
        int current = held.get().tier(track.get());
        // A second click on the row just bought, before the screen moved on.
        if (tier.get() == current && current >= 1) {
            return refusal("already_fitted");
        }
        if (current >= track.get().maxTier()) {
            return refusal("top_tier");
        }
        if (tier.get() != current + 1) {
            return refusal("not_next");
        }
        Optional<CharterRefusal> unpaid = Charters.spend(
                context.server(),
                context.charter().orElseThrow().id(),
                track.get().price(tier.get()));
        if (unpaid.isPresent()) {
            return Optional.of(unpaid.get().message());
        }
        SuitTracks fitted = held.get().with(track.get(), tier.get());
        player.setAttached(SuitRegistry.TRACKS, Versioned.of(fitted));
        if (track.get() == SuitTrack.AIR) {
            player.setAttached(
                    SuitRegistry.SUIT, Versioned.of(state.get().withReserve(fitted.capacity(SuitTuning.DEFAULT))));
        }
        player.sendOverlayMessage(Component.translatable(
                        "message.deepcharter.suit.fitted",
                        Component.translatable(
                                "hud.deepcharter.suit.track." + track.get().id()),
                        tier.get())
                .withStyle(ChatFormatting.GREEN));
        return Optional.empty();
    }

    private static Optional<Component> refusal(String reason) {
        return Optional.of(Component.translatable("message.deepcharter.suit." + reason));
    }
}
