package io.github.pkeppeler.deepcharter.charter;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;

/**
 * The server-side API of charters, and the one place the rest of the mod changes them. Each operation applies the rules of
 * {@link CharterData}, tells the affected players' clients, then fires the matching {@link CharterEvents}, so a listener that
 * throws cannot leave a client stale. A refused
 * operation does none of that. Call everything on the server thread.
 */
public final class Charters {
	private Charters() {
	}

	/**
	 * False when the saved charters are of a version this build cannot read, so every call here that reads them throws. Logs once
	 * when not. The readable forms below check this themselves; check it first only to guard several reads at once.
	 */
	public static boolean isReadable(MinecraftServer server) {
		return CharterData.get(server).isReadable();
	}

	/**
	 * The charter {@code player} is on, as Director or crew; empty when they are on none or the saved charters are unreadable
	 * (logged once). Never throws: for tick, join, sync and callback paths.
	 */
	public static Optional<Charter> readableCharterOf(MinecraftServer server, UUID player) {
		return isReadable(server) ? CharterData.get(server).charterOf(player) : Optional.empty();
	}

	/** The charter with this id; empty when there is none or the saved charters are unreadable (logged once). Never throws. */
	public static Optional<Charter> readableFind(MinecraftServer server, CharterId id) {
		return isReadable(server) ? CharterData.get(server).find(id) : Optional.empty();
	}

	/** The charter {@code player} is on, as Director or crew. Throws when the saved charters are unreadable: for commands and explicit actions. */
	public static Optional<Charter> charterOfOrThrow(MinecraftServer server, UUID player) {
		return CharterData.get(server).charterOf(player);
	}

	/** The charter with this id. Throws when the saved charters are unreadable: for commands and explicit actions. */
	public static Optional<Charter> findOrThrow(MinecraftServer server, CharterId id) {
		return CharterData.get(server).find(id);
	}

	/** The charter with this name, ignoring case. */
	public static Optional<Charter> findByName(MinecraftServer server, String name) {
		return CharterData.get(server).findByName(name);
	}

	/** Every charter. Throws when the saved charters are unreadable: for commands and explicit actions. */
	public static Collection<Charter> allOrThrow(MinecraftServer server) {
		return CharterData.get(server).all();
	}

	public static Optional<CharterRefusal> found(MinecraftServer server, UUID founder, String name) {
		CharterData data = CharterData.get(server);
		CharterId id = CharterId.random();
		Optional<CharterRefusal> refusal = data.found(founder, name, id);
		if (refusal.isEmpty()) {
			Charter charter = data.find(id).orElseThrow();
			sync(server, charter.roster());
			CharterEvents.FOUNDED.invoker().onFounded(server, charter);
		}
		return refusal;
	}

	public static Optional<CharterRefusal> apply(MinecraftServer server, UUID applicant, CharterId id) {
		CharterData data = CharterData.get(server);
		Optional<CharterRefusal> refusal = data.apply(applicant, id);
		if (refusal.isEmpty()) {
			CharterEvents.APPLIED.invoker().onApplied(server, data.find(id).orElseThrow(), applicant);
		}
		return refusal;
	}

	/** {@code player} revives the dormant charter {@code id} and becomes its Director. */
	public static Optional<CharterRefusal> revive(MinecraftServer server, UUID player, CharterId id) {
		CharterData data = CharterData.get(server);
		Optional<CharterRefusal> refusal = data.revive(player, id);
		if (refusal.isEmpty()) {
			Charter charter = data.find(id).orElseThrow();
			sync(server, charter.roster());
			CharterEvents.REVIVED.invoker().onRevived(server, charter, player);
		}
		return refusal;
	}

	public static Optional<CharterRefusal> approve(MinecraftServer server, UUID director, UUID applicant) {
		CharterData data = CharterData.get(server);
		Optional<CharterRefusal> refusal = data.approve(director, applicant);
		if (refusal.isEmpty()) {
			Charter charter = data.charterOf(applicant).orElseThrow();
			sync(server, charter.roster());
			CharterEvents.JOINED.invoker().onJoined(server, charter, applicant);
		}
		return refusal;
	}

	public static Optional<CharterRefusal> deny(MinecraftServer server, UUID director, UUID applicant) {
		return CharterData.get(server).deny(director, applicant);
	}

	/** {@code player} leaves their charter or withdraws their application. */
	public static Optional<CharterRefusal> leave(MinecraftServer server, UUID player) {
		CharterData data = CharterData.get(server);
		Optional<Charter> before = data.charterOf(player);
		Optional<CharterRefusal> refusal = data.leave(player);
		if (refusal.isPresent() || before.isEmpty()) {
			return refusal;
		}
		Charter after = data.find(before.get().id()).orElseThrow();
		sync(server, List.of(player));
		sync(server, after.roster());
		CharterEvents.LEFT.invoker().onLeft(server, after, player);
		if (before.get().isDirector(player)) {
			if (after.dormant()) {
				CharterEvents.WENT_DORMANT.invoker().onWentDormant(server, after);
			} else {
				CharterEvents.DIRECTOR_CHANGED.invoker().onDirectorChanged(server, after, player, after.director().orElseThrow());
			}
		}
		return refusal;
	}

	public static Optional<CharterRefusal> deposit(MinecraftServer server, CharterId id, long amount) {
		return changeAccount(server, id, amount, CharterData.get(server).deposit(id, amount));
	}

	/** Takes from the account. The account never goes negative: an overdraft is refused and changes nothing. */
	public static Optional<CharterRefusal> spend(MinecraftServer server, CharterId id, long amount) {
		return changeAccount(server, id, -amount, CharterData.get(server).spend(id, amount));
	}

	/** Raises the charter's deepest point to {@code depth} blocks below the surface, if that is deeper than before. */
	public static Optional<CharterRefusal> recordDeepestPoint(MinecraftServer server, CharterId id, int depth) {
		CharterData data = CharterData.get(server);
		Optional<Charter> before = data.find(id);
		Optional<CharterRefusal> refusal = data.recordDeepestPoint(id, depth);
		if (refusal.isEmpty() && before.get().deepestPoint() < depth) {
			sync(server, data.find(id).orElseThrow().roster());
		}
		return refusal;
	}

	private static Optional<CharterRefusal> changeAccount(MinecraftServer server, CharterId id, long delta, Optional<CharterRefusal> refusal) {
		if (refusal.isEmpty()) {
			Charter charter = findOrThrow(server, id).orElseThrow();
			sync(server, charter.roster());
			CharterEvents.ACCOUNT_CHANGED.invoker().onAccountChanged(server, charter, delta);
		}
		return refusal;
	}

	private static void sync(MinecraftServer server, Collection<UUID> players) {
		players.forEach(player -> CharterSyncPayload.send(server, player));
	}
}
