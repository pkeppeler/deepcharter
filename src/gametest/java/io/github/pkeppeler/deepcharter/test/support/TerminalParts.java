package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

/**
 * Repairs a terminal the way the right-click shortcut does ({@link Terminals#insert}), which holds the rules of the
 * repair
 * menu: one part from the player's inventory, or a refusal that takes nothing.
 */
public final class TerminalParts {
    private TerminalParts() {}

    public static Optional<TerminalRefusal> insert(ServerPlayer player, BlockPos pos, Item part) {
        return Terminals.insert(player, pos, part);
    }
}
