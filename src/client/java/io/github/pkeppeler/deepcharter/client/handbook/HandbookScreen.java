package io.github.pkeppeler.deepcharter.client.handbook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;
import io.github.pkeppeler.deepcharter.handbook.HandbookReadPayload;
import io.github.pkeppeler.deepcharter.handbook.HandbookVisibility;

/**
 * The Employee Handbook: a paper booklet, with a Handbook tab (cover, issue slip, Founder's letter, contents, the chapters and the
 * end page) and a Notes tab. All text is the vanilla italic font in coloured ink.
 *
 * <p>What each chapter shows is decided by {@link HandbookVisibility}, before the screen is made: this class only draws the pages
 * it is given. It reports a chapter the first time the player flips to it, if the chapter is fully visible and not yet read, and
 * the owner of the screen sends that to the server ({@link #open}). The Notes tab lists the Notes the charter has found, unread ones
 * marked; opening a Note shows its text and reports it the same way, once, if it is not read yet.
 */
public class HandbookScreen extends Screen {
	private static final HandbookScreenTuning T = HandbookScreenTuning.DEFAULT;
	private static final int OPAQUE = 0xFF000000;
	/** The sheet is wide enough for a margin column from this width on. */
	private static final int MARGIN_MIN_PAPER_WIDTH = 220;
	private static final int MARGIN_GAP = 4;
	private static final int TOP_MARGIN = 14;
	private static final int CONTENT_TOP = 14;
	private static final int PARAGRAPH_GAP = 5;

	/** One line of the Notes list: the box it fills, and the Note a click on it opens. */
	public record NoteRow(Identifier note, int left, int top, int right, int bottom) {
		boolean contains(double x, double y) {
			return x >= left && x < right && y >= top && y < bottom;
		}
	}

	/** One line of a contents page: the box it fills, and the page a click on it goes to. */
	public record ContentsEntry(int page, int left, int top, int right, int bottom) {
		boolean contains(double x, double y) {
			return x >= left && x < right && y >= top && y < bottom;
		}
	}

	private final List<HandbookPage> pages;
	/** The index in {@link #pages} of the first page of each chapter, by chapter number. */
	private final Map<Integer, Integer> chapterPages;
	/** The page of directives of each chapter, by chapter number. */
	private final Map<Integer, HandbookPage.Chapter> chapters;
	private final Predicate<Identifier> isRead;
	private final Consumer<Identifier> onViewed;
	private final List<HandbookNote> notes;
	private final Set<Identifier> reported = new HashSet<>();

	private int page;
	private int previousPage;
	private int flipTicks;
	private boolean notesTab;
	private Identifier openNote;
	private int noteScroll;

	private int paperLeft;
	private int paperTop;
	private int paperWidth;
	private int paperHeight;
	private int textLeft;
	private int textWidth;
	private int marginLeft;
	private int marginWidth;
	private PaperButton back;
	private PaperButton next;
	private PaperButton handbookTab;
	private PaperButton notesButton;
	private PaperButton noteBack;

	/**
	 * @param pages    the pages of the Handbook tab, from {@link HandbookPages#of}
	 * @param isRead   whether the player has already read an entry: a chapter or a Note
	 * @param onViewed told the id of a chapter or Note the player has just opened, once, if it is not read yet
	 * @param notes    what the Notes tab lists; empty shows its empty state
	 */
	public HandbookScreen(List<HandbookPage> pages, Predicate<Identifier> isRead, Consumer<Identifier> onViewed, List<HandbookNote> notes) {
		super(Component.translatable("deepcharter.handbook.screen.title"));
		if (pages.isEmpty()) {
			throw new IllegalArgumentException("the handbook needs at least one page");
		}
		this.pages = List.copyOf(pages);
		Map<Integer, Integer> firstPages = new HashMap<>();
		Map<Integer, HandbookPage.Chapter> byNumber = new HashMap<>();
		for (int index = 0; index < this.pages.size(); index++) {
			switch (this.pages.get(index)) {
				case HandbookPage.ChapterText text -> firstPages.putIfAbsent(text.number(), index);
				case HandbookPage.Chapter chapter -> {
					firstPages.putIfAbsent(chapter.number(), index);
					byNumber.put(chapter.number(), chapter);
				}
				default -> {
				}
			}
		}
		this.chapterPages = Map.copyOf(firstPages);
		this.chapters = Map.copyOf(byNumber);
		this.isRead = isRead;
		this.onViewed = onViewed;
		this.notes = List.copyOf(notes);
	}

