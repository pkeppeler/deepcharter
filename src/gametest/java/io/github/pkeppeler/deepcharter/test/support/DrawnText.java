package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import org.joml.Vector2f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import io.github.pkeppeler.deepcharter.test.support.ClientChecks.Box;

/**
 * The text a screen draws itself, with the box each string covers (#411). {@link #of} renders a screen once through a
 * {@link GuiGraphicsExtractor} subclass that records the string instead of drawing it, so a layout test can hold drawn text to the same
 * rules as a widget: inside the screen, and not under a button.
 *
 * <p>Text drawn while a widget renders (a button's label, an order row's two lines) is the widget's own, so its box is the widget's
 * and it is left out: a widget is whatever {@link Renderable} that is not a {@link Screen} on the stack when the string is drawn. The glow
 * of {@code CrtDraw.glowText} (four copies one pixel out, then the text) counts as one string, the last copy. Text drawn through a
 * scissor is cut to it, and a string wholly outside the scissor is dropped. {@code textWithWordWrap} and {@code textWithBackdrop} are
 * not recorded; the mod draws no text with either.
 */
public final class DrawnText {
	private DrawnText() {
	}

	/** A string the screen drew: its text, and the box it covers on the screen in GUI pixels. */
	public record Drawn(String text, Box box) {
		/** The text cut to 30 characters, for a failure message. */
		public String shortText() {
			return text.length() <= 30 ? text : text.substring(0, 30) + "...";
		}
	}

	/** Renders {@code screen} into a recorder and returns the strings it drew outside any widget. Client thread. */
	public static List<Drawn> of(Screen screen) {
		Minecraft client = Minecraft.getInstance();
		Recorder recorder = new Recorder(client);
		screen.extractRenderStateWithTooltipAndSubtitles(recorder, -1, -1, 0f);
		return List.copyOf(recorder.drawn);
	}

	/** A drawn string that leaves the screen, as "text 'EINSTEINIUM $2000' at 300,10 100 x 9 leaves the 427 by 240 screen: 5 px past the right edge". */
	public static Optional<String> leavesScreen(Screen screen, Drawn drawn) {
		return ClientChecks.boxLeavesScreen(screen, "text '" + drawn.shortText() + "'", drawn.box());
	}

	/** A drawn string that overlaps an interactive widget, with the overlap in pixels (width by height). */
	public static Optional<String> underWidget(Drawn drawn, List<? extends AbstractWidget> widgets) {
		for (AbstractWidget widget : widgets) {
			Box box = Box.of(widget);
			if (ClientChecks.overlaps(drawn.box(), box)) {
				int across = Math.min(drawn.box().right(), box.right()) - Math.max(drawn.box().x(), box.x());
				int down = Math.min(drawn.box().bottom(), box.bottom()) - Math.max(drawn.box().y(), box.y());
				return Optional.of("text '" + drawn.shortText() + "' at " + drawn.box().x() + "," + drawn.box().y() + " " + drawn.box().width() + " x "
						+ drawn.box().height() + " overlaps " + ClientChecks.describe(widget) + " by " + across + " x " + down + " px");
			}
		}
		return Optional.empty();
	}

	private static final class Recorder extends GuiGraphicsExtractor {
		/** Copies of one string up to this many pixels apart are one string with a glow. */
		private static final int GLOW_REACH = 2;
		private static final StackWalker STACK = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

		private final List<Drawn> drawn = new ArrayList<>();
		private final Deque<Box> scissors = new ArrayDeque<>();
		private final Vector2f corner = new Vector2f();

		Recorder(Minecraft client) {
			super(client, new GuiRenderState(), -1, -1);
		}

		@Override
		public void enableScissor(int x0, int y0, int x1, int y1) {
			super.enableScissor(x0, y0, x1, y1);
			// Vanilla pushes the scissor transformed by the current pose (ScreenRectangle.transformAxisAligned), so the text boxes and the scissor are in the same space.
			Box now = transformed(x0, y0, x1 - x0, y1 - y0);
			scissors.push(scissors.isEmpty() ? now : intersection(scissors.peek(), now));
		}

