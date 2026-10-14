package io.github.pkeppeler.deepcharter.market;

import io.github.pkeppeler.deepcharter.terminal.TerminalActions;
import io.github.pkeppeler.deepcharter.terminal.TerminalFeatures;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;

/** Gives the ore processor terminal its two sales, its ore withdrawal, its fuse, its crate press and its work orders. */
public final class MarketRegistry {
    private MarketRegistry() {}

    public static void register() {
        TerminalActions.register(TerminalTypes.ORE_PROCESSOR, OreProcessor.SELL_CARGO, OreProcessor::sellCargo);
        TerminalActions.register(TerminalTypes.ORE_PROCESSOR, OreProcessor.SELL_INVENTORY, OreProcessor::sellInventory);
        TerminalActions.register(TerminalTypes.ORE_PROCESSOR, OreProcessor.FUSE_SPOIL, OreProcessor::fuseSpoil);
        TerminalActions.register(TerminalTypes.ORE_PROCESSOR, OreProcessor.PRESS_CRATES, OreProcessor::pressCrates);
        TerminalActions.register(TerminalTypes.ORE_PROCESSOR, OreProcessor.WITHDRAW_ORE, OreProcessor::withdrawOre);
        TerminalFeatures.register(TerminalTypes.ORE_PROCESSOR, ProcessorView.STREAM_CODEC, OreProcessor::view);
        WorkOrders.register();
    }
}
