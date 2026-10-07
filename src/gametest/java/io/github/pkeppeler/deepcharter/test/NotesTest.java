package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.handbook.FindResult;
import io.github.pkeppeler.deepcharter.handbook.HandbookReadPayload;
import io.github.pkeppeler.deepcharter.handbook.NoteBlock;
import io.github.pkeppeler.deepcharter.handbook.Notes;
import io.github.pkeppeler.deepcharter.handbook.NotesData;
import io.github.pkeppeler.deepcharter.handbook.NotesSyncPayload;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;
import io.github.pkeppeler.deepcharter.test.support.LogCapture;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/**
 * Server GameTests for #78: using a Note block files the Note for the whole charter, unread for each member; reading marks it for
 * the reader alone; a repeat adds nothing; a player on no charter files nothing; and the saved format is versioned.
 */
public class NotesTest {
	private static final Identifier FIRST = Notes.id(1);
	private static final Identifier SECOND = Notes.id(2);
	private static final BlockPos BLOCK = new BlockPos(1, 1, 1);

	private static String uniqueName() {
		return "Notes " + UUID.randomUUID().toString().substring(0, 8);
	}

	private static void expectDone(GameTestHelper helper, Optional<CharterRefusal> refusal, String what) {
		if (refusal.isPresent()) {
			throw helper.assertionException("%s should succeed, was refused: %s", what, refusal.get());
		}
	}

	/** A charter with {@code director} as Director and {@code crew} as its crew. */
	private static CharterId found(GameTestHelper helper, MinecraftServer server, ServerPlayer director, ServerPlayer... crew) {
		expectDone(helper, Charters.found(server, director.getUUID(), uniqueName()), "founding");
		Charter charter = Charters.charterOf(server, director.getUUID()).orElseThrow();
		for (ServerPlayer member : crew) {
			join(helper, server, charter.id(), director, member);
		}
		return charter.id();
	}

	private static void join(GameTestHelper helper, MinecraftServer server, CharterId id, ServerPlayer director, ServerPlayer member) {
		expectDone(helper, Charters.apply(server, member.getUUID(), id), "applying");
		expectDone(helper, Charters.approve(server, director.getUUID(), member.getUUID()), "approving");
	}

	private static List<Identifier> notesOf(MinecraftServer server, ServerPlayer player) {
		return Notes.foundFor(server, player.getUUID());
	}

