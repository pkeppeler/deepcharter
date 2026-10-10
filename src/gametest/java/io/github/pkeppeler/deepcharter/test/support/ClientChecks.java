package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

/**
 * The assertion and the screen-layout checks that client GameTests share. A layout check returns the problem it found, so a
 * test can name the case it was running in the failure. The layout checks read the client's font, so call them on the client thread.
 */
public final class ClientChecks {
	private ClientChecks() {
	}

	/** A rectangle on the screen, in GUI pixels. */
	public record Box(int x, int y, int width, int height) {
		public static Box of(AbstractWidget widget) {
			return new Box(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight());
		}

		public static Box ofText(int x, int y, String text) {
			Minecraft client = Minecraft.getInstance();
			return new Box(x, y, client.font.width(text), client.font.lineHeight);
		}

		public int right() {
			return x + width;
		}

		public int bottom() {
			return y + height;
		}
	}

	public static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	public static void requireNone(String when, Optional<String> problem) {
		require(problem.isEmpty(), when + ": " + problem.orElse(""));
	}

	public static List<Button> buttons(Screen screen) {
		return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
	}

	public static Button button(Screen screen, String label) {
		return buttons(screen).stream().filter(button -> button.getMessage().getString().equals(label)).findFirst()
				.orElseThrow(() -> new AssertionError("no button '" + label + "' on the screen"));
	}

	public static String labelOf(Button button) {
		return "'" + button.getMessage().getString() + "'";
	}

	/** Boxes that only touch do not overlap. */
	public static boolean overlaps(Box box, Box other) {
		return box.x() < other.right() && other.x() < box.right() && box.y() < other.bottom() && other.y() < box.bottom();
	}

	public static Optional<String> buttonLeavesScreen(Screen screen, Button button) {
		Box box = Box.of(button);
		if (box.x() < 0 || box.y() < 0 || box.right() > screen.width || box.bottom() > screen.height) {
			return Optional.of(labelOf(button) + " leaves the " + screen.width + " by " + screen.height + " screen");
		}
		return Optional.empty();
	}

	public static Optional<String> labelClipped(Button button) {
		int textWidth = Minecraft.getInstance().font.width(button.getMessage().getString());
		if (textWidth > button.getWidth()) {
			return Optional.of(labelOf(button) + " is " + textWidth + " wide in a button " + button.getWidth() + " wide");
		}
		return Optional.empty();
	}

	public static Optional<String> buttonOverlaps(Button button, List<Button> buttons) {
		for (Button other : buttons) {
			if (other != button && overlaps(Box.of(button), Box.of(other))) {
				return Optional.of(labelOf(button) + " overlaps " + labelOf(other));
			}
		}
		return Optional.empty();
	}

	/** {@code kind} names the line in the message. */
	public static Optional<String> textLeavesScreen(Screen screen, String kind, int x, int y, String text) {
		Box box = Box.ofText(x, y, text);
		if (box.x() < 0 || box.y() < 0 || box.right() > screen.width || box.bottom() > screen.height) {
			return Optional.of(kind + " '" + text + "' leaves the " + screen.width + " by " + screen.height + " screen");
		}
		return Optional.empty();
	}

	/** {@code kind} names the line in the message. */
	public static Optional<String> textUnderButton(String kind, int x, int y, String text, List<Button> buttons) {
		Box box = Box.ofText(x, y, text);
		for (Button button : buttons) {
			if (overlaps(box, Box.of(button))) {
				return Optional.of(kind + " '" + text + "' is under " + labelOf(button));
			}
		}
		return Optional.empty();
	}

	/** A widget by what a failure message should call it: a button by its label, any other by its class and message. */
	public static String describe(AbstractWidget widget) {
		if (widget instanceof Button button) {
			return labelOf(button);
		}
		return widget.getClass().getSimpleName() + " '" + widget.getMessage().getString() + "'";
	}

	/** The visible widgets the screen holds, in the order it holds them. */
	public static List<AbstractWidget> widgets(Screen screen) {
		return screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
				.filter(widget -> widget.visible).toList();
	}

	/** How far {@code box} reaches past each edge of the screen, as "5 px past the right edge, 3 px past the bottom edge"; empty when it lies inside. */
	public static Optional<String> overflowOf(Screen screen, Box box) {
		List<String> edges = new ArrayList<>();
		if (box.x() < 0) {
			edges.add(-box.x() + " px past the left edge");
		}
		if (box.y() < 0) {
			edges.add(-box.y() + " px past the top edge");
		}
		if (box.right() > screen.width) {
			edges.add((box.right() - screen.width) + " px past the right edge");
		}
		if (box.bottom() > screen.height) {
			edges.add((box.bottom() - screen.height) + " px past the bottom edge");
		}
		return edges.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", edges));
	}

	/** {@code what} names the rectangle in the message; the overflow is in pixels past each edge. */
	public static Optional<String> boxLeavesScreen(Screen screen, String what, Box box) {
		return overflowOf(screen, box).map(overflow -> what + " at " + box.x() + "," + box.y() + " " + box.width() + " x " + box.height()
				+ " leaves the " + screen.width + " by " + screen.height + " screen: " + overflow);
	}

	public static Optional<String> widgetLeavesScreen(Screen screen, AbstractWidget widget) {
		return boxLeavesScreen(screen, describe(widget), Box.of(widget));
	}

	/** The overlap is in pixels, width by height. */
	public static Optional<String> widgetOverlaps(AbstractWidget widget, List<AbstractWidget> widgets) {
		Box box = Box.of(widget);
		for (AbstractWidget other : widgets) {
			Box otherBox = Box.of(other);
			if (other != widget && overlaps(box, otherBox)) {
				int across = Math.min(box.right(), otherBox.right()) - Math.max(box.x(), otherBox.x());
				int down = Math.min(box.bottom(), otherBox.bottom()) - Math.max(box.y(), otherBox.y());
				return Optional.of(describe(widget) + " overlaps " + describe(other) + " by " + across + " x " + down + " px");
			}
		}
		return Optional.empty();
	}
}
