package io.github.pkeppeler.deepcharter.client.handbook;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueScreens;
import io.github.pkeppeler.deepcharter.client.theme.Colors;
import io.github.pkeppeler.deepcharter.client.ui.ScrollList;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;
import io.github.pkeppeler.deepcharter.handbook.HandbookReadPayload;
import io.github.pkeppeler.deepcharter.handbook.HandbookVisibility;
import io.github.pkeppeler.deepcharter.handbook.Notes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * The Employee Handbook: a paper booklet, with a Handbook tab (cover, issue slip, Founder's letter, contents, the chapters and the
 * end page) and a Notes tab. All text is the vanilla italic font in coloured ink.
 *
 * <p>What each chapter shows is decided by {@link HandbookVisibility}, before the screen is made: this class only draws the pages
 * it is given. It reports a chapter the first time the player flips to it, if the chapter is fully visible and not yet read, and
 * the owner of the screen sends that to the server ({@link #open}). The Notes tab lists the Notes the charter has found, unread ones
 * marked; opening a Note shows its text and reports it the same way, once, if it is not read yet.
 */
public final class HandbookScreen extends Screen {
    /** The sheet is wide enough for a margin column from this width on. */
    private static final int MARGIN_MIN_PAPER_WIDTH = 220;
    /** The label of the intro's tab: the chapters' tabs carry their numbers. */
    private static final String INTRO_TAB = "i";

    /** The look in force: read at each use, never kept, so a resource reload restyles an open screen. */
    private static HandbookScreenTuning tuning() {
        return HandbookScreenTuning.current();
    }

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
    private final HandbookMemory memory;
    /** The page index of each chapter's quest map, by chapter number. */
    private final Map<Integer, Integer> mapPages;
    /** The index of the first page of any chapter: the pages before it are the intro. */
    private final int firstChapterPage;
    /** The index of the first pencil sheet, or -1 when no Note has drawn a path. */
    private final int pencilPage;

    private int page;
    private int previousPage;
    /**
     * The sheet of the page in view, from 0: a page whose text does not fit one sheet runs on to the next ({@link
     * SheetFlow}).
     */
    private int sheet;

    private int previousSheet;
    /**
     * The first line of each sheet of each page, by page index, as {@link #init} last laid them out; before that, every
     * page has one sheet.
     */
    private List<List<Integer>> sheetStarts;

    private int flipTicks;
    private boolean notesTab;
    private @Nullable Identifier openNote;
    private final ScrollList noteScroll =
            new ScrollList(this::noteCount, () -> Math.max(1, noteRows().size()), () -> {});
    /** The sheet of the open Note being read, from 0: a Note longer than one sheet runs on to the next. */
    private int notePage;

    private int paperLeft;
    private int paperTop;
    private int paperWidth;
    private int paperHeight;
    private int textLeft;
    private int textWidth;
    private int marginLeft;
    private int marginWidth;
    /** The widgets {@link #init} builds. */
    private record Buttons(
            PaperButton back,
            PaperButton next,
            PaperButton handbookTab,
            PaperButton notesButton,
            PaperButton noteBack,
            List<PaperButton> chapterTabs) {}

    /** Absent until {@link #init} has built the widgets. */
    private @Nullable Buttons buttons;

    /** Ticks since the screen opened: the clock an any-of slot's icon cycles on. */
    private int cycleTicks;

    /** The screen closing returns to; none returns to the game. */
    private @Nullable Screen parent;

    /**
     * @param pages    the pages of the Handbook tab, from {@link HandbookPages#of}
     * @param isRead   whether the player has already read an entry: a chapter or a Note
     * @param onViewed told the id of a chapter or Note the player has just opened, once, if it is not read yet
     * @param notes    what the Notes tab lists; empty shows its empty state
     */
    // test-seam: a test builds the book with no memory, so it opens at the cover whatever an earlier test left
    public HandbookScreen(
            List<HandbookPage> pages,
            Predicate<Identifier> isRead,
            Consumer<Identifier> onViewed,
            List<HandbookNote> notes) {
        this(pages, isRead, onViewed, notes, new HandbookMemory());
    }

    /** As above, opening on the page {@code memory} holds, with the flowcharts as it holds them; the screen keeps it up to date. */
    public HandbookScreen(
            List<HandbookPage> pages,
            Predicate<Identifier> isRead,
            Consumer<Identifier> onViewed,
            List<HandbookNote> notes,
            HandbookMemory memory) {
        super(Component.translatable("deepcharter.handbook.screen.title"));
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("the handbook needs at least one page");
        }
        this.pages = List.copyOf(pages);
        this.sheetStarts = Collections.nCopies(this.pages.size(), List.of(0));
        Map<Integer, Integer> firstPages = new HashMap<>();
        Map<Integer, HandbookPage.Chapter> byNumber = new HashMap<>();
        Map<Integer, Integer> maps = new HashMap<>();
        int firstPencil = -1;
        for (int index = 0; index < this.pages.size(); index++) {
            switch (this.pages.get(index)) {
                case HandbookPage.Pencil pencil -> firstPencil = firstPencil < 0 ? index : firstPencil;
                case HandbookPage.ChapterText text -> firstPages.putIfAbsent(text.number(), index);
                case HandbookPage.Chapter chapter -> {
                    firstPages.putIfAbsent(chapter.number(), index);
                    byNumber.put(chapter.number(), chapter);
                    maps.put(chapter.number(), index);
                }
                default -> {}
            }
        }
        this.chapterPages = Map.copyOf(firstPages);
        this.chapters = Map.copyOf(byNumber);
        this.mapPages = Map.copyOf(maps);
        this.pencilPage = firstPencil;
        this.firstChapterPage =
                firstPages.values().stream().min(Integer::compare).orElse(this.pages.size());
        this.memory = memory;
        this.page = memory.pageIn(this.pages);
        this.previousPage = page;
        this.isRead = isRead;
        this.onViewed = onViewed;
        this.notes = List.copyOf(notes);
    }

    /** Opens the handbook with the chapters the server synced and the progress it last sent. Marks a viewed chapter read on the server. */
    public static void open(Minecraft client) {
        open(client, null);
    }

    /** As {@link #open(Minecraft)}, and closing the handbook returns to {@code parent} (none: the game). */
    public static void open(Minecraft client, @Nullable Screen parent) {
        if (client.player == null || client.getConnection() == null) {
            return;
        }
        HandbookScreen screen = new HandbookScreen(
                currentPages(client),
                ClientReadMarks::isRead,
                entry -> ClientPlayNetworking.send(new HandbookReadPayload(entry)),
                ClientNotes.entries(),
                HandbookMemory.SESSION);
        screen.parent = parent;
        client.gui.setScreen(screen);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    /**
     * Opens the handbook on {@code note}, because the player used its block in the world. The Note is listed even when the charter has
     * not found it, so it can be read all the same.
     */
    public static void openNoteFromWorld(Minecraft client, Identifier note) {
        if (client.player == null || client.getConnection() == null || !Notes.exists(note)) {
            return;
        }
        List<HandbookNote> listed = new ArrayList<>(ClientNotes.entries());
        if (listed.stream().noneMatch(entry -> entry.id().equals(note))) {
            listed.add(ClientNotes.entry(note));
            listed.sort(Comparator.comparingInt(HandbookNote::number));
        }
        HandbookScreen screen = new HandbookScreen(
                currentPages(client),
                ClientReadMarks::isRead,
                entry -> ClientPlayNetworking.send(new HandbookReadPayload(entry)),
                listed);
        client.gui.setScreen(screen);
        screen.openNote(note);
    }

    /** The pages of the Handbook tab, from the chapters, progress and pencil paths the server synced. */
    public static List<HandbookPage> currentPages(Minecraft client) {
        Map<Identifier, HandbookChapter> chapters = new LinkedHashMap<>();
        HandbookChapters.all(Objects.requireNonNull(
                                client.getConnection(),
                                "the handbook pages need a connection to the server for its chapters")
                        .registryAccess())
                .forEach(chapter -> chapters.put(chapter.key().identifier(), chapter.value()));
        return HandbookPages.withPencil(HandbookPages.of(chapters, ClientHandbook.completed()), ClientPencil.entries());
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
        return "deepcharter.handbook.chapter." + chapter.getNamespace() + "."
                + chapter.getPath().replace('/', '.');
    }

    // test-seam: the page in view is navigation state that reaches the player only as a flipping sheet (the counter
    // shows the page being left until the flip ends), and goTo's clamping at the cover and the end is what tests pin
    public int page() {
        return page;
    }

    /** Flips to the first sheet of page {@code index}, stopping at the cover and the end page. */
    public void goTo(int index) {
        flipTo(index, 0);
    }

    /** Next: the page's next sheet, or the next page. */
    private void forward() {
        if (sheet < sheetsOf(page) - 1) {
            flipTo(page, sheet + 1);
        } else {
            flipTo(page + 1, 0);
        }
    }

    /** Back: the page's sheet before, or the last sheet of the page before. */
    private void backward() {
        if (sheet > 0) {
            flipTo(page, sheet - 1);
        } else if (page > 0) {
            flipTo(page - 1, sheetsOf(page - 1) - 1);
        }
    }

    /**
     * Flips to sheet {@code toSheet} of page {@code index}, both clamped to the book. A new page is remembered and
     * reported.
     */
    private void flipTo(int index, int toSheet) {
        int target = Mth.clamp(index, 0, pages.size() - 1);
        int targetSheet = Mth.clamp(toSheet, 0, sheetsOf(target) - 1);
        if (target == page && targetSheet == sheet) {
            return;
        }
        boolean newPage = target != page;
        previousPage = page;
        previousSheet = sheet;
        page = target;
        sheet = targetSheet;
        flipTicks = tuning().flipTicks();
        if (newPage) {
            memory.remember(pages.get(page));
            reportViewed();
        }
        updateButtons();
    }

    /** Reports the chapter of the page in view, once, if the player may read it in full and has not read it yet. */
    private void reportViewed() {
        Identifier viewed = switch (pages.get(page)) {
            case HandbookPage.Chapter chapter when chapter.visibility() == HandbookVisibility.FULL -> chapter.id();
            case HandbookPage.ChapterText text -> text.id();
            default -> null;
        };
        if (viewed != null && !isRead.test(viewed) && reported.add(viewed)) {
            onViewed.accept(viewed);
        }
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

    /** Whether the player has yet to read {@code note}, which the Notes list marks. */
    public boolean noteUnread(Identifier note) {
        return !isRead.test(note);
    }

    /** Opens {@code note} on the Notes tab. Reports it as viewed the first time, if it is not read yet. An id the tab does not list is ignored. */
    public void openNote(Identifier note) {
        if (notes.stream().noneMatch(entry -> entry.id().equals(note))) {
            return;
        }
        notesTab = true;
        openNote = note;
        notePage = 0;
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
        for (int index = noteScroll.first(); index < notes.size() && y + font.lineHeight + 2 <= bottom(); index++) {
            rows.add(
                    new NoteRow(notes.get(index).id(), textLeft, y - 1, textLeft + textWidth, y + font.lineHeight + 2));
            y += font.lineHeight + 4;
        }
        return rows;
    }

    private int noteCount() {
        return notes.size();
    }

    private int notesTop() {
        return paperTop + tuning().topMargin() + Math.round(font.lineHeight * 1.25f) + 2 + tuning().paragraphGap();
    }

    private static String label(HandbookNote note) {
        return String.format("N%02d", note.number());
    }

    private HandbookNote note(Identifier id) {
        return notes.stream().filter(entry -> entry.id().equals(id)).findFirst().orElseThrow();
    }

    @Override
    protected void init() {
        paperWidth = Math.min(
                tuning().paperWidth(),
                width
                        - QuestMapTuning.current().windowMargin()
                        - QuestMapTuning.current().chapterTabWidth());
        // The sheet and the tab above it always fit the window, so in the smallest one the sheet gives up its 100 px
        // floor.
        paperHeight = Mth.clamp(
                height - tuning().tabHeight() - 16,
                Math.min(100, height - tuning().tabHeight()),
                tuning().paperMaxHeight());
        paperLeft = Math.min(
                (width - paperWidth) / 2,
                width
                        - paperWidth
                        - QuestMapTuning.current().chapterTabWidth()
                        - QuestMapTuning.current().tabEdgeGap());
        paperTop = (height - paperHeight + tuning().tabHeight()) / 2;
        textLeft = paperLeft + tuning().bindingWidth() + tuning().padding();
        boolean hasMargin = paperWidth >= MARGIN_MIN_PAPER_WIDTH;
        marginWidth = hasMargin ? tuning().marginWidth() : 0;
        int textRight =
                paperLeft + paperWidth - tuning().padding() - (hasMargin ? marginWidth + tuning().marginGap() : 0);
        textWidth = textRight - textLeft;
        marginLeft = textRight + tuning().marginGap();
        layOutSheets();

        int tabY = paperTop - tuning().tabHeight() + 1;
        int tabX = paperLeft + tuning().bindingWidth() + 4;
        PaperButton handbookTab = addRenderableWidget(new PaperButton(
                tabX,
                tabY,
                tuning().tabWidth(),
                tuning().tabHeight(),
                Component.translatable("deepcharter.handbook.screen.tab.handbook"),
                button -> showHandbook()));
        PaperButton notesButton = addRenderableWidget(new PaperButton(
                tabX + tuning().tabWidth() + 2,
                tabY,
                tuning().tabWidth(),
                tuning().tabHeight(),
                Component.translatable("deepcharter.handbook.screen.tab.notes"),
                button -> showNotes()));
        int buttonY = paperTop + paperHeight - tuning().buttonHeight() - 4;
        PaperButton noteBack = addRenderableWidget(new PaperButton(
                textLeft,
                buttonY,
                tuning().buttonWidth(),
                tuning().buttonHeight(),
                Component.translatable("deepcharter.handbook.notes.back"),
                button -> closeNote()));
        PaperButton back = addRenderableWidget(new PaperButton(
                textLeft,
                buttonY,
                tuning().buttonWidth(),
                tuning().buttonHeight(),
                Component.translatable("deepcharter.handbook.screen.back"),
                button -> backward()));
        PaperButton next = addRenderableWidget(new PaperButton(
                paperLeft + paperWidth - tuning().padding() - tuning().buttonWidth(),
                buttonY,
                tuning().buttonWidth(),
                tuning().buttonHeight(),
                Component.translatable("deepcharter.handbook.screen.next"),
                button -> pressNext()));
        buttons = new Buttons(back, next, handbookTab, notesButton, noteBack, chapterTabs());
        // A resize can change how many sheets a Note takes, so the sheet that was on screen may be gone.
        if (openNote != null) {
            notePage = Mth.clamp(notePage, 0, noteSheets() - 1);
        }
        updateButtons();
    }

    /**
     * The tabs down the right edge of the sheet, stepped as far as the sheet is tall: the intro's first, then one for each chapter, then
     * the pencil sheet's once a Note has drawn a path. The intro's opens the cover, a chapter's opens its map, the last opens the
     * pencil sheet. A tab is at index {@link #section} of the list.
     */
    private List<PaperButton> chapterTabs() {
        QuestMapTuning q = QuestMapTuning.current();
        int count = mapPages.size() + 1 + (pencilPage >= 0 ? 1 : 0);
        int pitch = Math.min(tuning().tabHeight() + q.chapterTabGap(), (paperHeight - q.chapterTabTop()) / count);
        int tabsTop = paperTop + q.chapterTabTop();
        int minPitch = font.lineHeight + q.chapterTabGap();
        int tabHeight = pitch - q.chapterTabGap();
        if (pitch < minPitch) {
            // a window too short for the tabs to be a line of text each: they stand a line tall, the gap between them
            // goes first when even that is too long for the window, and the column starts higher
            pitch = Math.max(font.lineHeight, Math.min(minPitch, height / count));
            tabHeight = Math.max(font.lineHeight, pitch - q.chapterTabGap());
            tabsTop = Math.max(0, paperTop + paperHeight - count * pitch);
        }
        List<PaperButton> tabs = new ArrayList<>();
        for (int section = 0; section < count; section++) {
            int target = section;
            PaperButton tab = addRenderableWidget(new PaperButton(
                    paperLeft + paperWidth - 1,
                    tabsTop + section * pitch,
                    q.chapterTabWidth(),
                    tabHeight,
                    section == 0
                            ? Component.literal(INTRO_TAB)
                            : section > mapPages.size()
                                    ? Component.translatable("deepcharter.handbook.pencil.tab")
                                    : Component.literal(Integer.toString(section)),
                    button -> goTo(
                            target == 0
                                    ? 0
                                    : target > mapPages.size()
                                            ? pencilPage
                                            : Objects.requireNonNull(mapPages.get(target)))));
            tab.setJoinsLeft();
            tabs.add(tab);
        }
        return tabs;
    }

    /**
     * The section of the book the open page belongs to, whose tab reads as selected: 0 for the intro (cover, slip, letter and
     * contents), a chapter's number for its text and map, the pencil sheet's own tab after the chapters, or -1 for the end pages and the Notes tab, which have no tab.
     */
    public int section() {
        if (notesTab) {
            return -1;
        }
        if (pages.get(page) instanceof HandbookPage.Pencil) {
            return mapPages.size() + 1;
        }
        int chapter = HandbookPage.chapterNumber(pages.get(page));
        if (chapter > 0) {
            return chapter;
        }
        return page < firstChapterPage ? 0 : -1;
    }

    /** The next button turns a page of the handbook, or a sheet of an open Note, and from the last sheet of a Note goes back to its first. */
    private void pressNext() {
        if (notesTab) {
            turnNote((notePage + 1)
                    % notePages(note(Objects.requireNonNull(
                            openNote, "the next button turns Note sheets only while a Note is open"))));
        } else {
            forward();
        }
    }

    /** How many sheets the open Note takes, or 0 while no Note is open. */
    public int noteSheets() {
        return openNote == null ? 0 : notePages(note(openNote));
    }

    /** Turns the open Note to sheet {@code sheet}, from 0, stopping at its first and last sheet. Does nothing while no Note is open. */
    public void turnNote(int sheet) {
        if (openNote == null) {
            return;
        }
        notePage = Mth.clamp(sheet, 0, noteSheets() - 1);
        updateButtons();
    }

    private void updateButtons() {
        Buttons built = buttons;
        if (built == null) {
            return;
        }
        built.back().visible = !notesTab;
        built.next().visible = !notesTab || (openNote != null && notePages(note(openNote)) > 1);
        built.back().active = !notesTab && (page > 0 || sheet > 0);
        built.next().active = notesTab || page < pages.size() - 1 || sheet < sheetsOf(page) - 1;
        built.noteBack().visible = notesTab && openNote != null;
        int section = section();
        for (int i = 0; i < built.chapterTabs().size(); i++) {
            PaperButton tab = built.chapterTabs().get(i);
            HandbookPage.Chapter entry = chapters.get(i);
            tab.visible = !notesTab;
            tab.setSelected(i == section);
            tab.setFaded(entry != null && entry.visibility() == HandbookVisibility.CLASSIFIED);
        }
        built.handbookTab().setSelected(!notesTab);
        built.notesButton().setSelected(notesTab);
    }

    @Override
    public void tick() {
        super.tick();
        cycleTicks++;
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
            backward();
            return true;
        }
        if (!notesTab && event.isRight()) {
            forward();
            return true;
        }
        if (notesTab && openNote != null && event.isLeft()) {
            turnNote(Math.max(0, notePage - 1));
            return true;
        }
        if (notesTab && openNote != null && event.isRight()) {
            turnNote(Math.min(notePages(note(openNote)) - 1, notePage + 1));
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
        if (flipTicks == 0
                && !notesTab
                && pages.get(page) instanceof HandbookPage.Chapter chapter
                && chapter.visibility() != HandbookVisibility.CLASSIFIED
                && clickMap(chapter, event)) {
            return true;
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

    /** A click on a chapter's map: the index card first (its link, its items), then a step. */
    private boolean clickMap(HandbookPage.Chapter chapter, MouseButtonEvent event) {
        MapFrame frame = mapFrame(chapter);
        if (chapter.visibility() == HandbookVisibility.FULL) {
            QuestMap.Card card = indexCard(chapter);
            if (card.link().contains(event.x(), event.y())) {
                goTo(firstPageOf(chapter));
                return true;
            }
            QuestMap.CardItem item = cardItemUnder(chapter, event.x(), event.y());
            if (item != null) {
                CatalogueScreens.open(item.shown(cycleTicks));
                return true;
            }
            if (card.box().contains(event.x(), event.y())) {
                return true;
            }
        }
        if (!frame.clip().contains(event.x(), event.y())) {
            return false;
        }
        int step = stepUnder(chapter, event.x(), event.y());
        if (step >= 0) {
            memory.pick(
                    chapter.number(), chapter.chapter().directives().get(step).id());
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (notesTab && openNote == null && scrollY != 0) {
            return noteScroll.mouseScrolled(scrollY);
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int shown = page;
        int shownSheet = sheet;
        float squeeze = 1;
        if (flipTicks > 0 && !notesTab) {
            float progress = Mth.clamp(1 - (flipTicks - partialTick) / tuning().flipTicks(), 0, 1);
            shown = progress < 0.5f ? previousPage : page;
            shownSheet = progress < 0.5f ? previousSheet : sheet;
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
            HandbookPage.Chapter under =
                    flipTicks == 0 && pages.get(shown) instanceof HandbookPage.Chapter chapter ? chapter : null;
            int hoveredStep = under == null ? -1 : stepUnder(under, mouseX, mouseY);
            QuestMap.CardItem hoveredItem = under == null ? null : cardItemUnder(under, mouseX, mouseY);
            drawPage(graphics, shown, shownSheet, hoveredStep, hoveredItem);
            Buttons built = widgets();
            PaperDraw.centered(
                    graphics,
                    font,
                    Component.translatable(
                            "deepcharter.handbook.screen.page", sheetNumber(shown, shownSheet), sheetTotal()),
                    (built.next().getX() + built.back().getRight()) / 2,
                    built.back().getY() + 3,
                    1,
                    tuning().faintInkColor());
        }
        graphics.pose().popMatrix();
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * Draws sheet {@code onSheet} (from 0) of page {@code index}; on a chapter's map, step {@code hoveredStep} and the
     * index card's item {@code hoveredItem} are under the mouse.
     */
    private void drawPage(
            GuiGraphicsExtractor graphics,
            int index,
            int onSheet,
            int hoveredStep,
            QuestMap.@Nullable CardItem hoveredItem) {
        switch (pages.get(index)) {
            case HandbookPage.Cover cover -> drawCover(graphics);
            case HandbookPage.Slip slip -> drawSlip(graphics);
            case HandbookPage.Letter letter -> drawLetter(graphics);
            case HandbookPage.Contents contents -> drawContents(graphics, contents);
            case HandbookPage.Controls controls -> drawControls(graphics, controls);
            case HandbookPage.ChapterText text -> drawFlowing(graphics, chapterTextFlow(text), index, onSheet);
            case HandbookPage.Chapter chapter -> drawChapter(graphics, chapter, hoveredStep, hoveredItem);
            case HandbookPage.Pencil pencil -> drawPencil(graphics, pencil);
            case HandbookPage.Appendix appendix -> drawAppendix(graphics);
            case HandbookPage.Contract contract -> drawFlowing(graphics, contractFlow(contract), index, onSheet);
        }
    }

    /** The middle of the page, the binding band left out. */
    private int centerX() {
        return paperLeft + tuning().bindingWidth() + (paperWidth - tuning().bindingWidth()) / 2;
    }

    private int bottom() {
        return paperTop + paperHeight - tuning().buttonHeight() - 10;
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("deepcharter.handbook." + key, args);
    }

    private void drawCover(GuiGraphicsExtractor graphics) {
        int y = PaperDraw.centered(
                graphics,
                font,
                tr("cover.company"),
                centerX(),
                paperTop + tuning().topMargin() + 6,
                1,
                tuning().faintInkColor());
        y = PaperDraw.centered(graphics, font, tr("cover.divisions"), centerX(), y, 1, tuning().faintInkColor());
        Component title = tr("cover.title");
        float scale =
                Math.min(2f, (float) textWidth / Math.max(1, font.width(PaperDraw.ink(title, tuning().inkColor()))));
        y = PaperDraw.centered(graphics, font, title, centerX(), y + 14, scale, tuning().inkColor());
        PaperDraw.centered(graphics, font, tr("cover.subtitle"), centerX(), y + 4, 1, tuning().inkColor());
        PaperDraw.stamp(graphics, font, tr("cover.stamp"), centerX(), bottom() - 28, -7, tuning().stampColor());
    }

    private void drawSlip(GuiGraphicsExtractor graphics) {
        int y = PaperDraw.centered(
                        graphics,
                        font,
                        tr("slip.heading"),
                        centerX(),
                        paperTop + tuning().topMargin(),
                        1.5f,
                        tuning().inkColor())
                + tuning().paragraphGap();
        String player = minecraft != null && minecraft.player != null
                ? minecraft.player.getName().getString()
                : "";
        y = PaperDraw.wrapped(graphics, font, tr("slip.issued_to", player), textLeft, y, textWidth, tuning().inkColor())
                + 2;
        Component charter = ClientCharter.view()
                .map(view -> (Component) Component.literal(view.name()))
                .orElseGet(() -> tr("slip.no_charter"));
        y = PaperDraw.wrapped(graphics, font, tr("slip.charter", charter), textLeft, y, textWidth, tuning().inkColor())
                + tuning().paragraphGap() * 2;
        PaperDraw.wrapped(graphics, font, tr("slip.note"), textLeft, y, textWidth, tuning().inkColor());
        margin(graphics, "deepcharter.handbook.slip.margin", paperTop + tuning().contentTop() * 2);
        PaperDraw.stamp(graphics, font, tr("slip.stamp"), centerX(), bottom() - 28, 6, tuning().stampColor());
    }

    private void drawLetter(GuiGraphicsExtractor graphics) {
        int y = PaperDraw.centered(
                        graphics,
                        font,
                        tr("letter.heading"),
                        centerX(),
                        paperTop + tuning().topMargin(),
                        1.25f,
                        tuning().inkColor())
                + tuning().paragraphGap();
        y = PaperDraw.wrapped(graphics, font, tr("letter.body.1"), textLeft, y, textWidth, tuning().inkColor())
                + tuning().paragraphGap();
        y = PaperDraw.wrapped(graphics, font, tr("letter.body.2"), textLeft, y, textWidth, tuning().inkColor())
                + tuning().paragraphGap();
        PaperDraw.centered(
                graphics, font, tr("letter.signature"), textLeft + textWidth * 3 / 4, y, 1, tuning().inkColor());
        margin(graphics, "deepcharter.handbook.letter.margin", paperTop + tuning().contentTop() * 2);
    }

    /**
     * The entries of one contents page, top to bottom, every one on one line. Needs the screen to be initialised. The same boxes
     * are drawn and are the click targets.
     */
    public List<ContentsEntry> contentsEntries(HandbookPage.Contents contents) {
        List<ContentsEntry> entries = new ArrayList<>();
        int y = contentsTop();
        for (int number = contents.firstChapter(); number < contents.firstChapter() + contents.count(); number++) {
            int chapterNumber = number;
            int pageIndex = Objects.requireNonNull(
                    chapterPages.get(chapterNumber), () -> "chapter " + chapterNumber + " has no page in the handbook");
            entries.add(new ContentsEntry(pageIndex, textLeft, y - 1, textLeft + textWidth, y + font.lineHeight + 2));
            y += font.lineHeight + 4;
        }
        return entries;
    }

    /** The widgets, for code that runs only on a screen that has been initialised. */
    private Buttons widgets() {
        return Objects.requireNonNull(buttons, () -> "the handbook buttons are built in init(), which has not run");
    }

    private int contentsTop() {
        return paperTop + tuning().topMargin() + Math.round(font.lineHeight * 1.25f) + 2 + tuning().paragraphGap();
    }

    private void drawContents(GuiGraphicsExtractor graphics, HandbookPage.Contents contents) {
        Component heading = contents.parts() > 1
                ? tr("contents.heading.part", contents.part(), contents.parts())
                : tr("contents.heading");
        PaperDraw.centered(
                graphics, font, heading, centerX(), paperTop + tuning().topMargin(), 1.25f, tuning().inkColor());
        for (ContentsEntry box : contentsEntries(contents)) {
            int chapterNumber = chapterNumberAt(box.page());
            HandbookPage.Chapter chapter = Objects.requireNonNull(
                    chapters.get(chapterNumber), () -> "chapter " + chapterNumber + " has no chapter page");
            int y = box.top() + 1;
            if (chapter.visibility() == HandbookVisibility.CLASSIFIED) {
                PaperDraw.redacted(
                        graphics,
                        font,
                        I18n.get("deepcharter.handbook.contents.entry.classified", chapter.number()),
                        textLeft,
                        y,
                        textWidth,
                        tuning().inkColor());
                continue;
            }
            Component status = chapter.isComplete()
                    ? tr("contents.done")
                    : chapter.visibility() == HandbookVisibility.FULL && !isRead.test(chapter.id())
                            ? tr("contents.new")
                            : null;
            int statusWidth = status == null ? 0 : font.width(status) + 4;
            String line = tr(
                            "contents.entry",
                            chapter.number(),
                            chapter.chapter().title())
                    .getString();
            int lineWidth = textWidth - statusWidth;
            if (font.width(PaperDraw.ink(Component.literal(line), tuning().inkColor())) > lineWidth) {
                line = font.plainSubstrByWidth(line, lineWidth - font.width("...")) + "...";
            }
            graphics.text(
                    font,
                    PaperDraw.ink(Component.literal(line), tuning().inkColor()),
                    textLeft,
                    y,
                    Colors.opaque(tuning().inkColor()),
                    false);
            if (status != null) {
                graphics.text(
                        font,
                        PaperDraw.ink(status, tuning().stampColor()),
                        textLeft + textWidth - statusWidth + 4,
                        y,
                        Colors.opaque(tuning().stampColor()),
                        false);
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

    /** A page of the controls: its label and title, then each line, with the keys the player has bound now. */
    private void drawControls(GuiGraphicsExtractor graphics, HandbookPage.Controls controls) {
        int y = SheetFlow.draw(
                graphics,
                heading(
                        tr("controls.label", controls.part(), controls.parts()),
                        Component.translatable(HandbookControls.titleKey(controls.part()))),
                paperTop + tuning().topMargin());
        for (Component line : HandbookControls.lines(controls.part(), Minecraft.getInstance().options)) {
            y = PaperDraw.wrapped(graphics, font, line, textLeft, y, textWidth, tuning().inkColor())
                    + tuning().paragraphGap();
        }
    }

    /**
     * What a page that flows is made of: the heading every sheet repeats, the body that runs over the sheets, and the
     * key of the margin note beside its first sheet.
     */
    private record Flow(List<SheetFlow.Line> heading, List<SheetFlow.Line> body, String marginKey) {}

    /** A chapter's text page: the chapter's label and title, then the text. */
    private Flow chapterTextFlow(HandbookPage.ChapterText text) {
        return new Flow(
                heading(tr("chapter.label", text.number()), text.chapter().title()),
                inked(Component.translatable(textKey(text.id(), text.part())), tuning().inkColor()),
                textMarginKey(text.id(), text.part()));
    }

    /**
     * A sheet of a page that flows: its heading, the lines of its body that fall on this sheet, and on the first sheet
     * the margin note beside it if the language file has one.
     */
    private void drawFlowing(GuiGraphicsExtractor graphics, Flow flow, int index, int onSheet) {
        int top = SheetFlow.draw(graphics, flow.heading(), paperTop + tuning().topMargin());
        SheetFlow.draw(graphics, SheetFlow.sheet(flow.body(), sheetStarts.get(index), onSheet), top);
        if (onSheet == 0) {
            margin(graphics, flow.marginKey(), paperTop + tuning().contentTop() * 2);
        }
    }

    /**
     * A page's heading as lines, laid out once both to draw it and to find where the text under it starts:
     * {@code label} in faint ink, then {@code title} in ink if there is one, then a rule across the text column.
     */
    private List<SheetFlow.Line> heading(Component label, @Nullable Component title) {
        List<SheetFlow.Line> lines = new ArrayList<>(inked(label, tuning().faintInkColor()));
        if (title != null) {
            if (!lines.isEmpty()) {
                SheetFlow.Line last = lines.removeLast();
                lines.add(new SheetFlow.Line(
                        last.pitch() + QuestMapTuning.current().headingGap(), last.draw()));
            }
            lines.addAll(inked(title, tuning().inkColor()));
        }
        lines.add(new SheetFlow.Line(
                tuning().paragraphGap() + 2,
                (graphics, y) -> graphics.fill(
                        textLeft, y + 1, textLeft + textWidth, y + 2, Colors.opaque(tuning().inkColor()))));
        return lines;
    }

    /** {@code text} in {@code color} ink, wrapped to the text column, a line each. */
    private List<SheetFlow.Line> inked(Component text, int color) {
        return font.split(PaperDraw.ink(text, color), textWidth).stream()
                .map(line -> new SheetFlow.Line(
                        font.lineHeight + 1,
                        (graphics, y) -> graphics.text(font, line, textLeft, y, Colors.opaque(color), false)))
                .toList();
    }

    private void drawChapter(
            GuiGraphicsExtractor graphics,
            HandbookPage.Chapter chapter,
            int hoveredStep,
            QuestMap.@Nullable CardItem hoveredItem) {
        if (chapter.visibility() != HandbookVisibility.CLASSIFIED) {
            drawMap(graphics, chapter, hoveredStep, hoveredItem);
            return;
        }
        int y = PaperDraw.wrapped(
                graphics,
                font,
                tr("chapter.label", chapter.number()),
                textLeft,
                paperTop + tuning().topMargin(),
                textWidth,
                tuning().faintInkColor());
        int noteLines = font.split(tr("chapter.classified"), textWidth).size();
        int noteHeight = noteLines * (font.lineHeight + 1);
        // A short sheet gives up redaction bars before the note runs under Back and Next.
        int bars = Mth.clamp((bottom() - y - 10 - noteHeight) / (font.lineHeight + 4), 0, 3);
        for (int bar = 0; bar < bars; bar++) {
            PaperDraw.redaction(
                    graphics,
                    textLeft,
                    y + 4 + bar * (font.lineHeight + 4),
                    textWidth * (9 - (chapter.number() + bar) % 3) / 10,
                    font.lineHeight);
        }
        y += 3 * (font.lineHeight + 4) + 10;
        if (bars < 3) {
            y -= (3 - bars) * (font.lineHeight + 4);
        }
        int noteEnd = PaperDraw.wrapped(
                graphics, font, tr("chapter.classified"), textLeft, y, textWidth, tuning().inkColor());
        int stampY = bottom() - 28;
        // The stamp's frame stands 3 px above its lettering; with no room under the note it is left out.
        if (stampY - 3 >= noteEnd) {
            PaperDraw.stamp(
                    graphics, font, tr("chapter.stamp.classified"), centerX(), stampY, -12, tuning().stampColor());
        }
    }

    /** The map's mouse-facing geometry: the heading it hangs under, the view the flowchart is clipped to, and the corner it grows from. */
    private record MapFrame(int headingBottom, QuestMap.Rect clip, int originX, int originY) {}

    private int mapWidth() {
        return paperLeft + paperWidth - tuning().padding() - textLeft;
    }

    /** The width a chapter's label and title wrap to: short of the index card when the card is up. */
    private int headingWidth(HandbookPage.Chapter chapter) {
        if (chapter.visibility() != HandbookVisibility.FULL) {
            return mapWidth();
        }
        QuestMapTuning q = QuestMapTuning.current();
        return Math.max(q.headingMinWidth(), indexCard(chapter).box().x() - q.headingCardGap() - textLeft);
    }

    private MapFrame mapFrame(HandbookPage.Chapter chapter) {
        int labelEnd = paperTop
                + tuning().topMargin()
                + font.split(PaperDraw.ink(tr("chapter.label", chapter.number()), 0), headingWidth(chapter))
                                .size()
                        * (font.lineHeight + 1);
        int titleEnd = labelEnd
                + QuestMapTuning.current().headingGap()
                + font.split(PaperDraw.ink(chapter.chapter().title(), 0), headingWidth(chapter))
                                .size()
                        * (font.lineHeight + 1);
        int top = titleEnd + QuestMapTuning.current().ruleGap();
        QuestMap.Rect clip = new QuestMap.Rect(
                paperLeft + tuning().bindingWidth(), top, paperWidth - tuning().bindingWidth() - 1, bottom() - top);
        return new MapFrame(
                titleEnd, clip, textLeft, top + QuestMapTuning.current().mapPad());
    }

    private @Nullable Component mapNote(HandbookPage.Chapter chapter) {
        return chapter.visibility() == HandbookVisibility.FULL
                        && Language.getInstance().has(marginKey(chapter.id()))
                ? Component.translatable(marginKey(chapter.id()))
                : null;
    }

    private QuestMap.Layout mapLayout(HandbookPage.Chapter chapter) {
        Component note = mapNote(chapter);
        int noteLines = note == null
                ? 0
                : font.split(PaperDraw.ink(note, 0), QuestMapTuning.current().noteWidth())
                        .size();
        return QuestMap.layout(
                mapWidth(),
                mapFrame(chapter).clip().h() - QuestMapTuning.current().mapPad(),
                QuestMap.model(chapters, chapter.number()),
                font.lineHeight,
                noteLines);
    }

    private int heldItems(Item item) {
        return minecraft != null && minecraft.player != null
                ? minecraft.player.getInventory().countItem(item)
                : 0;
    }

    private String seePageLink(HandbookPage.Chapter chapter) {
        return I18n.get("deepcharter.handbook.quest.see_page", sheetNumber(firstPageOf(chapter), 0));
    }

    private QuestMap.Card indexCard(HandbookPage.Chapter chapter) {
        HandbookChapter.Entry step = QuestMap.shown(chapter, memory.picked(chapter.number()));
        return QuestMap.card(
                font,
                step,
                chapter.chapter().directives().indexOf(step) + 1,
                paperLeft + paperWidth,
                paperTop,
                seePageLink(chapter),
                chapter.completed().contains(step.id()),
                this::heldItems);
    }

    // test-seam: where the index card of a chapter's picked step draws the icon of {@code item}, which the client test
    // clicks to
    // see the click open the item's recipes; null when the card has no such item
    public @Nullable Rect indexCardItemBox(int chapterNumber, Item item) {
        HandbookPage.Chapter chapter = Objects.requireNonNull(
                chapters.get(chapterNumber), () -> "chapter " + chapterNumber + " has no chapter page");
        for (QuestMap.CardItem shown : indexCard(chapter).items()) {
            if (shown.items().contains(item)) {
                return new Rect(
                        shown.box().x(),
                        shown.box().y(),
                        shown.box().w(),
                        shown.box().h());
            }
        }
        return null;
    }

    // test-seam: the box of the index card of a chapter's picked step, which the layout test holds every item's hit
    // box inside
    public Rect indexCardBox(int chapterNumber) {
        return rect(indexCard(chapterPage(chapterNumber)).box());
    }

    // test-seam: the hit box of each item of the index card of a chapter's picked step, in order, which no drawn text
    // shows; the layout test holds them inside the card and clear of each other
    public List<Rect> indexCardItemHits(int chapterNumber) {
        return indexCard(chapterPage(chapterNumber)).items().stream()
                .map(item -> rect(item.hit()))
                .toList();
    }

    private HandbookPage.Chapter chapterPage(int chapterNumber) {
        return Objects.requireNonNull(
                chapters.get(chapterNumber), () -> "chapter " + chapterNumber + " has no chapter page");
    }

    private static Rect rect(QuestMap.Rect box) {
        return new Rect(box.x(), box.y(), box.w(), box.h());
    }

    // test-seam: the number the first impression rests on, which no drawn text shows: how many pixels of the sheet a
    // chapter's map leaves unused (negative when it would overflow). The fits test holds it at 0 or more for every
    // shipped chapter
    public int mapSpareHeight(int chapterNumber) {
        HandbookPage.Chapter chapter = Objects.requireNonNull(
                chapters.get(chapterNumber), () -> "chapter " + chapterNumber + " has no chapter page");
        return mapFrame(chapter).clip().h()
                - QuestMapTuning.current().mapPad()
                - mapLayout(chapter).height();
    }

    /** The first page of a chapter in the book: its text if it has any, else its map. */
    private int firstPageOf(HandbookPage.Chapter chapter) {
        return Objects.requireNonNull(
                chapterPages.get(chapter.number()),
                () -> "chapter " + chapter.number() + " has no page in the handbook");
    }

    /** Whether a click on a step of {@code chapter}'s map picks it: only a chapter in full has steps to pick. */
    private static boolean pickable(HandbookPage.Chapter chapter) {
        return chapter.visibility() == HandbookVisibility.FULL;
    }

    /**
     * The step of {@code chapter}'s map under screen point ({@code x}, {@code y}), the one a click there picks; -1 off
     * the steps, under the index card, or on a map that is not {@link #pickable}.
     */
    private int stepUnder(HandbookPage.Chapter chapter, double x, double y) {
        if (!pickable(chapter) || indexCard(chapter).box().contains(x, y)) {
            return -1;
        }
        MapFrame frame = mapFrame(chapter);
        return frame.clip().contains(x, y) ? mapLayout(chapter).stepAt(x - frame.originX(), y - frame.originY()) : -1;
    }

    /**
     * Whether a click on an item of {@code chapter}'s index card opens its recipe: the card is up, on a chapter in
     * full, and the window has room for the catalogue.
     */
    private static boolean itemsOpen(HandbookPage.Chapter chapter) {
        return chapter.visibility() == HandbookVisibility.FULL && CatalogueScreens.canOpen();
    }

    /**
     * The item of {@code chapter}'s index card under screen point ({@code x}, {@code y}), the one whose recipe a click
     * there opens; null off the items or when the items open nothing ({@link #itemsOpen}).
     */
    private QuestMap.@Nullable CardItem cardItemUnder(HandbookPage.Chapter chapter, double x, double y) {
        return itemsOpen(chapter) ? indexCard(chapter).itemAt(x, y) : null;
    }

    /**
     * A chapter's heading, then its quest map with step {@code hoveredStep} under the mouse, then the index card of
     * the step the player has picked, with its item {@code hoveredItem} under the mouse.
     */
    private void drawMap(
            GuiGraphicsExtractor graphics,
            HandbookPage.Chapter chapter,
            int hoveredStep,
            QuestMap.@Nullable CardItem hoveredItem) {
        boolean full = chapter.visibility() == HandbookVisibility.FULL;
        MapFrame frame = mapFrame(chapter);
        int y = PaperDraw.wrapped(
                graphics,
                font,
                tr("chapter.label", chapter.number()),
                textLeft,
                paperTop + tuning().topMargin(),
                headingWidth(chapter),
                tuning().faintInkColor());
        PaperDraw.wrapped(
                graphics,
                font,
                chapter.chapter().title(),
                textLeft,
                y + QuestMapTuning.current().headingGap(),
                headingWidth(chapter),
                tuning().inkColor());
        graphics.fill(
                textLeft,
                frame.headingBottom() + 1,
                textLeft + mapWidth(),
                frame.headingBottom() + 2,
                Colors.opaque(tuning().inkColor()));
        QuestMap.Model model = QuestMap.model(chapters, chapter.number());
        QuestMap.Layout layout = mapLayout(chapter);
        QuestMap.drawChart(
                graphics,
                font,
                model,
                layout,
                frame.originX(),
                frame.originY(),
                frame.clip(),
                mapNote(chapter),
                tr("chapter.stamp.classified"),
                pickable(chapter),
                hoveredStep);
        int stampX = textLeft + QuestMapTuning.current().stampLeft();
        int stampY = paperTop + tuning().topMargin() + QuestMapTuning.current().stampTop();
        if (chapter.visibility() == HandbookVisibility.PREVIEW) {
            PaperDraw.stamp(graphics, font, tr("chapter.stamp.coming"), stampX, stampY, 8, tuning().stampColor());
        } else if (chapter.isComplete()) {
            PaperDraw.stamp(graphics, font, tr("chapter.stamp.complete"), stampX, stampY, -10, tuning().stampColor());
        }
        if (full) {
            HandbookChapter.Entry step = QuestMap.shown(chapter, memory.picked(chapter.number()));
            QuestMap.Card card = indexCard(chapter);
            QuestMap.drawCard(
                    graphics,
                    font,
                    card,
                    step,
                    tr("quest.step", card.stepNumber()),
                    seePageLink(chapter),
                    cycleTicks,
                    itemsOpen(chapter),
                    hoveredItem);
        }
    }

    /** The pencil sheet: its heading and rule, then a row for each path on it. The page is fixed: nothing on it drags or zooms. */
    private void drawPencil(GuiGraphicsExtractor graphics, HandbookPage.Pencil pencil) {
        int y = PaperDraw.wrapped(
                graphics,
                font,
                tr("pencil.label"),
                textLeft,
                paperTop + tuning().topMargin(),
                mapWidth(),
                tuning().faintInkColor());
        y = PaperDraw.wrapped(
                graphics,
                font,
                tr("pencil.title"),
                textLeft,
                y + QuestMapTuning.current().headingGap(),
                mapWidth(),
                tuning().inkColor());
        // as drawMap's rule: across the whole sheet the rows are drawn on
        graphics.fill(textLeft, y + 1, textLeft + mapWidth(), y + 2, Colors.opaque(tuning().inkColor()));
        PencilSheet.draw(graphics, font, pencilLayout(pencil, y + tuning().paragraphGap() + 2));
    }

    private PencilSheet.Layout pencilLayout(HandbookPage.Pencil pencil, int top) {
        return PencilSheet.layout(pencil.entries(), textLeft, top, mapWidth(), bottom(), font.lineHeight);
    }

    private void drawAppendix(GuiGraphicsExtractor graphics) {
        int y = PaperDraw.centered(
                        graphics,
                        font,
                        tr("appendix.heading"),
                        centerX(),
                        paperTop + tuning().topMargin(),
                        1.25f,
                        tuning().inkColor())
                + tuning().paragraphGap();
        PaperDraw.wrapped(graphics, font, tr("appendix.restricted"), textLeft, y, textWidth, tuning().inkColor());
        margin(graphics, "deepcharter.handbook.appendix.margin", paperTop + tuning().contentTop() * 2);
        PaperDraw.stamp(graphics, font, tr("appendix.stamp"), centerX(), bottom() - 28, -9, tuning().stampColor());
    }

    /**
     * A page of Appendix A: its title, then its clauses, one clause a paragraph, with a black bar over each redacted
     * word.
     */
    private Flow contractFlow(HandbookPage.Contract contract) {
        return new Flow(
                heading(contractHeading(contract), null),
                contractLines(contract),
                contractKey(contract.part()) + ".margin");
    }

    private static Component contractHeading(HandbookPage.Contract contract) {
        return contract.parts() > 1
                ? tr(
                        "appendix.title.part",
                        Component.translatable("deepcharter.handbook.appendix.title"),
                        contract.part(),
                        contract.parts())
                : tr("appendix.title");
    }

    /** The lines of the clauses of a page of Appendix A, a paragraph gap after each clause. */
    private List<SheetFlow.Line> contractLines(HandbookPage.Contract contract) {
        int color = tuning().inkColor();
        int pitch = PaperDraw.redactedPitch(font);
        List<SheetFlow.Line> lines = new ArrayList<>();
        for (String clause : I18n.get(contractKey(contract.part())).lines().toList()) {
            List<List<PaperDraw.Piece>> rows = PaperDraw.redactedLines(font, clause, textWidth, color);
            if (rows.isEmpty()) {
                lines.add(new SheetFlow.Line(pitch + tuning().paragraphGap(), (graphics, y) -> {}));
            }
            for (int row = 0; row < rows.size(); row++) {
                List<PaperDraw.Piece> pieces = rows.get(row);
                int gap = row == rows.size() - 1 ? tuning().paragraphGap() : 0;
                lines.add(new SheetFlow.Line(
                        pitch + gap,
                        (graphics, y) -> PaperDraw.redactedLine(graphics, font, pieces, textLeft, y, color)));
            }
        }
        return lines;
    }

    /**
     * Splits each page's body into the sheets this window holds, and keeps the screen on a sheet that is still there.
     */
    private void layOutSheets() {
        sheetStarts = pages.stream()
                .map(content -> switch (content) {
                    case HandbookPage.ChapterText text -> flowStarts(chapterTextFlow(text));
                    case HandbookPage.Contract contract -> flowStarts(contractFlow(contract));
                    default -> List.of(0);
                })
                .toList();
        sheet = Mth.clamp(sheet, 0, sheetsOf(page) - 1);
        previousSheet = Mth.clamp(previousSheet, 0, sheetsOf(previousPage) - 1);
    }

    /** The first line of each sheet of a page that flows, under the heading every sheet repeats. */
    private List<Integer> flowStarts(Flow flow) {
        int bodyTop = paperTop + tuning().topMargin() + SheetFlow.height(flow.heading());
        return SheetFlow.starts(flow.body(), bodyTop, bodyTop, bottom(), font.lineHeight + 1);
    }

    private int sheetsOf(int index) {
        return sheetStarts.get(index).size();
    }

    /**
     * What the page counter shows for sheet {@code onSheet} of page {@code index}: how many sheets come before it, plus
     * one.
     */
    private int sheetNumber(int index, int onSheet) {
        int number = onSheet + 1;
        for (int before = 0; before < index; before++) {
            number += sheetsOf(before);
        }
        return number;
    }

    private int sheetTotal() {
        return sheetNumber(pages.size() - 1, sheetsOf(pages.size() - 1) - 1);
    }

    private void drawNotes(GuiGraphicsExtractor graphics) {
        if (openNote != null) {
            drawNote(graphics, note(openNote));
            return;
        }
        int y = PaperDraw.centered(
                        graphics,
                        font,
                        tr("notes.heading"),
                        centerX(),
                        paperTop + tuning().topMargin(),
                        1.25f,
                        tuning().inkColor())
                + tuning().paragraphGap();
        if (notes.isEmpty()) {
            y = PaperDraw.centered(
                            graphics,
                            font,
                            tr("notes.empty"),
                            centerX(),
                            y + tuning().paragraphGap() * 3,
                            1,
                            tuning().inkColor())
                    + 2;
            PaperDraw.wrapped(graphics, font, tr("notes.hint"), textLeft, y, textWidth, tuning().faintInkColor());
            return;
        }
        for (NoteRow row : noteRows()) {
            HandbookNote note = note(row.note());
            boolean unread = noteUnread(note.id());
            int color = unread ? tuning().inkColor() : tuning().faintInkColor();
            Component status = unread ? tr("notes.new") : null;
            int statusWidth = status == null ? 0 : font.width(status) + 4;
            String line = tr("notes.entry", label(note), note.title()).getString();
            if (font.width(PaperDraw.ink(Component.literal(line), color)) > textWidth - statusWidth) {
                line = font.plainSubstrByWidth(line, textWidth - statusWidth - font.width("...")) + "...";
            }
            graphics.text(
                    font,
                    PaperDraw.ink(Component.literal(line), color),
                    textLeft,
                    row.top() + 1,
                    Colors.opaque(color),
                    false);
            if (status != null) {
                graphics.text(
                        font,
                        PaperDraw.ink(status, tuning().stampColor()),
                        textLeft + textWidth - statusWidth + 4,
                        row.top() + 1,
                        Colors.opaque(tuning().stampColor()),
                        false);
            }
        }
    }

    private void drawNote(GuiGraphicsExtractor graphics, HandbookNote note) {
        List<Integer> starts = noteStarts(note);
        int y = paperTop + tuning().topMargin();
        if (notePage == 0) {
            y = SheetFlow.draw(graphics, noteHeading(note), y);
        }
        SheetFlow.draw(graphics, SheetFlow.sheet(noteBody(note), starts, notePage), y);
        if (starts.size() > 1) {
            Buttons built = widgets();
            PaperDraw.centered(
                    graphics,
                    font,
                    Component.translatable("deepcharter.handbook.screen.page", notePage + 1, starts.size()),
                    (built.next().getX() + built.noteBack().getRight()) / 2,
                    built.noteBack().getY() + 3,
                    1,
                    tuning().faintInkColor());
        }
    }

    private List<SheetFlow.Line> noteHeading(HandbookNote note) {
        return heading(tr("notes.label", label(note)), note.title());
    }

    private List<SheetFlow.Line> noteBody(HandbookNote note) {
        return inked(note.text(), tuning().marginInkColor());
    }

    /**
     * The first line of each sheet of a Note. Its label and title head the first sheet only: a Note is a loose paper
     * read on its own, under its own sheet counter, not a page of the book.
     */
    private List<Integer> noteStarts(HandbookNote note) {
        int top = paperTop + tuning().topMargin();
        return SheetFlow.starts(
                noteBody(note), top + SheetFlow.height(noteHeading(note)), top, bottom(), font.lineHeight + 1);
    }

    /** How many sheets a Note takes, so that none of its text runs past the buttons. */
    private int notePages(HandbookNote note) {
        return noteStarts(note).size();
    }

    /** A previous miner's note in pencil, in the margin column, if the sheet has one and the language file has a note for it. */
    private void margin(GuiGraphicsExtractor graphics, String key, int y) {
        if (marginWidth <= 0 || !Language.getInstance().has(key)) {
            return;
        }
        graphics.fill(
                marginLeft - 2,
                y - 2,
                marginLeft - 1,
                paperTop + paperHeight - tuning().buttonHeight() - 14,
                Colors.withAlpha(tuning().marginInkColor(), tuning().marginRuleAlpha()));
        PaperDraw.wrapped(
                graphics,
                font,
                Component.translatable(key),
                marginLeft + 2,
                y,
                marginWidth - 4,
                tuning().marginInkColor());
    }
}