	/** Opens the handbook with the chapters the server synced and the progress it last sent. Marks a viewed chapter read on the server. */
	public static void open(Minecraft client) {
		if (client.player == null || client.getConnection() == null) {
			return;
		}
		Map<Identifier, HandbookChapter> chapters = new LinkedHashMap<>();
		HandbookChapters.all(client.getConnection().registryAccess()).forEach(chapter -> chapters.put(chapter.key().identifier(), chapter.value()));
		client.gui.setScreen(new HandbookScreen(HandbookPages.of(chapters, ClientHandbook.completed()), ClientReadMarks::isRead,
				entry -> ClientPlayNetworking.send(new HandbookReadPayload(entry)), ClientNotes.entries()));
	}

	/**
	 * The lang key of the margin note of a chapter: {@code deepcharter.handbook.chapter.<namespace>.<path>.margin}, with each
	 * {@code /} of the path turned into a dot, so that two namespaces never share a note.
	 */
	public static String marginKey(Identifier chapter) {
		return chapterKey(chapter) + ".margin";
	}

	/** The lang key of page {@code part} (from 1) of the text of a chapter: the chapter's key, then {@code .text.<part>}. */
	public static String textKey(Identifier chapter, int part) {
		return chapterKey(chapter) + ".text." + part;
	}

	/** The lang key of the margin note beside page {@code part} of the text of a chapter. */
	public static String textMarginKey(Identifier chapter, int part) {
		return textKey(chapter, part) + ".margin";
	}

	/** The lang key of page {@code part} (from 1) of Appendix A. Its clauses are separated by a newline. */
	public static String contractKey(int part) {
		return "deepcharter.handbook.appendix.page." + part;
	}

	private static String chapterKey(Identifier chapter) {
		return "deepcharter.handbook.chapter." + chapter.getNamespace() + "." + chapter.getPath().replace('/', '.');
	}

	public int page() {
		return page;
	}

	public List<HandbookPage> pages() {
		return pages;
	}

	/** Flips to page {@code index}, stopping at the cover and the end page. */
	public void goTo(int index) {
		int target = Mth.clamp(index, 0, pages.size() - 1);
		if (target == page) {
			return;
		}
		previousPage = page;
		page = target;
		flipTicks = T.flipTicks();
		Identifier viewed = switch (pages.get(page)) {
			case HandbookPage.Chapter chapter when chapter.visibility() == HandbookVisibility.FULL -> chapter.id();
			case HandbookPage.ChapterText text -> text.id();
			default -> null;
		};
		if (viewed != null && !isRead.test(viewed) && reported.add(viewed)) {
			onViewed.accept(viewed);
		}
		updateButtons();
	}

	public boolean onNotesTab() {
		return notesTab;
	}

	public void showNotes() {
		notesTab = true;
		openNote = null;
		updateButtons();
	}

	public void showHandbook() {
		notesTab = false;
		updateButtons();
	}

	/** Whether the Notes tab shows its empty state. */
	public boolean notesEmpty() {
		return notes.isEmpty();
	}

	/** Whether the player has yet to read {@code note}, which the Notes list marks. */
	public boolean noteUnread(Identifier note) {
		return !isRead.test(note);
	}

	/** The Note being read on the Notes tab, or null while the tab shows the list. */
	public Identifier openNote() {
		return openNote;
	}

	/** Opens {@code note} on the Notes tab. Reports it as viewed the first time, if it is not read yet. An id the tab does not list is ignored. */
	public void openNote(Identifier note) {
		if (notes.stream().noneMatch(entry -> entry.id().equals(note))) {
			return;
		}
		notesTab = true;
		openNote = note;
		if (noteUnread(note) && reported.add(note)) {
			onViewed.accept(note);
		}
		updateButtons();
	}

	/** Back from a Note to the list. */
	public void closeNote() {
		openNote = null;
		updateButtons();
	}

