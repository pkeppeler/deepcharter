package io.github.pkeppeler.deepcharter.transmission;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;

/**
 * Fires a transmission for a charter. Any feature calls this when the event a transmission waits
 * for happens; it needs no transmission code to exist yet.
 *
 * <p>A fired transmission joins the charter's fired set, and pays its bonus, once. Each member is sent the fired set in order, from
 * their own place in it: at once when the transmission fires if they are online, otherwise on their next login. A member who joins later
 * starts at the beginning and is sent the charter's story so far. A charter founded after a repair transmission first fired has it in
 * its fired set from the start, with no bonus.
 *
 * <p>Nothing here throws because of saved data or a delivery: unreadable data does nothing and is logged once, a saved id that the data
 * file lacks is skipped and logged once, and a failed send or a throwing listener is logged and the member is not moved past it. Only a
 * bad argument (an unknown charter or transmission) throws, because that is the caller's bug.
 *
 * <p>Call everything on the server thread. The overloads with a {@link TransmissionData} take the data to use instead of the
 * world's, so a test can run on state of its own.
 */
public final class Transmissions {
	private static final String UNKNOWN_DIRECTOR = "the Director";

	/** The running server, for {@link #fire(CharterId, Identifier)}, whose frozen signature has no server. Set while one is running. */
	private static MinecraftServer running;
	private static final Set<Identifier> REPORTED_MISSING = new HashSet<>();

	private Transmissions() {
	}

	static void started(MinecraftServer server) {
		running = server;
		REPORTED_MISSING.clear();
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
		if (!data.isReadable()) {
			return;
		}
		payPending(server, data, charter);
		if (!data.fire(charter, fired)) {
			return;
		}
		// The bonus follows the fired set's answer, which is "once" even across a restart, so it is credited only here.
		fired.bonus().ifPresent(bonus -> credit(server, data, charter, bonus));
		deliver(server, data, found);
	}

	/**
	 * Gives {@code charter}, which has just been founded, the repair transmissions that have fired in the world, in the order they
	 * first fired, and sends them to its online members. They pay no bonus: the bonus was for the charter that did the work.
	 */
	public static void replayTo(MinecraftServer server, TransmissionData data, CharterId charter) {
		Charter found = requireCharter(server, charter);
		if (data.isReadable() && !data.replayTo(charter).isEmpty()) {
			deliver(server, data, found);
		}
	}

	/** Sends every online member of {@code charter} what they have not been sent, in order. */
	public static void deliver(MinecraftServer server, TransmissionData data, CharterId charter) {
		if (data.isReadable()) {
			deliver(server, data, requireCharter(server, charter));
		}
	}

	/**
	 * Sends {@code player} what they have not been sent of the charter's story, in order. Call it when the player logs in, passing the
	 * player in: the server's lookup by UUID does not find a player in their own join event.
	 */
	public static void deliverTo(MinecraftServer server, TransmissionData data, CharterId charter, ServerPlayer player) {
		if (data.isReadable()) {
			sendUnsent(server, data, requireCharter(server, charter), player);
		}
	}

	/** Called when a player logs in: pays what is pending for their charter, and sends them what they missed. */
	public static void deliverOnLogin(MinecraftServer server, ServerPlayer player) {
		TransmissionData data = TransmissionData.get(server);
		if (!data.isReadable() || !Charters.isReadable(server)) {
			return;
		}
		Charters.charterOf(server, player.getUUID()).ifPresent(charter -> {
			payPending(server, data, charter.id());
			sendUnsent(server, data, charter, player);
		});
	}

	/** Tries again the bonuses of {@code charter} that its account refused. Each is credited once and removed; a full account keeps them. */
	public static void payPending(MinecraftServer server, TransmissionData data, CharterId charter) {
		if (!data.isReadable()) {
			return;
		}
		List<Transmission.Bonus> pending = data.progress(charter).pending();
		for (Transmission.Bonus bonus : pending) {
			Optional<CharterRefusal> refusal = Charters.deposit(server, charter, bonus.amount());
			if (refusal.isPresent()) {
				if (refusal.get() != CharterRefusal.ACCOUNT_FULL) {
					DeepCharter.LOGGER.error("Pending bonus {} of charter {} was refused: {}", bonus, charter.value(), refusal.get());
				}
				return;
			}
			data.removeFirstPending(charter);
		}
	}

