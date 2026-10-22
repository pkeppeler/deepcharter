package io.github.pkeppeler.deepcharter.market;

import io.github.pkeppeler.deepcharter.outpost.OutpostAccess;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;
import io.github.pkeppeler.deepcharter.terminal.TerminalActions;
import io.github.pkeppeler.deepcharter.terminal.TerminalFeatures;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import net.minecraft.resources.Identifier;

/** Gives the ore processor terminal its two sales, its ore withdrawal, its fuse, its crate press and its work orders. */
public final class MarketRegistry {
    private MarketRegistry() {}

    public static void register() {
        ownerOnly(OreProcessor.SELL_CARGO, OreProcessor::sellCargo);
        ownerOnly(OreProcessor.SELL_INVENTORY, OreProcessor::sellInventory);
        ownerOnly(OreProcessor.FUSE_SPOIL, OreProcessor::fuseSpoil);
        ownerOnly(OreProcessor.PRESS_CRATES, OreProcessor::pressCrates);
        ownerOnly(OreProcessor.WITHDRAW_ORE, OreProcessor::withdrawOre);
        TerminalFeatures.register(TerminalTypes.ORE_PROCESSOR, ProcessorView.STREAM_CODEC, OreProcessor::view);
        WorkOrders.register();
    }

    private static void ownerOnly(Identifier action, TerminalAction handler) {
        TerminalActions.register(TerminalTypes.ORE_PROCESSOR, action, OutpostAccess.ownerOnly(handler));
    }
}
