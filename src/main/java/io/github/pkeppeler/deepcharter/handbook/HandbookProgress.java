package io.github.pkeppeler.deepcharter.handbook;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterEvents;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;

/**
 * Directive progress, which belongs to the charter: a directive one member completes is complete for everyone on it, and a
 * new member inherits what is done. A directive is complete when the advancement with its id is done for one online member.
 * Our own code reaches that through {@link Directives#fire}; a vanilla criterion reaches it by the poll in {@link #sweep}.
 *
 * <p>A player's directive advancements mirror their current charter: on join, on founding or joining a charter, and on leaving
 * one, the player is given the advancements of the directives the charter has done and loses the others. So what a player did
 * alone, or on another charter, earns this charter nothing, and the player can earn it again here.
 *
 * <p>Everything on a tick, join or sync path reads through {@link #readableData}: when the saved charters or the saved progress
 * are of a version this build cannot read, it logs once and does nothing, and {@link #completedFor} is empty. {@link #completed}
 * is an explicit call and throws instead.
 */
public final class HandbookProgress {
	private HandbookProgress() {
	}

	/** Syncs progress to clients, mirrors it into advancements, and polls vanilla criteria. */
	static void register() {
		HandbookSyncPayload.register();
		ServerPlayerEvents.JOIN.register(player -> refresh(player.level().getServer(), player));
		CharterEvents.FOUNDED.register((server, charter) -> refreshAll(server, charter.roster()));
		CharterEvents.REVIVED.register((server, charter, director) -> refreshAll(server, List.of(director)));
		CharterEvents.JOINED.register((server, charter, player) -> refreshAll(server, List.of(player)));
		CharterEvents.LEFT.register((server, charter, player) -> refreshAll(server, List.of(player)));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (player.tickCount % HandbookTuning.DEFAULT.progressPollTicks() == 0) {
					sweep(player);
				}
			}
		});
	}

	/**
	 * The directives {@code charter} has completed.
	 *
	 * @throws IllegalStateException if the saved progress is of a version this build cannot read
	 */
	public static Set<Identifier> completed(MinecraftServer server, CharterId charter) {
		return HandbookProgressData.get(server).completed(charter);
	}

	/**
	 * The directives complete for {@code player}: those of their charter, or none when they are on none. Empty, and logged once,
	 * while the saved charters or progress are unreadable.
	 */
	public static Set<Identifier> completedFor(MinecraftServer server, UUID player) {
		Optional<HandbookProgressData> data = readableData(server);
		if (data.isEmpty()) {
			return Set.of();
		}
		return Charters.charterOf(server, player).map(charter -> data.get().completed(charter.id())).orElse(Set.of());
	}

	/**
	 * Completes, for {@code player}'s charter, every directive whose advancement {@code player} has finished. A player on no
	 * charter completes nothing. Does nothing while the saved charters or progress are unreadable.
	 */
	public static void sweep(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		Optional<HandbookProgressData> readable = readableData(server);
		if (readable.isEmpty()) {
			return;
		}
		Optional<Charter> charter = Charters.charterOf(server, player.getUUID());
		if (charter.isEmpty()) {
			return;
		}
		HandbookProgressData data = readable.get();
		Set<Identifier> done = data.completed(charter.get().id());
		boolean changed = false;
		for (Identifier directive : HandbookChapters.directivesOrEmpty(server)) {
			if (done.contains(directive)) {
				continue;
			}
			AdvancementHolder advancement = server.getAdvancements().get(directive);
			if (advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone()) {
				changed |= data.complete(charter.get().id(), directive);
			}
		}
		if (changed) {
			charter.get().roster().forEach(member -> HandbookSyncPayload.send(server, member));
		}
	}

	/** The progress data, or empty (logged once for each saved-data object) when the saved charters or the saved progress are unreadable. */
	static Optional<HandbookProgressData> readableData(MinecraftServer server) {
		if (!Charters.isReadable(server)) {
			return Optional.empty();
		}
		return Optional.of(HandbookProgressData.get(server)).filter(HandbookProgressData::isReadable);
	}

	private static void refreshAll(MinecraftServer server, Iterable<UUID> players) {
		for (UUID id : players) {
			ServerPlayer online = server.getPlayerList().getPlayer(id);
			if (online != null) {
				refresh(server, online);
			}
		}
	}

	private static void refresh(MinecraftServer server, ServerPlayer player) {
		mirror(server, player);
		HandbookSyncPayload.send(server, player);
	}

	/**
	 * Makes {@code player}'s directive advancements match their charter: done for the directives the charter has completed, not
	 * done for the rest. A player on no charter has none done.
	 */
	private static void mirror(MinecraftServer server, ServerPlayer player) {
		Optional<HandbookProgressData> data = readableData(server);
		if (data.isEmpty()) {
			return;
		}
		Set<Identifier> done = Charters.charterOf(server, player.getUUID()).map(charter -> data.get().completed(charter.id())).orElse(Set.of());
		for (Identifier directive : HandbookChapters.directivesOrEmpty(server)) {
			AdvancementHolder advancement = server.getAdvancements().get(directive);
			if (advancement == null) {
				continue;
			}
			AdvancementProgress progress = player.getAdvancements().getOrStartProgress(advancement);
			if (done.contains(directive)) {
				for (String criterion : toList(progress.getRemainingCriteria())) {
					player.getAdvancements().award(advancement, criterion);
				}
			} else {
				for (String criterion : toList(progress.getCompletedCriteria())) {
					player.getAdvancements().revoke(advancement, criterion);
				}
			}
		}
	}

	/** A copy: awarding and revoking change the progress being read. */
	private static List<String> toList(Iterable<String> criteria) {
		List<String> list = new ArrayList<>();
		criteria.forEach(list::add);
		return list;
	}
}