	/** {@code member} has left {@code charter}: forget how far they were, so that a return starts at the beginning. */
	public static void forget(MinecraftServer server, TransmissionData data, CharterId charter, UUID member) {
		if (data.isReadable()) {
			data.forget(charter, member);
		}
	}

	private static void credit(MinecraftServer server, TransmissionData data, CharterId charter, Transmission.Bonus bonus) {
		Optional<CharterRefusal> refusal = Charters.deposit(server, charter, bonus.amount());
		if (refusal.isEmpty()) {
			return;
		}
		if (refusal.get() != CharterRefusal.ACCOUNT_FULL) {
			DeepCharter.LOGGER.error("Bonus {} of charter {} was refused: {}", bonus, charter.value(), refusal.get());
			return;
		}
		// Not lost: kept, and tried again on the next fire, login or zone poll.
		data.addPending(charter, bonus);
	}

	private static void deliver(MinecraftServer server, TransmissionData data, Charter charter) {
		for (UUID member : charter.roster()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member);
			if (player != null) {
				sendUnsent(server, data, charter, player);
			}
		}
	}

	/**
	 * Sends {@code player} each transmission after their place, in order. The place moves past a transmission only once it is sent, so a
	 * failure sends it again later and loses nothing; a listener that throws is logged and does not stop the rest.
	 */
	private static void sendUnsent(MinecraftServer server, TransmissionData data, Charter charter, ServerPlayer player) {
		TransmissionData.Progress progress = data.progress(charter.id());
		List<Identifier> unsent = progress.unsent(player.getUUID());
		if (unsent.isEmpty()) {
			return;
		}
		int sent = progress.fired().size() - unsent.size();
		String director = directorName(server, charter, player);
		for (Identifier id : unsent) {
			Optional<Transmission> transmission = TransmissionCatalog.find(id);
			if (transmission.isEmpty()) {
				// A transmission removed from the data file: skip it for good, saying so once.
				if (REPORTED_MISSING.add(id)) {
					DeepCharter.LOGGER.error("Transmission {} is saved for charter {} but the data file no longer lists it: skipped", id, charter.name());
				}
			} else {
				try {
					if (ServerPlayNetworking.canSend(player, TransmissionPayload.TYPE)) {
						ServerPlayNetworking.send(player, new TransmissionPayload(id, charter.name(), director));
					}
				} catch (RuntimeException e) {
					DeepCharter.LOGGER.error("Could not send transmission {} to {}: it is sent again on their next login", id, player.getGameProfile().name(), e);
					return;
				}
			}
			sent++;
			data.markSent(charter.id(), player.getUUID(), sent);
			transmission.ifPresent(found -> notifyDelivered(server, charter, player, found));
		}
	}

	private static void notifyDelivered(MinecraftServer server, Charter charter, ServerPlayer player, Transmission transmission) {
		try {
			TransmissionEvents.DELIVERED.invoker().onDelivered(server, charter, player, transmission);
		} catch (RuntimeException e) {
			DeepCharter.LOGGER.error("A listener of the delivery of {} to {} threw", transmission.id(), player.getGameProfile().name(), e);
		}
	}

	/** The Director's name for {@code [DIRECTOR]}: from the player or an online member, else the server's name cache, else a plain stand-in. */
	private static String directorName(MinecraftServer server, Charter charter, ServerPlayer recipient) {
		Optional<UUID> director = charter.director();
		if (director.isEmpty()) {
			return UNKNOWN_DIRECTOR;
		}
		ServerPlayer online = recipient.getUUID().equals(director.get()) ? recipient : server.getPlayerList().getPlayer(director.get());
		if (online != null) {
			return online.getGameProfile().name();
		}
		return server.services().nameToIdCache().get(director.get()).map(NameAndId::name).orElse(UNKNOWN_DIRECTOR);
	}

	private static Charter requireCharter(MinecraftServer server, CharterId charter) {
		return Charters.find(server, charter).orElseThrow(() -> new IllegalArgumentException("No charter " + charter.value()));
	}
}
