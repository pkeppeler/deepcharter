package io.github.pkeppeler.deepcharter.sound;

import io.github.pkeppeler.deepcharter.fuel.FuelPump;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.market.OreProcessor;
import io.github.pkeppeler.deepcharter.repair.RepairStation;
import io.github.pkeppeler.deepcharter.terminal.TerminalEvents;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTerminal;
import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * The sound of a terminal button, played to the player who pressed it and to no one else: the sale and purchase sounds of the
 * actions below, and the error sound of every refusal. An action not listed here makes no sound of its own.
 */
public final class TerminalSounds {
    private static final Map<Identifier, DeepSound> ACTIONS = Map.ofEntries(
            Map.entry(OreProcessor.SELL_CARGO, DeepSound.UI_SALE),
            Map.entry(OreProcessor.SELL_INVENTORY, DeepSound.UI_SALE),
            Map.entry(OreProcessor.FUSE_SPOIL, DeepSound.UI_PURCHASE),
            Map.entry(OreProcessor.PRESS_CRATES, DeepSound.UI_PURCHASE),
            Map.entry(FuelPump.BUY, DeepSound.UI_PURCHASE),
            Map.entry(FuelPump.FILL, DeepSound.UI_PURCHASE),
            Map.entry(UpgradeTerminal.BUY, DeepSound.UI_PURCHASE),
            Map.entry(RepairStation.BUY, DeepSound.UI_PURCHASE),
            Map.entry(HangarTerminal.BUY_MOLE, DeepSound.UI_PURCHASE),
            Map.entry(HangarTerminal.RESTORE_WRECK, DeepSound.UI_PURCHASE));

    private TerminalSounds() {}

    static void init() {
        TerminalEvents.ACTED.register((server, type, player, action) -> {
            DeepSound sound = ACTIONS.get(action);
            if (sound != null) {
                SoundPackets.playTo(player, sound);
            }
        });
        TerminalEvents.REFUSED.register((player, refusal) -> SoundPackets.playTo(player, DeepSound.UI_ERROR));
    }
}
