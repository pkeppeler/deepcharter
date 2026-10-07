package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.handbook.ClientNotes;
import io.github.pkeppeler.deepcharter.client.handbook.ClientReadMarks;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookNote;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPage;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.handbook.NoteBlock;
import io.github.pkeppeler.deepcharter.handbook.Notes;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;

/**
 * Client GameTest for #78. A screen driven by hand first: the Notes tab lists the notes, marks the unread ones, and opening one
 * reports it once. Then the real game: a charter finds two Notes through the block, the Notes tab lists them, opening one marks it
 * read on the server and for this player only, and the list tells the read one from the unread one.
 */
public class NotesClientTest implements FabricClientGameTest {
	private static final Identifier FIRST = Notes.id(1);
	private static final Identifier TENTH = Notes.id(10);
	private static final int LEFT_MOUSE = 1;
	/** A window so small that the sheet is at its minimum size. */
	private static final int MIN_WINDOW_WIDTH = 200;
	private static final int MIN_WINDOW_HEIGHT = 100;
	private static final int WINDOW_WIDTH = 427;
	private static final int WINDOW_HEIGHT = 240;

	@Override
	public void runTest(ClientGameTestContext context) {
		theListMarksUnreadNotesAndOpeningOneReportsItOnce(context);
		everyNoteOfTheSliceHasTextAndFitsTheListAtMinimumSize(context);
		inTheRealGame(context);
	}

	private static List<HandbookNote> notes(int count) {
		List<HandbookNote> notes = new ArrayList<>();
		for (int number = 1; number <= count; number++) {
			Identifier id = Notes.id(number);
			notes.add(new HandbookNote(id, number, Component.literal("PLACEHOLDER: title " + number), Component.literal("PLACEHOLDER: text " + number)));
		}
		return notes;
	}

	private static List<HandbookPage> noPages() {
		return List.of(new HandbookPage.Cover());
	}

	private static MouseButtonEvent clickOn(HandbookScreen.NoteRow row) {
		return new MouseButtonEvent((row.left() + row.right()) / 2.0, (row.top() + row.bottom()) / 2.0, new MouseButtonInfo(LEFT_MOUSE, 0));
	}

	private static void theListMarksUnreadNotesAndOpeningOneReportsItOnce(ClientGameTestContext context) {
		context.runOnClient(client -> {
			List<Identifier> viewed = new ArrayList<>();
			Set<Identifier> read = Set.of(FIRST);
			HandbookScreen screen = new HandbookScreen(noPages(), read::contains, viewed::add, notes(3));
			screen.init(WINDOW_WIDTH, WINDOW_HEIGHT);
			screen.showNotes();
			check(!screen.notesEmpty(), "with notes the Notes tab is not empty");
			check(screen.openNote() == null, "the Notes tab opens on the list");
			check(!screen.noteUnread(FIRST) && screen.noteUnread(Notes.id(2)), "a read note and an unread note are told apart");
			List<HandbookScreen.NoteRow> rows = screen.noteRows();
			check(rows.size() == 3, "the list has a row for each note, got " + rows.size());

			check(screen.mouseClicked(clickOn(rows.get(1)), false), "a row is a click target");
			check(Notes.id(2).equals(screen.openNote()), "clicking a row opens that note, opened " + screen.openNote());
			check(viewed.equals(List.of(Notes.id(2))), "opening an unread note reports it, got " + viewed);
			screen.closeNote();
			check(screen.openNote() == null, "back returns to the list");
			screen.openNote(Notes.id(2));
			check(viewed.size() == 1, "opening a note again reports nothing more, got " + viewed);
			screen.openNote(FIRST);
			check(viewed.size() == 1, "opening a note that is read reports nothing, got " + viewed);
			screen.openNote(Notes.id(9));
			check(FIRST.equals(screen.openNote()), "a note the tab does not list cannot be opened");
			check(viewed.size() == 1, "an unlisted note is never reported, got " + viewed);

			HandbookScreen empty = new HandbookScreen(noPages(), id -> false, id -> { }, List.of());
			empty.init(WINDOW_WIDTH, WINDOW_HEIGHT);
			empty.showNotes();
			check(empty.notesEmpty() && empty.noteRows().isEmpty(), "with no notes the tab shows its empty state");
		});
	}

