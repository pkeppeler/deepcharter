package io.github.pkeppeler.deepcharter.charter;

import java.util.UUID;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import net.minecraft.server.MinecraftServer;

/**
 * Server-side events for changes to a charter. They fire from {@link Charters} after the change is made and saved, with the
 * charter as it is now. A listener must not change the charter's people from inside the event.
 */
public final class CharterEvents {
	/** A charter was founded. Its Director is the founder. */
	public static final Event<Founded> FOUNDED = EventFactory.createArrayBacked(Founded.class, listeners -> (server, charter) -> {
		for (Founded listener : listeners) {
			listener.onFounded(server, charter);
		}
	});

	/** A player applied to the charter. */
	public static final Event<Applied> APPLIED = EventFactory.createArrayBacked(Applied.class, listeners -> (server, charter, applicant) -> {
		for (Applied listener : listeners) {
			listener.onApplied(server, charter, applicant);
		}
	});

	/** The Director approved an application: the player is now crew. */
	public static final Event<Joined> JOINED = EventFactory.createArrayBacked(Joined.class, listeners -> (server, charter, player) -> {
		for (Joined listener : listeners) {
			listener.onJoined(server, charter, player);
		}
	});

	/** A Director or crew member left. Not fired for a withdrawn application. */
	public static final Event<Left> LEFT = EventFactory.createArrayBacked(Left.class, listeners -> (server, charter, player) -> {
		for (Left listener : listeners) {
			listener.onLeft(server, charter, player);
		}
	});

	/** The Director left and crew took over. Fires after {@link #LEFT}. */
	public static final Event<DirectorChanged> DIRECTOR_CHANGED = EventFactory.createArrayBacked(DirectorChanged.class, listeners -> (server, charter, previous, next) -> {
		for (DirectorChanged listener : listeners) {
			listener.onDirectorChanged(server, charter, previous, next);
		}
	});

	/** The last person left. The charter keeps its account and progress. Fires after {@link #LEFT}. */
	public static final Event<WentDormant> WENT_DORMANT = EventFactory.createArrayBacked(WentDormant.class, listeners -> (server, charter) -> {
		for (WentDormant listener : listeners) {
			listener.onWentDormant(server, charter);
		}
	});

	/** The account changed by {@code delta}: positive for a deposit, negative for a spend. */
	public static final Event<AccountChanged> ACCOUNT_CHANGED = EventFactory.createArrayBacked(AccountChanged.class, listeners -> (server, charter, delta) -> {
		for (AccountChanged listener : listeners) {
			listener.onAccountChanged(server, charter, delta);
		}
	});

	private CharterEvents() {
	}

	@FunctionalInterface
	public interface Founded {
		void onFounded(MinecraftServer server, Charter charter);
	}

	@FunctionalInterface
	public interface Applied {
		void onApplied(MinecraftServer server, Charter charter, UUID applicant);
	}

	@FunctionalInterface
	public interface Joined {
		void onJoined(MinecraftServer server, Charter charter, UUID player);
	}

	@FunctionalInterface
	public interface Left {
		void onLeft(MinecraftServer server, Charter charter, UUID player);
	}

	@FunctionalInterface
	public interface DirectorChanged {
		void onDirectorChanged(MinecraftServer server, Charter charter, UUID previous, UUID next);
	}

	@FunctionalInterface
	public interface WentDormant {
		void onWentDormant(MinecraftServer server, Charter charter);
	}

	@FunctionalInterface
	public interface AccountChanged {
		void onAccountChanged(MinecraftServer server, Charter charter, long delta);
	}
}
