package io.github.pkeppeler.deepcharter.client.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import io.github.pkeppeler.deepcharter.client.theme.PanelLook;

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
 * <p>With the machine panel on ({@link PanelLook}, a pack's choice) the screen sits in a frame: the backdrop shrinks to the CRT glass, the
 * frame and its decals are drawn behind the content and the glass sprite and scanlines over it, and a screen puts its content inside
 * {@link #contentLeft}, {@link #contentTop}, {@link #contentRight} and {@link #contentBottom}, which are the panel's insets or, without
 * the panel, the screen's own margin.
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

	/** The x where content starts: the panel's left inset, or {@code margin} without the panel. */
	protected final int contentLeft(int margin) {
		PanelLook panel = PanelLook.current();
		return panel.enabled() ? panel.content().left() : margin;
	}

	/** The y where content starts: the panel's top inset, or {@code margin} without the panel. */
	protected final int contentTop(int margin) {
		PanelLook panel = PanelLook.current();
		return panel.enabled() ? panel.content().top() : margin;
	}

	/** The x where content ends: the screen's width less the panel's right inset, or less {@code margin} without the panel. */
	protected final int contentRight(int margin) {
		PanelLook panel = PanelLook.current();
		return width - (panel.enabled() ? panel.content().right() : margin);
	}

	/** The y where content ends: the screen's height less the panel's bottom inset, or less {@code margin} without the panel. */
	protected final int contentBottom(int margin) {
		PanelLook panel = PanelLook.current();
		return height - (panel.enabled() ? panel.content().bottom() : margin);
	}

	/** The width of the content area, which is where text wraps. */
	protected final int contentWidth(int margin) {
		return contentRight(margin) - contentLeft(margin);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		PanelLook panel = PanelLook.current();
		if (panel.enabled()) {
			CrtDraw.panelBackground(graphics, panel, width, height);
		} else {
			CrtDraw.backdrop(graphics, 0, 0, width, height);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		PanelLook panel = PanelLook.current();
		if (panel.enabled()) {
			// The glass and its scanlines go over the text and the phosphor only: the buttons are metal, and are drawn after them.
			CrtDraw.panelGlass(graphics, panel, width, height);
			super.extractRenderState(graphics, mouseX, mouseY, partialTick);
			return;
		}
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
			entry.lines = CrtText.wrap(font, writer.visible(), wrapWidth);
			entry.cachedRevealed = writer.revealed();
			entry.cachedWidth = wrapWidth;
		}
		int color = entry.color != null ? entry.color : tuning.phosphorColor();
		int lineY = y;
		int lastX = x;
		int lastY = y;
		for (String line : entry.lines) {
			CrtDraw.glowText(graphics, font, line, x, lineY, color);
			lastX = x + CrtText.width(font, line);
			lastY = lineY;
			lineY += font.lineHeight + tuning.lineSpacing();
		}
		boolean cursorOn = !writer.done() || (ticks / tuning.cursorBlinkTicks()) % 2 == 0;
		if (cursorOn) {
			graphics.fill(lastX + 1, lastY, lastX + CrtText.width(font, "W"), lastY + font.lineHeight - 1, color);
		}
		return Math.max(lineY, y + font.lineHeight + tuning.lineSpacing());
	}
}