	/**
	 * The rows of the Notes list that fit on the sheet from the scroll position, top to bottom. Needs the screen to be initialised.
	 * The same boxes are drawn and are the click targets.
	 */
	public List<NoteRow> noteRows() {
		List<NoteRow> rows = new ArrayList<>();
		int y = notesTop();
		for (int index = noteScroll; index < notes.size() && y + font.lineHeight + 2 <= bottom(); index++) {
			rows.add(new NoteRow(notes.get(index).id(), textLeft, y - 1, textLeft + textWidth, y + font.lineHeight + 2));
			y += font.lineHeight + 4;
		}
		return rows;
	}

	private int notesTop() {
		return paperTop + TOP_MARGIN + Math.round(font.lineHeight * 1.25f) + 2 + PARAGRAPH_GAP;
	}

	private static String label(HandbookNote note) {
		return String.format("N%02d", note.number());
	}

	private HandbookNote note(Identifier id) {
		return notes.stream().filter(entry -> entry.id().equals(id)).findFirst().orElseThrow();
	}

	@Override
	protected void init() {
		paperWidth = Math.min(T.paperWidth(), width - 12);
		paperHeight = Mth.clamp(height - T.tabHeight() - 16, 100, T.paperMaxHeight());
		paperLeft = (width - paperWidth) / 2;
		paperTop = (height - paperHeight + T.tabHeight()) / 2;
		textLeft = paperLeft + T.bindingWidth() + T.padding();
		boolean hasMargin = paperWidth >= MARGIN_MIN_PAPER_WIDTH;
		marginWidth = hasMargin ? T.marginWidth() : 0;
		int textRight = paperLeft + paperWidth - T.padding() - (hasMargin ? marginWidth + MARGIN_GAP : 0);
		textWidth = textRight - textLeft;
		marginLeft = textRight + MARGIN_GAP;

		int tabY = paperTop - T.tabHeight() + 1;
		int tabX = paperLeft + T.bindingWidth() + 4;
		handbookTab = addRenderableWidget(new PaperButton(tabX, tabY, T.tabWidth(), T.tabHeight(),
				Component.translatable("deepcharter.handbook.screen.tab.handbook"), button -> showHandbook()));
		notesButton = addRenderableWidget(new PaperButton(tabX + T.tabWidth() + 2, tabY, T.tabWidth(), T.tabHeight(),
				Component.translatable("deepcharter.handbook.screen.tab.notes"), button -> showNotes()));
		int buttonY = paperTop + paperHeight - T.buttonHeight() - 4;
		noteBack = addRenderableWidget(new PaperButton(textLeft, buttonY, T.buttonWidth(), T.buttonHeight(),
				Component.translatable("deepcharter.handbook.notes.back"), button -> closeNote()));
		back = addRenderableWidget(new PaperButton(textLeft, buttonY, T.buttonWidth(), T.buttonHeight(),
				Component.translatable("deepcharter.handbook.screen.back"), button -> goTo(page - 1)));
		next = addRenderableWidget(new PaperButton(paperLeft + paperWidth - T.padding() - T.buttonWidth(), buttonY, T.buttonWidth(), T.buttonHeight(),
				Component.translatable("deepcharter.handbook.screen.next"), button -> goTo(page + 1)));
		updateButtons();
	}

	private void updateButtons() {
		if (back == null) {
			return;
		}
		back.visible = !notesTab;
		next.visible = !notesTab;
		back.active = !notesTab && page > 0;
		next.active = !notesTab && page < pages.size() - 1;
		noteBack.visible = notesTab && openNote != null;
		handbookTab.setSelected(!notesTab);
		notesButton.setSelected(notesTab);
	}

