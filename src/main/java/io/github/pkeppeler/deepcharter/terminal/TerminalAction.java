package io.github.pkeppeler.deepcharter.terminal;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import io.github.pkeppeler.deepcharter.charter.Charter;

/**
 * What a repaired terminal does when a player presses one of its buttons. {@link Terminals#act} has already checked that the
 * terminal exists, that the player is within range and on a charter, and that the terminal is repaired, so the handler
 * only applies its own rules. Register one with {@link TerminalActions#register}.
 */
@FunctionalInterface
public interface TerminalAction {
	/** Runs the action: empty when it was done, or the reason it was refused, which the player is shown. */
	Optional<Component> run(Context context);

	/** @param args what the client sent with the action. It is untrusted: read it defensively. */
	record Context(MinecraftServer server, ServerPlayer player, Charter charter, TerminalType type, BlockPos pos, CompoundTag args) {
	}
}
