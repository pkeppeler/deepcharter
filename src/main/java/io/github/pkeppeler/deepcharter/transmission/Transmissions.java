package io.github.pkeppeler.deepcharter.transmission;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;

/**
 * Fires a transmission for a charter. Any feature calls this when the event a transmission waits
 * for happens; it needs no transmission code to exist yet.
 *
 * <p>A fired transmission joins the charter's fired set and its queue, and pays its bonus, once. The queue is sent, in order, to
 * every online member of the charter, as soon as there is one: at once if somebody is online, otherwise when a member logs in or
 * joins. A charter founded after a repair transmission first fired is sent it too, with no bonus.
 *
 * <p>Call everything on the server thread. The overloads with a {@link TransmissionData} take the data to use instead of the
 * world's, so a test can run on state of its own.
 */
public final class Transmissions {
	private static final String UNKNOWN_DIRECTOR = "the Director";

	/** The running server, for {@link #fire(CharterId, Identifier)}, whose frozen signature has no server. Set while one is running. */
	private static MinecraftServer running;

	private Transmissions() {
	}

	static void started(MinecraftServer server) {
		running = server;
	}

	static void stopped() {
		running = null;
	}

	/**
	 * Fires {@code transmission} once for {@code charter}; a charter that has it already is not sent
	 * it again.
	 *
	 * @throws IllegalStateException if no server is running
	 * @throws IllegalArgumentException if the charter or the transmission does not exist
	 */
	public static void fire(CharterId charter, Identifier transmission) {
		Objects.requireNonNull(charter, "charter");
		Objects.requireNonNull(transmission, "transmission");
		if (running == null) {
			throw new IllegalStateException("Cannot fire " + transmission + ": no server is running");
		}
		fire(running, charter, transmission);
	}

	/** {@link #fire(CharterId, Identifier)} on a given server. */
	public static void fire(MinecraftServer server, CharterId charter, Identifier transmission) {
		fire(server, TransmissionData.get(server), charter, transmission);
	}

	/** {@link #fire(CharterId, Identifier)} with the transmission state in {@code data}. */
	public static void fire(MinecraftServer server, TransmissionData data, CharterId charter, Identifier transmission) {
		Transmission fired = TransmissionCatalog.require(transmission);
		Charter found = requireCharter(server, charter);
		if (!data.fire(charter, fired)) {
			return;
		}
		// The bonus follows the fired set's answer, which is "once" even across a restart, so it is paid only here.
		fired.bonus().ifPresent(bonus -> {
			if (Charters.deposit(server, charter, bonus.amount()).isPresent()) {
				DeepCharter.LOGGER.warn("Bonus {} of {} could not be credited to charter {}: its account refused the deposit",
						bonus, transmission, found.name());
			}
		});
		deliver(server, data, found, Optional.empty());
	}

	/**
	 * Gives {@code charter}, which has just been founded, the repair transmissions that have fired in the world, in the order they
	 * first fired, and sends them to its online members. They pay no bonus: the bonus was for the charter that did the work.
	 */
	public static void replayTo(MinecraftServer server, TransmissionData data, CharterId charter) {
		Charter found = requireCharter(server, charter);
		if (!data.replayTo(charter).isEmpty()) {
			deliver(server, data, found, Optional.empty());
		}
	}

	/** Sends the queue of {@code charter} to its online members, in order, and empties it. Nothing is sent, and the queue kept, if nobody is online. */
	public static void deliver(MinecraftServer server, TransmissionData data, CharterId charter) {
		deliver(server, data, requireCharter(server, charter), Optional.empty());
	}

	/**
	 * Sends a player who has just logged in the queue of their charter. The player is passed in because the server's lookup by UUID
	 * does not find a player in their own join event.
	 */
	static void deliverOnLogin(MinecraftServer server, ServerPlayer player) {
		Charters.charterOf(server, player.getUUID()).ifPresent(charter -> deliver(server, TransmissionData.get(server), charter, Optional.of(player)));
	}

	private static void deliver(MinecraftServer server, TransmissionData data, Charter charter, Optional<ServerPlayer> joining) {
		if (data.progress(charter.id()).queue().isEmpty()) {
			return;
		}
		List<ServerPlayer> online = onlineMembers(server, charter, joining);
		if (online.isEmpty()) {
			return;
		}
		String director = directorName(server, charter, online);
		for (Identifier id : data.takeQueue(charter.id())) {
			Transmission transmission = TransmissionCatalog.require(id);
			for (ServerPlayer player : online) {
				if (ServerPlayNetworking.canSend(player, TransmissionPayload.TYPE)) {
					ServerPlayNetworking.send(player, new TransmissionPayload(id, charter.name(), director));
				}
				TransmissionEvents.DELIVERED.invoker().onDelivered(server, charter, player, transmission);
			}
		}
	}

	private static List<ServerPlayer> onlineMembers(MinecraftServer server, Charter charter, Optional<ServerPlayer> joining) {
		List<ServerPlayer> online = new ArrayList<>();
		for (UUID member : charter.roster()) {
			ServerPlayer player = joining.filter(candidate -> candidate.getUUID().equals(member)).orElseGet(() -> server.getPlayerList().getPlayer(member));
			if (player != null) {
				online.add(player);
			}
		}
		return online;
	}

	/** The Director's name for {@code [DIRECTOR]}: from an online member, else the server's name cache, else a plain stand-in. */
	private static String directorName(MinecraftServer server, Charter charter, List<ServerPlayer> online) {
		Optional<UUID> director = charter.director();
		if (director.isEmpty()) {
			return UNKNOWN_DIRECTOR;
		}
		return online.stream().filter(player -> player.getUUID().equals(director.get())).findFirst()
				.map(player -> player.getGameProfile().name())
				.or(() -> server.services().nameToIdCache().get(director.get()).map(NameAndId::name))
				.orElse(UNKNOWN_DIRECTOR);
	}

	private static Charter requireCharter(MinecraftServer server, CharterId charter) {
		return Charters.find(server, charter).orElseThrow(() -> new IllegalArgumentException("No charter " + charter.value()));
	}
}