	@Override
	public void tick() {
		super.tick();
		if (flipTicks > 0) {
			flipTicks--;
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (HandbookKeys.OPEN.matches(event)) {
			onClose();
			return true;
		}
		if (!notesTab && event.isLeft()) {
			goTo(page - 1);
			return true;
		}
		if (!notesTab && event.isRight()) {
			goTo(page + 1);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (notesTab && openNote == null) {
			for (NoteRow row : noteRows()) {
				if (row.contains(event.x(), event.y())) {
					openNote(row.note());
					return true;
				}
			}
		}
		if (flipTicks == 0 && !notesTab) {
			if (pages.get(page) instanceof HandbookPage.Contents contents) {
				for (ContentsEntry entry : contentsEntries(contents)) {
					if (entry.contains(event.x(), event.y())) {
						goTo(entry.page());
						return true;
					}
				}
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (notesTab && openNote == null && scrollY != 0) {
			int visible = Math.max(1, noteRows().size());
			noteScroll = Mth.clamp(noteScroll - (int) Math.signum(scrollY), 0, Math.max(0, notes.size() - visible));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int shown = page;
		float squeeze = 1;
		if (flipTicks > 0 && !notesTab) {
			float progress = Mth.clamp(1 - (flipTicks - partialTick) / T.flipTicks(), 0, 1);
			shown = progress < 0.5f ? previousPage : page;
			squeeze = Math.max(0.03f, progress < 0.5f ? 1 - 2 * progress : 2 * progress - 1);
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate(paperLeft, 0);
		graphics.pose().scale(squeeze, 1);
		graphics.pose().translate(-paperLeft, 0);
		PaperDraw.sheet(graphics, paperLeft, paperTop, paperWidth, paperHeight);
		if (notesTab) {
			drawNotes(graphics);
		} else {
			drawPage(graphics, pages.get(shown));
			graphics.centeredText(font, PaperDraw.ink(Component.translatable("deepcharter.handbook.screen.page", shown + 1, pages.size()), T.faintInkColor()),
					(next.getX() + back.getRight()) / 2, back.getY() + 3, OPAQUE | T.faintInkColor());
		}
		graphics.pose().popMatrix();
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void drawPage(GuiGraphicsExtractor graphics, HandbookPage content) {
		switch (content) {
			case HandbookPage.Cover cover -> drawCover(graphics);
			case HandbookPage.Slip slip -> drawSlip(graphics);
			case HandbookPage.Letter letter -> drawLetter(graphics);
			case HandbookPage.Contents contents -> drawContents(graphics, contents);
			case HandbookPage.ChapterText text -> drawChapterText(graphics, text);
			case HandbookPage.Chapter chapter -> drawChapter(graphics, chapter);
			case HandbookPage.Appendix appendix -> drawAppendix(graphics);
			case HandbookPage.Contract contract -> drawContract(graphics, contract);
		}
	}

	/** The middle of the page, the binding band left out. */
	private int centerX() {
		return paperLeft + T.bindingWidth() + (paperWidth - T.bindingWidth()) / 2;
	}

	private int bottom() {
		return paperTop + paperHeight - T.buttonHeight() - 10;
	}

	private static Component tr(String key, Object... args) {
		return Component.translatable("deepcharter.handbook." + key, args);
	}

	private void drawCover(GuiGraphicsExtractor graphics) {
		int y = PaperDraw.centered(graphics, font, tr("cover.company"), centerX(), paperTop + TOP_MARGIN + 6, 1, T.faintInkColor());
		y = PaperDraw.centered(graphics, font, tr("cover.divisions"), centerX(), y, 1, T.faintInkColor());
		Component title = tr("cover.title");
		float scale = Math.min(2f, (float) textWidth / Math.max(1, font.width(PaperDraw.ink(title, T.inkColor()))));
		y = PaperDraw.centered(graphics, font, title, centerX(), y + 14, scale, T.inkColor());
		PaperDraw.centered(graphics, font, tr("cover.subtitle"), centerX(), y + 4, 1, T.inkColor());
		PaperDraw.stamp(graphics, font, tr("cover.stamp"), centerX(), bottom() - 28, -7, T.stampColor());
	}

	private void drawSlip(GuiGraphicsExtractor graphics) {
		int y = PaperDraw.centered(graphics, font, tr("slip.heading"), centerX(), paperTop + TOP_MARGIN, 1.5f, T.inkColor()) + PARAGRAPH_GAP;
		String player = minecraft != null && minecraft.player != null ? minecraft.player.getName().getString() : "";
		y = PaperDraw.wrapped(graphics, font, tr("slip.issued_to", player), textLeft, y, textWidth, T.inkColor()) + 2;
		Component charter = ClientCharter.view().map(view -> (Component) Component.literal(view.name())).orElseGet(() -> tr("slip.no_charter"));
		y = PaperDraw.wrapped(graphics, font, tr("slip.charter", charter), textLeft, y, textWidth, T.inkColor()) + PARAGRAPH_GAP * 2;
		PaperDraw.wrapped(graphics, font, tr("slip.note"), textLeft, y, textWidth, T.inkColor());
		margin(graphics, "deepcharter.handbook.slip.margin", paperTop + CONTENT_TOP * 2);
		PaperDraw.stamp(graphics, font, tr("slip.stamp"), centerX(), bottom() - 28, 6, T.stampColor());
	}

	private void drawLetter(GuiGraphicsExtractor graphics) {
		int y = PaperDraw.centered(graphics, font, tr("letter.heading"), centerX(), paperTop + TOP_MARGIN, 1.25f, T.inkColor()) + PARAGRAPH_GAP;
		y = PaperDraw.wrapped(graphics, font, tr("letter.body.1"), textLeft, y, textWidth, T.inkColor()) + PARAGRAPH_GAP;
		y = PaperDraw.wrapped(graphics, font, tr("letter.body.2"), textLeft, y, textWidth, T.inkColor()) + PARAGRAPH_GAP;
		PaperDraw.centered(graphics, font, tr("letter.signature"), textLeft + textWidth * 3 / 4, y, 1, T.inkColor());
		margin(graphics, "deepcharter.handbook.letter.margin", paperTop + CONTENT_TOP * 2);
	}

	/** The lowest y a contents entry may reach: the top of the Back and Next buttons, less a gap. */
	public int contentsBottom() {
		return bottom();
	}

	/**
	 * The entries of one contents page, top to bottom, every one on one line. Needs the screen to be initialised. The same boxes
	 * are drawn and are the click targets.
	 */
	public List<ContentsEntry> contentsEntries(HandbookPage.Contents contents) {
		List<ContentsEntry> entries = new ArrayList<>();
		int y = contentsTop();
		for (int number = contents.firstChapter(); number < contents.firstChapter() + contents.count(); number++) {
			int pageIndex = chapterPages.get(number);
			entries.add(new ContentsEntry(pageIndex, textLeft, y - 1, textLeft + textWidth, y + font.lineHeight + 2));
			y += font.lineHeight + 4;
		}
		return entries;
	}

	private int contentsTop() {
		return paperTop + TOP_MARGIN + Math.round(font.lineHeight * 1.25f) + 2 + PARAGRAPH_GAP;
	}

	private void drawContents(GuiGraphicsExtractor graphics, HandbookPage.Contents contents) {
		Component heading = contents.parts() > 1 ? tr("contents.heading.part", contents.part(), contents.parts()) : tr("contents.heading");
		PaperDraw.centered(graphics, font, heading, centerX(), paperTop + TOP_MARGIN, 1.25f, T.inkColor());
		for (ContentsEntry box : contentsEntries(contents)) {
			HandbookPage.Chapter chapter = chapters.get(chapterNumberAt(box.page()));
			int y = box.top() + 1;
			if (chapter.visibility() == HandbookVisibility.CLASSIFIED) {
				PaperDraw.redacted(graphics, font, I18n.get("deepcharter.handbook.contents.entry.classified", chapter.number()),
						textLeft, y, textWidth, T.inkColor());
				continue;
			}
			Component status = chapter.isComplete() ? tr("contents.done") : chapter.visibility() == HandbookVisibility.FULL && !isRead.test(chapter.id()) ? tr("contents.new") : null;
			int statusWidth = status == null ? 0 : font.width(status) + 4;
			String line = tr("contents.entry", chapter.number(), chapter.chapter().title()).getString();
			int lineWidth = textWidth - statusWidth;
			if (font.width(PaperDraw.ink(Component.literal(line), T.inkColor())) > lineWidth) {
				line = font.plainSubstrByWidth(line, lineWidth - font.width("...")) + "...";
			}
			graphics.text(font, PaperDraw.ink(Component.literal(line), T.inkColor()), textLeft, y, OPAQUE | T.inkColor(), false);
			if (status != null) {
				graphics.text(font, PaperDraw.ink(status, T.stampColor()), textLeft + textWidth - statusWidth + 4, y, OPAQUE | T.stampColor(), false);
			}
		}
	}

	private int chapterNumberAt(int pageIndex) {
		return switch (pages.get(pageIndex)) {
			case HandbookPage.ChapterText text -> text.number();
			case HandbookPage.Chapter chapter -> chapter.number();
			default -> throw new IllegalArgumentException("page " + pageIndex + " is not a chapter page");
		};
	}

	/** A page of the text of a chapter: its label and title, the text, and the margin note beside it if the language file has one. */
	private void drawChapterText(GuiGraphicsExtractor graphics, HandbookPage.ChapterText text) {
		int y = PaperDraw.wrapped(graphics, font, tr("chapter.label", text.number()), textLeft, paperTop + TOP_MARGIN, textWidth, T.faintInkColor());
		y = PaperDraw.wrapped(graphics, font, text.chapter().title(), textLeft, y + 3, textWidth, T.inkColor());
		graphics.fill(textLeft, y + 1, textLeft + textWidth, y + 2, OPAQUE | T.inkColor());
		y += PARAGRAPH_GAP + 2;
		PaperDraw.wrapped(graphics, font, Component.translatable(textKey(text.id(), text.part())), textLeft, y, textWidth, T.inkColor());
		margin(graphics, textMarginKey(text.id(), text.part()), paperTop + CONTENT_TOP * 2);
	}

	private void drawChapter(GuiGraphicsExtractor graphics, HandbookPage.Chapter chapter) {
		int y = PaperDraw.wrapped(graphics, font, tr("chapter.label", chapter.number()), textLeft, paperTop + TOP_MARGIN, textWidth, T.faintInkColor());
		if (chapter.visibility() == HandbookVisibility.CLASSIFIED) {
			for (int bar = 0; bar < 3; bar++) {
				PaperDraw.redaction(graphics, textLeft, y + 4 + bar * (font.lineHeight + 4), textWidth * (9 - (chapter.number() + bar) % 3) / 10, font.lineHeight);
			}
			y += 3 * (font.lineHeight + 4) + 10;
			PaperDraw.wrapped(graphics, font, tr("chapter.classified"), textLeft, y, textWidth, T.inkColor());
			PaperDraw.stamp(graphics, font, tr("chapter.stamp.classified"), centerX(), bottom() - 28, -12, T.stampColor());
			return;
		}
		y = PaperDraw.wrapped(graphics, font, chapter.chapter().title(), textLeft, y + 3, textWidth, T.inkColor());
		graphics.fill(textLeft, y + 1, textLeft + textWidth, y + 2, OPAQUE | T.inkColor());
		y += PARAGRAPH_GAP + 2;
		y = PaperDraw.wrapped(graphics, font, tr("chapter.directives"), textLeft, y, textWidth, T.faintInkColor()) + 2;
		for (HandbookChapter.Entry directive : chapter.chapter().directives()) {
			String key = chapter.completed().contains(directive.id()) ? "chapter.directive.done" : "chapter.directive.open";
			y = PaperDraw.wrapped(graphics, font, tr(key, directive.text()), textLeft, y, textWidth, T.inkColor()) + 3;
		}
		if (chapter.visibility() == HandbookVisibility.FULL) {
			margin(graphics, marginKey(chapter.id()), paperTop + CONTENT_TOP * 2);
		}
		if (chapter.visibility() == HandbookVisibility.PREVIEW) {
			PaperDraw.stamp(graphics, font, tr("chapter.stamp.coming"), centerX(), bottom() - 28, 8, T.stampColor());
		} else if (chapter.isComplete()) {
			PaperDraw.stamp(graphics, font, tr("chapter.stamp.complete"), centerX(), bottom() - 28, -10, T.stampColor());
		}
	}

	private void drawAppendix(GuiGraphicsExtractor graphics) {
		int y = PaperDraw.centered(graphics, font, tr("appendix.heading"), centerX(), paperTop + TOP_MARGIN, 1.25f, T.inkColor()) + PARAGRAPH_GAP;
		PaperDraw.wrapped(graphics, font, tr("appendix.restricted"), textLeft, y, textWidth, T.inkColor());
		margin(graphics, "deepcharter.handbook.appendix.margin", paperTop + CONTENT_TOP * 2);
		PaperDraw.stamp(graphics, font, tr("appendix.stamp"), centerX(), bottom() - 28, -9, T.stampColor());
	}

	/** A page of Appendix A: its title, and the clauses of the page, one a paragraph, with a black bar over each redacted word. */
	private void drawContract(GuiGraphicsExtractor graphics, HandbookPage.Contract contract) {
		Component heading = contract.parts() > 1 ? tr("appendix.title.part", Component.translatable("deepcharter.handbook.appendix.title"), contract.part(), contract.parts())
				: tr("appendix.title");
		int y = PaperDraw.wrapped(graphics, font, heading, textLeft, paperTop + TOP_MARGIN, textWidth, T.faintInkColor());
		graphics.fill(textLeft, y + 1, textLeft + textWidth, y + 2, OPAQUE | T.inkColor());
		y += PARAGRAPH_GAP + 2;
		for (String clause : I18n.get(contractKey(contract.part())).split("\n")) {
			y = PaperDraw.redacted(graphics, font, clause, textLeft, y, textWidth, T.inkColor()) + PARAGRAPH_GAP;
		}
		margin(graphics, contractKey(contract.part()) + ".margin", paperTop + CONTENT_TOP * 2);
	}

	private void drawNotes(GuiGraphicsExtractor graphics) {
		if (openNote != null) {
			drawNote(graphics, note(openNote));
			return;
		}
		int y = PaperDraw.centered(graphics, font, tr("notes.heading"), centerX(), paperTop + TOP_MARGIN, 1.25f, T.inkColor()) + PARAGRAPH_GAP;
		if (notes.isEmpty()) {
			y = PaperDraw.centered(graphics, font, tr("notes.empty"), centerX(), y + PARAGRAPH_GAP * 3, 1, T.inkColor()) + 2;
			PaperDraw.wrapped(graphics, font, tr("notes.hint"), textLeft, y, textWidth, T.faintInkColor());
			return;
		}
		for (NoteRow row : noteRows()) {
			HandbookNote note = note(row.note());
			boolean unread = noteUnread(note.id());
			int color = unread ? T.inkColor() : T.faintInkColor();
			Component status = unread ? tr("notes.new") : null;
			int statusWidth = status == null ? 0 : font.width(status) + 4;
			String line = tr("notes.entry", label(note), note.title()).getString();
			if (font.width(PaperDraw.ink(Component.literal(line), color)) > textWidth - statusWidth) {
				line = font.plainSubstrByWidth(line, textWidth - statusWidth - font.width("...")) + "...";
			}
			graphics.text(font, PaperDraw.ink(Component.literal(line), color), textLeft, row.top() + 1, OPAQUE | color, false);
			if (status != null) {
				graphics.text(font, PaperDraw.ink(status, T.stampColor()), textLeft + textWidth - statusWidth + 4, row.top() + 1, OPAQUE | T.stampColor(), false);
			}
		}
	}

	private void drawNote(GuiGraphicsExtractor graphics, HandbookNote note) {
		int y = PaperDraw.wrapped(graphics, font, tr("notes.label", label(note)), textLeft, paperTop + TOP_MARGIN, textWidth, T.faintInkColor());
		y = PaperDraw.wrapped(graphics, font, note.title(), textLeft, y + 3, textWidth, T.inkColor());
		graphics.fill(textLeft, y + 1, textLeft + textWidth, y + 2, OPAQUE | T.inkColor());
		PaperDraw.wrapped(graphics, font, note.text(), textLeft, y + PARAGRAPH_GAP + 2, textWidth, T.marginInkColor());
	}

	/** A previous miner's note in pencil, in the margin column, if the sheet has one and the language file has a note for it. */
	private void margin(GuiGraphicsExtractor graphics, String key, int y) {
		if (marginWidth <= 0 || !Language.getInstance().has(key)) {
			return;
		}
		graphics.fill(marginLeft - 2, y - 2, marginLeft - 1, paperTop + paperHeight - T.buttonHeight() - 14, (T.marginInkColor() & 0x00FFFFFF) | 0x80000000);
		PaperDraw.wrapped(graphics, font, Component.translatable(key), marginLeft + 2, y, marginWidth - 4, T.marginInkColor());
	}
}
