package io.github.pkeppeler.deepcharter.handbook;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterEvents;
import io.github.pkeppeler.deepcharter.charter.Charters;

/**
 * Notes: pages found in the world. A Note a player finds belongs to their whole charter: every member's handbook lists it, and it
 * is unread for each member until that member opens it ({@link ReadMarks}, which belongs to the player). A new member of a charter
 * sees the charter's Notes, unread unless they read them before. A player on no charter files nothing: a Note needs a charter's
 * handbook to go into ({@link FindResult#NO_CHARTER}), and it is not remembered for a charter the player founds later.
 *
 * <p>The set of Notes is fixed in code, N01 to N11 for M2 (docs/lore/notes.md). A Note's id is {@code deepcharter:note/n<NN>}, and
 * its title and text are the language keys {@link #titleKey} and {@link #textKey}, so the server needs no table that the client has
 * to be sent. Add the later Notes by raising {@link #SHIPPED} and adding their keys.
 *
 * <p>Everything on a join, sync or gameplay path reads through {@link #readableData}: when the saved charters or the saved Notes are
 * of a version this build cannot read, it logs once and does nothing, and {@link #foundFor} is empty.
 */
public final class Notes {
	/** The highest Note number a Note block can name: N01 to N29. */
	public static final int MAX_NUMBER = 29;
	/** How many Notes have text: N01 to this number. */
	private static final int SHIPPED = 11;
	private static final String PATH_PREFIX = "note/n";
	private static final List<Identifier> ALL = IntStream.rangeClosed(1, SHIPPED).mapToObj(Notes::id).toList();

	private Notes() {
	}

	/** The id of Note number {@code number}: 7 is {@code deepcharter:note/n07}. */
	public static Identifier id(int number) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, String.format("%s%02d", PATH_PREFIX, number));
	}

	/** The number of a Note that {@link #exists}: {@code deepcharter:note/n07} is 7. */
	public static int number(Identifier note) {
		return Integer.parseInt(note.getPath().substring(PATH_PREFIX.length()));
	}

	/** Every Note that has text, in Note order. */
	public static List<Identifier> all() {
		return ALL;
	}

	public static boolean exists(Identifier note) {
		return ALL.contains(note);
	}

	public static String titleKey(Identifier note) {
		return key(note, "title");
	}

	public static String textKey(Identifier note) {
		return key(note, "text");
	}

	private static String key(Identifier note, String part) {
		return "deepcharter.handbook.note.n" + String.format("%02d", number(note)) + "." + part;
	}

	/** Syncs the Notes to clients: on join, and when a player founds, joins or leaves a charter. */
	static void register() {
		NotesSyncPayload.register();
		ServerPlayerEvents.JOIN.register(player -> NotesSyncPayload.send(player.level().getServer(), player));
		CharterEvents.FOUNDED.register((server, charter) -> sendAll(server, charter.roster()));
		CharterEvents.JOINED.register((server, charter, player) -> sendAll(server, List.of(player)));
		CharterEvents.LEFT.register((server, charter, player) -> sendAll(server, List.of(player)));
	}

	/**
	 * Files {@code note} in the handbook of every member of {@code finder}'s charter, unread for each, and tells their clients. Does
	 * nothing, and says why in the result, when the Note does not exist, the player is on no charter, the charter already has the Note,
	 * or the saved charters or Notes are unreadable.
	 */
	public static FindResult find(ServerPlayer finder, Identifier note) {
		MinecraftServer server = finder.level().getServer();
		if (!exists(note)) {
			return FindResult.UNKNOWN;
		}
		Optional<NotesData> data = readableData(server);
		if (data.isEmpty()) {
			return FindResult.UNREADABLE;
		}
		Optional<Charter> charter = Charters.charterOf(server, finder.getUUID());
		if (charter.isEmpty()) {
			return FindResult.NO_CHARTER;
		}
		if (!data.get().add(charter.get().id(), note)) {
			return FindResult.ALREADY_FOUND;
		}
		sendAll(server, charter.get().roster());
		return FindResult.FOUND;
	}

	/**
	 * The Notes the charter of {@code player} has found, in Note order: empty when they are on no charter. Empty, and logged once,
	 * while the saved charters or Notes are unreadable.
	 */
	public static List<Identifier> foundFor(MinecraftServer server, UUID player) {
		Optional<NotesData> data = readableData(server);
		if (data.isEmpty()) {
			return List.of();
		}
		Set<Identifier> found = Charters.charterOf(server, player).map(charter -> data.get().found(charter.id())).orElse(Set.of());
		return ALL.stream().filter(found::contains).toList();
	}

	/** The Notes data, or empty (logged once for each saved-data object) when the saved charters or the saved Notes are unreadable. */
	private static Optional<NotesData> readableData(MinecraftServer server) {
		if (!HandbookProgress.chartersReadable(server)) {
			return Optional.empty();
		}
		NotesData data = NotesData.get(server);
		if (!data.isReadable()) {
			HandbookProgress.reportOnce(data, "the saved handbook notes have a version this build cannot read, so the handbook skips them and keeps them unchanged");
			return Optional.empty();
		}
		return Optional.of(data);
	}

	private static void sendAll(MinecraftServer server, Iterable<UUID> players) {
		for (UUID id : players) {
			ServerPlayer online = server.getPlayerList().getPlayer(id);
			if (online != null) {
				NotesSyncPayload.send(server, online);
			}
		}
	}
}
