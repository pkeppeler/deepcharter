package io.github.pkeppeler.deepcharter.client.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;

/**
 * Base of every retro CRT-terminal screen: a phosphor backdrop, scanlines over everything, and typewriter text.
 *
 * <p>Vanilla runs {@code init()} again on every resize, so a subclass must not make typewriters there. It makes
 * them in its constructor with {@link #typewriter}, which this screen then advances on every tick, and it adds
 * its widgets in {@link #layout()}. {@code init()} is final here and calls {@code layout()}; {@link #typewriter}
 * throws if called from it. Time runs in ticks, so the reveal speed is the same at any frame rate.
 *
 * <p>Override {@link #extractRenderState} and draw your own content BEFORE calling {@code super}: the base draws
 * the widgets and then the scanlines over everything, so content drawn before {@code super} gets the scanlines
 * and content drawn after it would sit on top of them.
 *
 * <p>Typewriter text is plain and single-coloured; the colour is chosen per typewriter. The screen narrates the
 * full text of every typewriter once, when it opens, as part of {@link #getNarrationMessage()}.
 */
public abstract class CrtScreen extends Screen {
	private static final double SECONDS_PER_TICK = 1.0 / 20.0;

	/** A typewriter, its colour, and its wrapped lines, kept until the revealed text or the width changes. */
	private static final class Entry {
		/** The caller's colour, or null for the theme's phosphor, read at each draw. */
		private final Integer color;
		private int cachedRevealed = -1;
		private int cachedWidth = -1;
		private List<String> lines = List.of();

		private Entry(Integer color) {
			this.color = color;
		}
	}

	private final Map<Typewriter, Entry> typewriters = new LinkedHashMap<>();
	private boolean inLayout;
	private int ticks;

	protected CrtScreen(Component title) {
		super(title);
	}

	/** Adds this screen's widgets. Called on open and again on every resize: make no typewriters here. */
	protected abstract void layout();

	@Override
	protected final void init() {
		inLayout = true;
		try {
			layout();
		} finally {
			inLayout = false;
		}
	}

	/** Starts typing {@code text} in the theme's phosphor colour, which a reload changes while the screen is open. The hook is where a feature plays its letter sound. */
	protected Typewriter typewriter(Component text, Typewriter.LetterHook hook) {
		return typewriter(text, null, hook);
	}

	/** Starts typing {@code text} in {@code color} (ARGB), or in the phosphor colour when it is null. Call from the constructor, never from {@link #layout()}. */
	protected Typewriter typewriter(Component text, Integer color, Typewriter.LetterHook hook) {
		if (inLayout) {
			throw new IllegalStateException("Typewriters made in layout() restart on every resize: make them in the constructor");
		}
		Typewriter writer = new Typewriter(text.getString(), hook);
		typewriters.put(writer, new Entry(color));
		return writer;
	}

	@Override
	public void tick() {
		super.tick();
		ticks++;
		for (Typewriter writer : typewriters.keySet()) {
			writer.advance(SECONDS_PER_TICK);
		}
	}

	@Override
	public Component getNarrationMessage() {
		StringJoiner joiner = new StringJoiner(". ");
		joiner.add(super.getNarrationMessage().getString());
		for (Typewriter writer : typewriters.keySet()) {
			joiner.add(writer.text());
		}
		return Component.literal(joiner.toString());
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		CrtDraw.backdrop(graphics, width, height);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		CrtDraw.scanlines(graphics, width, height);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/**
	 * Draws the revealed part of {@code writer}, wrapped to {@code wrapWidth}, with a block cursor that blinks after
	 * the last letter. Returns the y below the last line.
	 */
	protected int drawTypewriter(GuiGraphicsExtractor graphics, Typewriter writer, int x, int y, int wrapWidth) {
		Entry entry = typewriters.get(writer);
		if (entry == null) {
			throw new IllegalArgumentException("Not a typewriter of this screen: " + writer.text());
		}
		CrtTuning tuning = CrtTuning.current();
		Font font = this.font;
		if (entry.cachedRevealed != writer.revealed() || entry.cachedWidth != wrapWidth) {
			entry.lines = font.getSplitter().splitLines(FormattedText.of(writer.visible()), wrapWidth, Style.EMPTY)
					.stream().map(FormattedText::getString).toList();
			entry.cachedRevealed = writer.revealed();
			entry.cachedWidth = wrapWidth;
		}
		int color = entry.color != null ? entry.color : tuning.phosphorColor();
		int lineY = y;
		int lastX = x;
		int lastY = y;
		for (String line : entry.lines) {
			CrtDraw.glowText(graphics, font, line, x, lineY, color);
			lastX = x + font.width(line);
			lastY = lineY;
			lineY += font.lineHeight + tuning.lineSpacing();
		}
		boolean cursorOn = !writer.done() || (ticks / tuning.cursorBlinkTicks()) % 2 == 0;
		if (cursorOn) {
			graphics.fill(lastX + 1, lastY, lastX + font.width("W"), lastY + font.lineHeight - 1, color);
		}
		return Math.max(lineY, y + font.lineHeight + tuning.lineSpacing());
	}
}