	/** Every shipped note has an entry for its row and fits when opened, and a long list scrolls at the smallest sheet. */
	private static void everyNoteOfTheSliceHasTextAndFitsTheListAtMinimumSize(ClientGameTestContext context) {
		context.runOnClient(client -> {
			List<HandbookNote> shipped = Notes.all().stream().map(ClientNotes::entry).toList();
			HandbookScreen screen = new HandbookScreen(noPages(), id -> false, id -> { }, shipped);
			screen.init(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT);
			screen.showNotes();
			List<HandbookScreen.NoteRow> rows = screen.noteRows();
			check(!rows.isEmpty() && rows.size() < shipped.size(), "at the smallest size part of the list shows, got " + rows.size() + " of " + shipped.size());
			int firstTop = rows.getFirst().top();
			check(screen.mouseScrolled(0, 0, 0, -1), "the list scrolls");
			check(screen.noteRows().getFirst().top() == firstTop && !screen.noteRows().getFirst().note().equals(rows.getFirst().note()),
					"scrolling moves the next note into the first row");
			for (HandbookNote note : shipped) {
				screen.openNote(note.id());
				check(note.id().equals(screen.openNote()), "note " + note.number() + " opens");
			}
		});
	}

	private static void inTheRealGame(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.runOnClient(client -> check(ClientNotes.found().isEmpty(), "a new world has no notes"));
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				check(Charters.found(server, player.getUUID(), "Notes Test Co").isEmpty(), "the player founds a charter");
				useNoteBlock(player, 1);
				useNoteBlock(player, 10);
			});
			context.waitFor(client -> ClientNotes.found().equals(List.of(FIRST, TENTH)));
			check(context.computeOnClient(client -> !ClientReadMarks.isRead(FIRST) && !ClientReadMarks.isRead(TENTH)), "the found notes are unread");

			context.runOnClient(client -> HandbookScreen.open(client));
			context.waitForScreen(HandbookScreen.class);
			HandbookScreen screen = context.computeOnClient(client -> (HandbookScreen) client.gui.screen());
			context.runOnClient(client -> screen.showNotes());
			context.waitTicks(2);
			check(context.computeOnClient(client -> screen.noteRows().size() == 2 && screen.noteUnread(FIRST) && screen.noteUnread(TENTH)),
					"the Notes tab lists the two found notes, both unread");

			context.runOnClient(client -> screen.mouseClicked(clickOn(screen.noteRows().getFirst()), false));
			context.waitTicks(2);
			context.takeScreenshot("notes-open");
			check(context.computeOnClient(client -> FIRST.equals(screen.openNote())), "clicking the first row opens the first note");
			context.waitFor(client -> ClientReadMarks.isRead(FIRST));
			check(singleplayer.getServer().computeOnServer(server -> ReadMarks.isRead(server.getPlayerList().getPlayers().getFirst(), FIRST)),
					"the server holds the read mark");
			check(singleplayer.getServer().computeOnServer(server -> !ReadMarks.isRead(server.getPlayerList().getPlayers().getFirst(), TENTH)),
					"the other note stays unread");

			context.runOnClient(client -> screen.closeNote());
			context.waitTicks(2);
			check(context.computeOnClient(client -> !screen.noteUnread(FIRST) && screen.noteUnread(TENTH)), "the list tells the read note from the unread one");
			context.takeScreenshot("notes-tab");
			context.setScreen(() -> null);
		}
	}

	/** Puts a Note block next to the player and uses it, as the game does when a player right-clicks it. */
	private static void useNoteBlock(ServerPlayer player, int number) {
		BlockPos pos = player.blockPosition().relative(Direction.NORTH, 2);
		player.level().setBlock(pos, NoteBlock.stateOf(number), 3);
		player.level().getBlockState(pos).useWithoutItem(player.level(), player, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