		@Override
		public void disableScissor() {
			super.disableScissor();
			scissors.pop();
		}

		@Override
		public void text(Font font, String text, int x, int y, int color) {
			record(text, font.width(text), font, x, y);
		}

		@Override
		public void text(Font font, String text, int x, int y, int color, boolean shadow) {
			record(text, font.width(text), font, x, y);
		}

		@Override
		public void text(Font font, FormattedCharSequence text, int x, int y, int color) {
			record(string(text), font.width(text), font, x, y);
		}

		@Override
		public void text(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
			record(string(text), font.width(text), font, x, y);
		}

		@Override
		public void text(Font font, Component text, int x, int y, int color) {
			record(text.getString(), font.width(text), font, x, y);
		}

		@Override
		public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
			record(text.getString(), font.width(text), font, x, y);
		}

		@Override
		public void centeredText(Font font, String text, int x, int y, int color) {
			record(text, font.width(text), font, x - font.width(text) / 2, y);
		}

		@Override
		public void centeredText(Font font, Component text, int x, int y, int color) {
			record(text.getString(), font.width(text), font, x - font.width(text) / 2, y);
		}

		@Override
		public void centeredText(Font font, FormattedCharSequence text, int x, int y, int color) {
			record(string(text), font.width(text), font, x - font.width(text) / 2, y);
		}

		private static String string(FormattedCharSequence text) {
			StringBuilder out = new StringBuilder();
			text.accept((index, style, codePoint) -> {
				out.appendCodePoint(codePoint);
				return true;
			});
			return out.toString();
		}

		private void record(String text, int width, Font font, int x, int y) {
			if (text.isBlank() || insideWidget()) {
				return;
			}
			Box box = transformed(x, y, width, font.lineHeight);
			if (!scissors.isEmpty()) {
				box = intersection(scissors.peek(), box);
				if (box.width() <= 0 || box.height() <= 0) {
					return;
				}
			}
			Drawn last = drawn.isEmpty() ? null : drawn.getLast();
			if (last != null && last.text().equals(text) && Math.abs(last.box().x() - box.x()) <= GLOW_REACH && Math.abs(last.box().y() - box.y()) <= GLOW_REACH) {
				drawn.set(drawn.size() - 1, new Drawn(text, box));
			} else {
				drawn.add(new Drawn(text, box));
			}
		}

		/** The box of a string at {@code x,y} under the current pose, so a translated or scaled string is where it lands. */
		private Box transformed(int x, int y, int width, int height) {
			float left = Float.MAX_VALUE;
			float top = Float.MAX_VALUE;
			float right = -Float.MAX_VALUE;
			float bottom = -Float.MAX_VALUE;
			for (int[] at : new int[][] {{x, y}, {x + width, y}, {x, y + height}, {x + width, y + height}}) {
				pose().transformPosition(at[0], at[1], corner);
				left = Math.min(left, corner.x);
				top = Math.min(top, corner.y);
				right = Math.max(right, corner.x);
				bottom = Math.max(bottom, corner.y);
			}
			int boxLeft = (int) Math.floor(left);
			int boxTop = (int) Math.floor(top);
			return new Box(boxLeft, boxTop, (int) Math.ceil(right) - boxLeft, (int) Math.ceil(bottom) - boxTop);
		}

		private static Box intersection(Box a, Box b) {
			int left = Math.max(a.x(), b.x());
			int top = Math.max(a.y(), b.y());
			return new Box(left, top, Math.min(a.right(), b.right()) - left, Math.min(a.bottom(), b.bottom()) - top);
		}

		private static boolean insideWidget() {
			return STACK.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
					.anyMatch(type -> Renderable.class.isAssignableFrom(type) && !Screen.class.isAssignableFrom(type)));
		}
	}
}
