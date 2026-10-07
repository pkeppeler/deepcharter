package io.github.pkeppeler.deepcharter.client.handbook;

import java.util.ArrayList;
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
 * the owner of the screen sends that to the server ({@link #open}). The notes are not drawn yet beyond an empty state: #78 fills
 * them in.
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

	/** A part of the contents page that jumps to a page when clicked. */
	private record Hit(int left, int top, int right, int bottom, int page) {
		boolean contains(double x, double y) {
			return x >= left && x < right && y >= top && y < bottom;
		}
	}

	private final List<HandbookPage> pages;
	private final Predicate<Identifier> isRead;
	private final Consumer<Identifier> onViewed;
	private final List<Component> notes;
	private final Set<Identifier> reported = new HashSet<>();
	private final List<Hit> hits = new ArrayList<>();

	private int page;
	private int previousPage;
	private int flipTicks;
	private boolean notesTab;

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

	/**
	 * @param pages    the pages of the Handbook tab, from {@link HandbookPages#of}
	 * @param isRead   whether the player has already read a chapter
	 * @param onViewed told the id of a full chapter the player has just flipped to, once, if it is not read yet
	 * @param notes    what the Notes tab lists; empty shows its empty state
	 */
	public HandbookScreen(List<HandbookPage> pages, Predicate<Identifier> isRead, Consumer<Identifier> onViewed, List<Component> notes) {
		super(Component.translatable("deepcharter.handbook.screen.title"));
		if (pages.isEmpty()) {
			throw new IllegalArgumentException("the handbook needs at least one page");
		}
		this.pages = List.copyOf(pages);
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
				chapter -> ClientPlayNetworking.send(new HandbookReadPayload(chapter)), List.of()));
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
		if (pages.get(page) instanceof HandbookPage.Chapter chapter && chapter.visibility() == HandbookVisibility.FULL
				&& !isRead.test(chapter.id()) && reported.add(chapter.id())) {
			onViewed.accept(chapter.id());
		}
		updateButtons();
	}

	public boolean onNotesTab() {
		return notesTab;
	}

	public void showNotes() {
		notesTab = true;
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
		if (flipTicks == 0 && !notesTab) {
			for (Hit hit : hits) {
				if (hit.contains(event.x(), event.y())) {
					goTo(hit.page());
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		hits.clear();
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
			case HandbookPage.Contents contents -> drawContents(graphics);
			case HandbookPage.Chapter chapter -> drawChapter(graphics, chapter);
			case HandbookPage.Appendix appendix -> drawAppendix(graphics);
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
		PaperDraw.stamp(graphics, font, tr("slip.stamp"), centerX(), bottom() - 28, 6, T.stampColor());
	}

	private void drawLetter(GuiGraphicsExtractor graphics) {
		int y = PaperDraw.centered(graphics, font, tr("letter.heading"), centerX(), paperTop + TOP_MARGIN, 1.25f, T.inkColor()) + PARAGRAPH_GAP;
		y = PaperDraw.wrapped(graphics, font, tr("letter.body.1"), textLeft, y, textWidth, T.inkColor()) + PARAGRAPH_GAP;
		y = PaperDraw.wrapped(graphics, font, tr("letter.body.2"), textLeft, y, textWidth, T.inkColor()) + PARAGRAPH_GAP;
		PaperDraw.centered(graphics, font, tr("letter.signature"), textLeft + textWidth * 3 / 4, y + PARAGRAPH_GAP, 1.5f, T.inkColor());
		margin(graphics, "deepcharter.handbook.letter.margin", paperTop + CONTENT_TOP * 2);
	}

	private void drawContents(GuiGraphicsExtractor graphics) {
		int y = PaperDraw.centered(graphics, font, tr("contents.heading"), centerX(), paperTop + TOP_MARGIN, 1.25f, T.inkColor()) + PARAGRAPH_GAP;
		for (int index = 0; index < pages.size(); index++) {
			if (!(pages.get(index) instanceof HandbookPage.Chapter chapter)) {
				continue;
			}
			if (y + font.lineHeight > bottom()) {
				break;
			}
			if (chapter.visibility() == HandbookVisibility.CLASSIFIED) {
				y = PaperDraw.redacted(graphics, font, I18n.get("deepcharter.handbook.contents.entry.classified", chapter.number()),
						textLeft, y, textWidth, T.inkColor());
				continue;
			}
			Component entry = tr("contents.entry", chapter.number(), chapter.chapter().title());
			Component status = chapter.isComplete() ? tr("contents.done") : chapter.visibility() == HandbookVisibility.FULL && !isRead.test(chapter.id()) ? tr("contents.new") : null;
			int statusWidth = status == null ? 0 : font.width(status) + 4;
			int lineTop = y;
			y = PaperDraw.wrapped(graphics, font, entry, textLeft, y, textWidth - statusWidth, T.inkColor());
			if (status != null) {
				graphics.text(font, PaperDraw.ink(status, T.stampColor()), textLeft + textWidth - statusWidth + 4, lineTop, OPAQUE | T.stampColor(), false);
			}
			hits.add(new Hit(textLeft, lineTop - 1, textLeft + textWidth, y + 1, index));
			y += 3;
		}
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
			margin(graphics, "deepcharter.handbook.chapter." + chapter.id().getPath() + ".margin", paperTop + CONTENT_TOP * 2);
		}
		if (chapter.visibility() == HandbookVisibility.PREVIEW) {
			PaperDraw.stamp(graphics, font, tr("chapter.stamp.coming"), centerX(), bottom() - 28, 8, T.stampColor());
		} else if (chapter.isComplete()) {
			PaperDraw.stamp(graphics, font, tr("chapter.stamp.complete"), centerX(), bottom() - 28, -10, T.stampColor());
		}
	}

	private void drawAppendix(GuiGraphicsExtractor graphics) {
		int y = PaperDraw.centered(graphics, font, tr("appendix.heading"), centerX(), paperTop + TOP_MARGIN, 1.25f, T.inkColor()) + PARAGRAPH_GAP;
		y = PaperDraw.wrapped(graphics, font, tr("appendix.restricted"), textLeft, y, textWidth, T.inkColor()) + PARAGRAPH_GAP;
		graphics.fill(textLeft, y, textLeft + textWidth, y + 1, OPAQUE | T.inkColor());
		y += PARAGRAPH_GAP;
		y = PaperDraw.wrapped(graphics, font, tr("appendix.title"), textLeft, y, textWidth, T.faintInkColor()) + 2;
		for (int line = 1; line <= 3 && y + font.lineHeight < bottom(); line++) {
			y = PaperDraw.redacted(graphics, font, I18n.get("deepcharter.handbook.appendix.line." + line), textLeft, y, textWidth, T.inkColor());
		}
		PaperDraw.stamp(graphics, font, tr("appendix.stamp"), centerX(), bottom() - 18, -9, T.stampColor());
	}

	private void drawNotes(GuiGraphicsExtractor graphics) {
		int y = PaperDraw.centered(graphics, font, tr("notes.heading"), centerX(), paperTop + TOP_MARGIN, 1.25f, T.inkColor()) + PARAGRAPH_GAP;
		if (notes.isEmpty()) {
			y = PaperDraw.centered(graphics, font, tr("notes.empty"), centerX(), y + PARAGRAPH_GAP * 3, 1, T.inkColor()) + 2;
			PaperDraw.wrapped(graphics, font, tr("notes.hint"), textLeft, y, textWidth, T.faintInkColor());
			return;
		}
		for (Component note : notes) {
			y = PaperDraw.wrapped(graphics, font, note, textLeft, y, textWidth, T.marginInkColor()) + PARAGRAPH_GAP;
		}
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
