package io.github.pkeppeler.deepcharter.market;

import io.github.pkeppeler.deepcharter.outpost.OutpostAccess;
import io.github.pkeppeler.deepcharter.terminal.TerminalActions;
import io.github.pkeppeler.deepcharter.terminal.TerminalFeatures;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;

/** Gives the ore processor terminal its two sales, its ore withdrawal, its fuse, its crate press and its work orders. */
public final class MarketRegistry {
    private MarketRegistry() {}

    public static void register() {
        TerminalActions.register(
                TerminalTypes.ORE_PROCESSOR, OreProcessor.SELL_CARGO, OutpostAccess.ownerOnly(OreProcessor::sellCargo));
        TerminalActions.register(
                TerminalTypes.ORE_PROCESSOR,
                OreProcessor.SELL_INVENTORY,
                OutpostAccess.ownerOnly(OreProcessor::sellInventory));
        TerminalActions.register(
                TerminalTypes.ORE_PROCESSOR, OreProcessor.FUSE_SPOIL, OutpostAccess.ownerOnly(OreProcessor::fuseSpoil));
        TerminalActions.register(
                TerminalTypes.ORE_PROCESSOR,
                OreProcessor.PRESS_CRATES,
                OutpostAccess.ownerOnly(OreProcessor::pressCrates));
        TerminalActions.register(
                TerminalTypes.ORE_PROCESSOR,
                OreProcessor.WITHDRAW_ORE,
                OutpostAccess.ownerOnly(OreProcessor::withdrawOre));
        TerminalFeatures.register(TerminalTypes.ORE_PROCESSOR, ProcessorView.STREAM_CODEC, OreProcessor::view);
        WorkOrders.register();
    }
}
