package io.github.pkeppeler.deepcharter.handbook;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.advancements.AdvancementHolder;
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
 */
public final class HandbookProgress {
	private static final Logger LOGGER = LoggerFactory.getLogger(HandbookProgress.class);
	private static final Set<Identifier> WARNED_NO_ADVANCEMENT = new HashSet<>();

	private HandbookProgress() {
	}

	/** Syncs progress to clients, and polls vanilla criteria. */
	static void register() {
		HandbookSyncPayload.register();
		ServerPlayerEvents.JOIN.register(player -> HandbookSyncPayload.send(player.level().getServer(), player));
		CharterEvents.FOUNDED.register((server, charter) -> sync(server, charter.roster()));
		CharterEvents.JOINED.register((server, charter, player) -> HandbookSyncPayload.send(server, player));
		CharterEvents.LEFT.register((server, charter, player) -> HandbookSyncPayload.send(server, player));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (player.tickCount % HandbookTuning.DEFAULT.progressPollTicks() == 0) {
					sweep(player);
				}
			}
		});
	}

	/** The directives {@code charter} has completed. */
	public static Set<Identifier> completed(MinecraftServer server, CharterId charter) {
		return HandbookProgressData.get(server).completed(charter);
	}

	/** The directives complete for {@code player}: those of their charter, or none when they are on none. */
	public static Set<Identifier> completedFor(MinecraftServer server, UUID player) {
		return Charters.charterOf(server, player).map(charter -> completed(server, charter.id())).orElse(Set.of());
	}

	/**
	 * Completes, for {@code player}'s charter, every directive whose advancement {@code player} has finished. A player on no
	 * charter completes nothing: their progress is not held for a charter they might join.
	 */
	public static void sweep(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		Optional<Charter> charter = Charters.charterOf(server, player.getUUID());
		if (charter.isEmpty()) {
			return;
		}
		HandbookProgressData data = HandbookProgressData.get(server);
		boolean changed = false;
		for (Identifier directive : HandbookChapters.directives(server)) {
			if (data.completed(charter.get().id()).contains(directive)) {
				continue;
			}
			AdvancementHolder advancement = server.getAdvancements().get(directive);
			if (advancement == null) {
				if (WARNED_NO_ADVANCEMENT.add(directive)) {
					LOGGER.warn("Handbook directive {} has no advancement with the same id, so it can never complete", directive);
				}
				continue;
			}
			if (player.getAdvancements().getOrStartProgress(advancement).isDone()) {
				changed |= data.complete(charter.get().id(), directive);
			}
		}
		if (changed) {
			sync(server, charter.get().roster());
		}
	}

	private static void sync(MinecraftServer server, Iterable<UUID> players) {
		players.forEach(player -> HandbookSyncPayload.send(server, player));
	}
}
