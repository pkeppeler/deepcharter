package io.github.pkeppeler.deepcharter.test.support;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.Terminals;

/** Repairs a terminal the way its screen does: the {@link Terminals#INSERT_PART} action, which the action packet runs through {@link Terminals#act}. */
public final class TerminalParts {
	private TerminalParts() {
	}

	public static Optional<TerminalRefusal> insert(ServerPlayer player, BlockPos pos, Item part) {
		CompoundTag args = new CompoundTag();
		args.putString(Terminals.PART_KEY, BuiltInRegistries.ITEM.getKey(part).toString());
		return Terminals.act(player, pos, Terminals.INSERT_PART, args);
	}
}