	/** Uses the Note block of {@code number} as {@code player} does: through the block, as the game calls it. */
	private static void use(GameTestHelper helper, ServerPlayer player, int number) {
		BlockPos pos = helper.absolutePos(BLOCK);
		helper.getLevel().setBlock(pos, NoteBlock.stateOf(number), 3);
		BlockState state = helper.getLevel().getBlockState(pos);
		state.useWithoutItem(helper.getLevel(), player, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
	}

	private static void expectNotes(GameTestHelper helper, MinecraftServer server, ServerPlayer player, List<Identifier> expected) {
		List<Identifier> actual = notesOf(server, player);
		if (!actual.equals(expected)) {
			throw helper.assertionException("%s should have the notes %s, has %s", player.getGameProfile().name(), expected, actual);
		}
	}

	private static void expectRead(GameTestHelper helper, ServerPlayer player, Identifier note, boolean expected) {
		if (ReadMarks.isRead(player, note) != expected) {
			throw helper.assertionException("%s should be %s for %s", note, expected ? "read" : "unread", player.getGameProfile().name());
		}
	}

	@GameTest
	public void usingANoteBlockFilesTheNoteForEveryMemberUnread(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer director = MockPlayers.join(helper, "Director").player();
		ServerPlayer crew = MockPlayers.join(helper, "Crew").player();
		ServerPlayer outsider = MockPlayers.join(helper, "Outsider").player();
		found(helper, server, director, crew);
		found(helper, server, outsider);

		use(helper, crew, 1);

		expectNotes(helper, server, director, List.of(FIRST));
		expectNotes(helper, server, crew, List.of(FIRST));
		expectNotes(helper, server, outsider, List.of());
		expectRead(helper, director, FIRST, false);
		expectRead(helper, crew, FIRST, false);
		helper.succeed();
	}

	@GameTest
	public void readingMarksTheNoteReadForTheReaderOnly(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer director = MockPlayers.join(helper, "Director").player();
		ServerPlayer crew = MockPlayers.join(helper, "Crew").player();
		found(helper, server, director, crew);
		use(helper, director, 1);

		if (!HandbookReadPayload.handle(server, crew, FIRST)) {
			throw helper.assertionException("a member may read a Note their charter has found");
		}

		expectRead(helper, crew, FIRST, true);
		expectRead(helper, director, FIRST, false);
		helper.succeed();
	}

	@GameTest
	public void usingAFoundNoteAgainAddsNoDuplicateAndKeepsReadMarks(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer director = MockPlayers.join(helper, "Director").player();
		ServerPlayer crew = MockPlayers.join(helper, "Crew").player();
		found(helper, server, director, crew);
		use(helper, director, 1);
		HandbookReadPayload.handle(server, crew, FIRST);

		use(helper, crew, 1);
		use(helper, director, 1);
		FindResult again = Notes.find(director, FIRST);

		expectNotes(helper, server, director, List.of(FIRST));
		expectNotes(helper, server, crew, List.of(FIRST));
		if (again != FindResult.ALREADY_FOUND) {
			throw helper.assertionException("finding a found Note again is ALREADY_FOUND, was %s", again);
		}
		expectRead(helper, crew, FIRST, true);
		expectRead(helper, director, FIRST, false);
		helper.succeed();
	}

	@GameTest
	public void aPlayerOnNoCharterFilesNothing(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer loner = MockPlayers.join(helper, "Loner").player();

		FindResult result = Notes.find(loner, FIRST);
		use(helper, loner, 1);

		if (result != FindResult.NO_CHARTER) {
			throw helper.assertionException("a player on no charter gets NO_CHARTER, was %s", result);
		}
		expectNotes(helper, server, loner, List.of());
		// Founding a charter afterwards does not bring the Note back: it was never found.
		found(helper, server, loner);
		expectNotes(helper, server, loner, List.of());
		helper.succeed();
	}

	@GameTest
	public void newCrewSeeTheCharterNotesUnreadAndALeaverLosesThem(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer director = MockPlayers.join(helper, "Director").player();
		ServerPlayer newcomer = MockPlayers.join(helper, "Newcomer").player();
		CharterId id = found(helper, server, director);
		use(helper, director, 1);
		use(helper, director, 2);
		expectNotes(helper, server, newcomer, List.of());

		join(helper, server, id, director, newcomer);

		expectNotes(helper, server, newcomer, List.of(FIRST, SECOND));
		expectRead(helper, newcomer, FIRST, false);
		expectRead(helper, newcomer, SECOND, false);

		expectDone(helper, Charters.leave(server, newcomer.getUUID()), "leaving");
		expectNotes(helper, server, newcomer, List.of());
		expectNotes(helper, server, director, List.of(FIRST, SECOND));
		helper.succeed();
	}

	@GameTest
	public void aNoteIsListedInNoteOrderWhateverOrderItWasFound(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer director = MockPlayers.join(helper, "Director").player();
		found(helper, server, director);

		use(helper, director, 2);
		use(helper, director, 1);

		expectNotes(helper, server, director, List.of(FIRST, SECOND));
		helper.succeed();
	}

	@GameTest
	public void aCharterKeepsItsNotesWhenItGoesDormant(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer director = MockPlayers.join(helper, "Director").player();
		CharterId id = found(helper, server, director);
		use(helper, director, 1);

		expectDone(helper, Charters.leave(server, director.getUUID()), "the last person leaving");

		if (!NotesData.get(server).found(id).equals(Set.of(FIRST))) {
			throw helper.assertionException("a dormant charter keeps its Notes");
		}
		helper.succeed();
	}

	@GameTest
	public void theServerRefusesToMarkANoteTheCharterHasNotFound(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer director = MockPlayers.join(helper, "Director").player();
		ServerPlayer other = MockPlayers.join(helper, "Other").player();
		ServerPlayer loner = MockPlayers.join(helper, "Loner").player();
		found(helper, server, director);
		found(helper, server, other);
		use(helper, other, 1);

		boolean notFound = HandbookReadPayload.handle(server, director, FIRST);
		boolean noSuchNote = HandbookReadPayload.handle(server, other, Notes.id(Notes.MAX_NUMBER));
		boolean noCharter = HandbookReadPayload.handle(server, loner, FIRST);

		if (notFound || noSuchNote || noCharter) {
			throw helper.assertionException("only a Note one's own charter found may be marked: notFound=%s noSuchNote=%s noCharter=%s",
					notFound, noSuchNote, noCharter);
		}
		expectRead(helper, director, FIRST, false);
		expectRead(helper, other, Notes.id(Notes.MAX_NUMBER), false);
		expectRead(helper, loner, FIRST, false);
		helper.succeed();
	}

	@GameTest
	public void aNoteWithNoTextYetFilesNothing(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer director = MockPlayers.join(helper, "Director").player();
		found(helper, server, director);

		use(helper, director, Notes.MAX_NUMBER);
		FindResult result = Notes.find(director, Identifier.fromNamespaceAndPath("deepcharter", "note/n99"));

		if (result != FindResult.UNKNOWN) {
			throw helper.assertionException("a Note that does not exist is UNKNOWN, was %s", result);
		}
		expectNotes(helper, server, director, List.of());
		helper.succeed();
	}

	@GameTest
	public void everyShippedNoteHasATitleAndATextInTheLanguageFile(GameTestHelper helper) {
		List<Identifier> all = Notes.all();
		if (all.size() != 11) {
			throw helper.assertionException("N01 to N11 ship in M2, found %s notes", all.size());
		}
		for (Identifier note : all) {
			String title = Notes.titleKey(note);
			String text = Notes.textKey(note);
			if (!Language.getInstance().has(title) || !Language.getInstance().has(text)) {
				throw helper.assertionException("%s needs the lang keys %s and %s", note, title, text);
			}
		}
		helper.succeed();
	}

	@GameTest
	public void theSyncListsTheNotesOfTheReceiversCharterOnly(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer director = MockPlayers.join(helper, "Director").player();
		ServerPlayer outsider = MockPlayers.join(helper, "Outsider").player();
		found(helper, server, director);
		use(helper, director, 2);
		use(helper, director, 1);

		if (!NotesSyncPayload.of(server, director.getUUID()).found().equals(List.of(FIRST, SECOND))) {
			throw helper.assertionException("the charter's Notes go to a member in Note order");
		}
		if (!NotesSyncPayload.of(server, outsider.getUUID()).found().isEmpty()) {
			throw helper.assertionException("a player on no charter is sent no Notes");
		}
		helper.succeed();
	}

	private static CompoundTag futureVersion() {
		CompoundTag future = new CompoundTag();
		future.putInt("version", 99);
		future.putString("shape", "from a later build");
		return future;
	}

	@GameTest
	public void unreadableNotesNeverThrowFromAUseAJoinOrASync(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer player = MockPlayers.join(helper, "Reader").player();
		found(helper, server, player);
		CompoundTag future = futureVersion();
		NotesData unreadable = NotesData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
		NotesData original = NotesData.get(server);
		LogCapture log = LogCapture.start("saved handbook notes");
		// Everything runs inside this one tick, so no other test sees the swapped data.
		server.getDataStorage().set(NotesData.TYPE, unreadable);
		try {
			for (int round = 0; round < 3; round++) {
				FindResult result = Notes.find(player, FIRST);
				if (result != FindResult.UNREADABLE) {
					throw helper.assertionException("finding with unreadable notes is UNREADABLE, was %s", result);
				}
				NotesSyncPayload.send(server, player);
				if (!notesOf(server, player).isEmpty() || HandbookReadPayload.handle(server, player, FIRST)) {
					throw helper.assertionException("unreadable notes read as empty and mark nothing");
				}
			}
		} finally {
			server.getDataStorage().set(NotesData.TYPE, original);
		}
		List<String> errors = log.errors();
		if (errors.size() != 1) {
			throw helper.assertionException("unreadable notes are logged once, not %s times: %s", errors.size(), errors);
		}
		if (!future.equals(NotesData.CODEC.encodeStart(NbtOps.INSTANCE, unreadable).getOrThrow())) {
			throw helper.assertionException("unreadable notes must round-trip unchanged");
		}
		helper.succeed();
	}

	/**
	 * Guards the datafixer type of {@link NotesData#TYPE}: a file saved by an older Minecraft goes through the real fixer on load and
	 * must come out unchanged. Keep it green on every Minecraft bump.
	 */
	@GameTest
	public void aNotesFileFromAnOlderMinecraftLoadsUnchanged(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		int olderDataVersion = 4000;
		Path dir = tempDir();
		try {
			CharterId id = CharterId.random();
			try (SavedDataStorage first = storage(server, dir)) {
				first.computeIfAbsent(NotesData.TYPE).add(id, FIRST);
				first.saveAndJoin();
			}
			Path file = savedFile(dir);
			CompoundTag stamped = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
			if (NbtUtils.getDataVersion(stamped) <= olderDataVersion) {
				throw helper.assertionException("the test needs a saved DataVersion above %s, got %s", olderDataVersion, NbtUtils.getDataVersion(stamped));
			}
			Tag savedBody = stamped.get("data");
			NbtIo.writeCompressed(NbtUtils.addDataVersion(stamped, olderDataVersion), file);

			try (SavedDataStorage second = storage(server, dir)) {
				NotesData loaded = second.computeIfAbsent(NotesData.TYPE);
				Tag reloaded = NotesData.CODEC.encodeStart(NbtOps.INSTANCE, loaded).getOrThrow();
				if (!reloaded.equals(savedBody) || !loaded.found(id).equals(Set.of(FIRST))) {
					throw helper.assertionException("the fixer changed the notes data: saved %s, loaded %s", savedBody, reloaded);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			deleteTree(dir);
		}
		helper.succeed();
	}

	private static SavedDataStorage storage(MinecraftServer server, Path dir) {
		return new SavedDataStorage(dir, server.getFixerUpper(), server.registryAccess());
	}

	private static Path tempDir() {
		try {
			return Files.createTempDirectory("notes-saved-data");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Path savedFile(Path dir) throws IOException {
		try (Stream<Path> files = Files.walk(dir)) {
			return files.filter(path -> path.toString().endsWith(".dat")).findFirst().orElseThrow();
		}
	}

	private static void deleteTree(Path dir) {
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
