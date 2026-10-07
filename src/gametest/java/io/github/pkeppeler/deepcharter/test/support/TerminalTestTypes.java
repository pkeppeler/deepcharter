package io.github.pkeppeler.deepcharter.test.support;

import java.util.List;
import java.util.Optional;

import net.fabricmc.api.ModInitializer;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.terminal.TerminalActions;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;

/**
 * Terminal types for the tests, registered by the test mod only so that no test handler ever sits on a production terminal.
 * A terminal block can only be registered while mods initialise, which is why this is an entrypoint.
 *
 * <ul>
 *   <li>{@link #REPAIRABLE}: for charters, one part (a flint), with the {@link #PING} and {@link #DENY} actions;</li>
 *   <li>{@link #OPEN}: always online, for anyone, with {@link #PING}.</li>
 * </ul>
 */
public final class TerminalTestTypes implements ModInitializer {
	public static final Identifier PING = Identifier.fromNamespaceAndPath("deepcharter_test", "ping");
	public static final Identifier DENY = Identifier.fromNamespaceAndPath("deepcharter_test", "deny");

	public static final TerminalType REPAIRABLE = TerminalTypes.register(Identifier.fromNamespaceAndPath("deepcharter_test", "repairable_terminal"), List.of(Items.FLINT));
	public static final TerminalType OPEN = TerminalTypes.registerAlwaysOnline(Identifier.fromNamespaceAndPath("deepcharter_test", "open_terminal"), TerminalType.Access.ANYONE);

	private static int pings;
	private static Optional<Optional<Charter>> lastPinger = Optional.empty();

	static {
		for (TerminalType type : List.of(REPAIRABLE, OPEN)) {
			TerminalActions.register(type, PING, context -> {
				pings++;
				lastPinger = Optional.of(context.charter());
				return Optional.empty();
			});
		}
		TerminalActions.register(REPAIRABLE, DENY, context -> Optional.of(Component.literal("denied for the test")));
	}

	/** How many times a {@link #PING} handler has run. */
	public static int pings() {
		return pings;
	}

	/** The charter the last ping ran for (itself empty for a player on none), or empty when nothing has pinged. */
	public static Optional<Optional<Charter>> lastPinger() {
		return lastPinger;
	}

	/** Loading this class registers the types; the entrypoint makes sure that happens at startup. */
	@Override
	public void onInitialize() {
	}
}
