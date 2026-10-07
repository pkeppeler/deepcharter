package io.github.pkeppeler.deepcharter.handbook;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Completes a handbook directive. Any feature calls this when a player does the thing a directive
 * asks for.
 *
 * <p>A directive is defined by a handbook chapter and completed by the advancement with the same id. This meets that advancement's
 * {@code deepcharter:directive} criterion; the advancement may list vanilla criteria as well, which complete the directive
 * without any call here (see {@link HandbookProgress#sweep}).
 */
public final class Directives {
	private static final Logger LOGGER = LoggerFactory.getLogger(Directives.class);
	private static final Set<Identifier> WARNED_UNKNOWN = new HashSet<>();

	private Directives() {
	}

	/**
	 * Completes {@code directive} for {@code player}'s whole charter. A directive that is already done stays done. A player on
	 * no charter completes nothing. A directive no chapter defines completes nothing and is logged once: a feature may call
	 * this before the chapter that defines the directive exists.
	 */
	public static void fire(ServerPlayer player, Identifier directive) {
		Objects.requireNonNull(player, "player");
		Objects.requireNonNull(directive, "directive");
		MinecraftServer server = player.level().getServer();
		if (!HandbookChapters.directives(server).contains(directive)) {
			if (WARNED_UNKNOWN.add(directive)) {
				LOGGER.warn("Directives.fire: no handbook chapter defines directive {}, so nothing was completed", directive);
			}
			return;
		}
		HandbookRegistry.DIRECTIVE_TRIGGER.trigger(player, directive);
		HandbookProgress.sweep(player);
	}
}
