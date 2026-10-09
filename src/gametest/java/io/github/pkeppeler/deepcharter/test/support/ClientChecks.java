package io.github.pkeppeler.deepcharter.test.support;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

/**
 * The assertion and the screen-layout checks that client GameTests share. A layout check returns the problem it found, or an
 * empty string when there is none, so a test can name the case it was running in the failure. The layout checks read the
 * client's font, so call them on the client thread.
 */
public final class ClientChecks {
	private ClientChecks() {
	}

	/** @throws AssertionError with {@code message} when {@code condition} is false; the message says what was expected and what the client had */
	public static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	/** The buttons among the screen's children, in child order. */
	public static List<Button> buttons(Screen screen) {
		return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
	}

	/** Whether the two boxes share any area; boxes that only touch do not overlap. */
	public static boolean overlap(int left, int top, int right, int bottom, int otherLeft, int otherTop, int otherRight, int otherBottom) {
		return left < otherRight && otherLeft < right && top < otherBottom && otherTop < bottom;
	}

	/** The problem when the button is not inside the screen. */
	public static String buttonLeavesScreen(Screen screen, Button button) {
		if (button.getX() < 0 || button.getY() < 0 || button.getRight() > screen.width || button.getBottom() > screen.height) {
			return "'" + button.getMessage().getString() + "' leaves the " + screen.width + " by " + screen.height + " screen";
		}
		return "";
	}

	/** The problem when the button's label is wider than the button. */
	public static String labelClipped(Button button) {
		String label = button.getMessage().getString();
		int textWidth = Minecraft.getInstance().font.width(label);
		if (textWidth > button.getWidth()) {
			return "'" + label + "' is " + textWidth + " wide in a button " + button.getWidth() + " wide";
		}
		return "";
	}

	/** The problem when the button overlaps another of {@code buttons}. */
	public static String buttonOverlaps(Button button, List<Button> buttons) {
		for (Button other : buttons) {
			if (other != button && overlap(button.getX(), button.getY(), button.getRight(), button.getBottom(),
					other.getX(), other.getY(), other.getRight(), other.getBottom())) {
				return "'" + button.getMessage().getString() + "' overlaps '" + other.getMessage().getString() + "'";
			}
		}
		return "";
	}

	/** The problem when the text line, drawn at {@code x}, {@code y}, is not inside the screen. {@code kind} names the line in the message. */
	public static String textLeavesScreen(Screen screen, String kind, int x, int y, String text) {
		int right = x + Minecraft.getInstance().font.width(text);
		int bottom = y + Minecraft.getInstance().font.lineHeight;
		if (x < 0 || y < 0 || right > screen.width || bottom > screen.height) {
			return kind + " '" + text + "' leaves the " + screen.width + " by " + screen.height + " screen";
		}
		return "";
	}

	/** The problem when the text line, drawn at {@code x}, {@code y}, is under one of {@code buttons}. {@code kind} names the line in the message. */
	public static String textUnderButton(String kind, int x, int y, String text, List<Button> buttons) {
		int right = x + Minecraft.getInstance().font.width(text);
		int bottom = y + Minecraft.getInstance().font.lineHeight;
		for (Button button : buttons) {
			if (overlap(x, y, right, bottom, button.getX(), button.getY(), button.getRight(), button.getBottom())) {
				return kind + " '" + text + "' is under '" + button.getMessage().getString() + "'";
			}
		}
		return "";
	}
}
