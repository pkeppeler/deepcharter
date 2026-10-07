package io.github.pkeppeler.deepcharter.handbook;

import java.util.Objects;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Completes a handbook directive. Any feature calls this when a player does the thing a directive
 * asks for; it needs no handbook code to exist yet.
 */
public final class Directives {
	// Filled by #61: today this does nothing. #61 keeps this signature, so callers never change.

	private Directives() {
	}

	/** Completes {@code directive} for {@code player}'s whole charter. A directive that is already done stays done. */
	public static void fire(ServerPlayer player, Identifier directive) {
		Objects.requireNonNull(player, "player");
		Objects.requireNonNull(directive, "directive");
	}
}
