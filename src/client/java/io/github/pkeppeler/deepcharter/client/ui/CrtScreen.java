package io.github.pkeppeler.deepcharter.client.ui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

/**
 * Base of every retro CRT-terminal screen: a phosphor backdrop, scanlines over everything, and typewriter text.
 * Add widgets as for any screen, with {@link CrtButton} and {@link CrtTextField} for the CRT look.
 *
 * <p>A subclass creates text with {@link #typewriter}, which this screen advances on every tick, and draws it with
 * {@link #drawTypewriter}. Time runs in ticks, so the reveal speed is the same at any frame rate.
 */
public abstract class CrtScreen extends Screen {
	private static final double SECONDS_PER_TICK = 1.0 / 20.0;

	private final List<Typewriter> typewriters = new ArrayList<>();
	private int ticks;

	protected CrtScreen(Component title) {
		super(title);
	}

	/**
	 * Starts typing {@code text}. The hook is where a feature plays its letter sound.
	 */
	protected Typewriter typewriter(Component text, Typewriter.LetterHook hook) {
		Typewriter writer = new Typewriter(text.getString(), hook);
		typewriters.add(writer);
		return writer;
	}

	@Override
	public void tick() {
		super.tick();
		ticks++;
		for (Typewriter writer : typewriters) {
			writer.advance(SECONDS_PER_TICK);
		}
	}

	/** Ticks since the screen opened. */
	protected int ticks() {
		return ticks;
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
		CrtTuning tuning = CrtTuning.DEFAULT;
		Font font = this.font;
		String shown = writer.visible();
		List<FormattedCharSequence> lines = font.split(FormattedText.of(shown), wrapWidth);
		int lineY = y;
		int lastX = x;
		int lastY = y;
		for (FormattedCharSequence line : lines) {
			StringBuilder plain = new StringBuilder();
			line.accept((index, style, codePoint) -> {
				plain.appendCodePoint(codePoint);
				return true;
			});
			CrtDraw.glowText(graphics, font, plain.toString(), x, lineY, tuning.phosphorColor());
			lastX = x + font.width(plain.toString());
			lastY = lineY;
			lineY += font.lineHeight + tuning.lineSpacing();
		}
		boolean cursorOn = !writer.done() || (ticks / tuning.cursorBlinkTicks()) % 2 == 0;
		if (cursorOn) {
			graphics.fill(lastX + 1, lastY, lastX + 1 + font.width("W") - 1, lastY + font.lineHeight - 1, tuning.phosphorColor());
		}
		return Math.max(lineY, y + font.lineHeight + tuning.lineSpacing());
	}
}
