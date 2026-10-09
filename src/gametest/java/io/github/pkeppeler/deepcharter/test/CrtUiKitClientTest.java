package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDemoScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * Client GameTest for #53: the typewriter timing and hook, then a real screen with a button and a text field.
 * The timing is pure logic, so it is checked first with explicit time steps and no game running.
 */
public class CrtUiKitClientTest implements FabricClientGameTest {
	/** Left button in the SDL numbering this Minecraft uses. */
	private static final int LEFT_MOUSE = 1;
	private static final double RATE = 20;
	private static final String TEXT = "HELLO CHARTER";

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		revealsAtConfiguredRate();
		hookFiresOncePerLetterInOrder();
		hookFiresInOrderWhenOneStepRevealsMany();
		skipRevealsTheRestWithoutFiringTheHook();
		stepsOfOneTwentiethAtRate40GiveExactlyTwoLettersEach();
		defaultRateIsTheTuningRate();
		screenTicksRevealExactly(context);
		screenButtonAndField(context);
		resizeKeepsTheReveal(context);
	}

	private static void revealsAtConfiguredRate() {
		Typewriter writer = new Typewriter(TEXT, RATE, (index, letter) -> { });
		require(writer.revealed() == 0, "nothing is revealed before time passes");
		writer.advance(0.24);
		require(writer.revealed() == 4, "0.24 s at 20 letters/s reveals 4 letters, got " + writer.revealed());
		require(writer.visible().equals("HELL"), "visible text is the prefix, got '" + writer.visible() + "'");
		writer.advance(0.02);
		require(writer.revealed() == 5, "partial time carries over: 0.26 s reveals 5 letters, got " + writer.revealed());
		for (int i = 0; i < 100; i++) {
			writer.advance(0.05);
		}
		require(writer.done() && writer.revealed() == TEXT.length(), "reveal stops at the full text");
	}

	private static void hookFiresOncePerLetterInOrder() {
		List<String> fired = new ArrayList<>();
		Typewriter writer = new Typewriter(TEXT, RATE, (index, letter) -> fired.add(index + ":" + letter));
		int steps = 0;
		while (!writer.done() && steps++ < 1000) {
			writer.advance(0.05);
		}
		require(fired.size() == TEXT.length(), "hook fired " + fired.size() + " times for " + TEXT.length() + " letters");
		for (int i = 0; i < TEXT.length(); i++) {
			require(fired.get(i).equals(i + ":" + TEXT.charAt(i)), "hook " + i + " was '" + fired.get(i) + "'");
		}
		writer.advance(5);
		require(fired.size() == TEXT.length(), "hook does not fire after the text is done");
	}

	private static void hookFiresInOrderWhenOneStepRevealsMany() {
		List<Integer> indexes = new ArrayList<>();
		Typewriter writer = new Typewriter(TEXT, RATE, (index, letter) -> indexes.add(index));
		writer.advance(0.5);
		require(indexes.equals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)), "one 0.5 s step fires hooks 0..9 in order, got " + indexes);
	}

	private static void skipRevealsTheRestWithoutFiringTheHook() {
		List<Integer> indexes = new ArrayList<>();
		Typewriter writer = new Typewriter(TEXT, RATE, (index, letter) -> indexes.add(index));
		writer.advance(0.1);
		writer.skip();
		require(writer.done() && writer.visible().equals(TEXT), "skip reveals the whole text");
		require(indexes.size() == 2, "skip fires no hook for the skipped letters, got " + indexes.size() + " calls in total");
	}

	/** 0.05 s added 8 times at 40 letters/s is just under 0.4 in floating point, which would hold a letter back. */
	private static void stepsOfOneTwentiethAtRate40GiveExactlyTwoLettersEach() {
		Typewriter writer = new Typewriter("X".repeat(400), 40, (index, letter) -> { });
		for (int n = 1; n <= 200; n++) {
			writer.advance(0.05);
			require(writer.revealed() == 2 * n, "after " + n + " steps expected " + 2 * n + " letters, got " + writer.revealed());
		}
	}

	private static void defaultRateIsTheTuningRate() {
		Typewriter writer = new Typewriter(TEXT, (index, letter) -> { });
		writer.advance(0.1);
		int expected = (int) Math.min(TEXT.length(), 0.1 * CrtTuning.current().lettersPerSecond());
		require(writer.revealed() == expected, "0.1 s reveals the tuned rate, got " + writer.revealed() + " expected " + expected);
	}

	/** A screen that is never shown, ticked by hand: exactly min(length, 2 * ticks) letters at 40 letters/s. */
	private static void screenTicksRevealExactly(ClientGameTestContext context) {
		context.runOnClient(client -> {
			CrtDemoScreen screen = new CrtDemoScreen();
			int length = screen.typewriter().text().length();
			require(length > 20, "the demo text is long enough to test");
			for (int tick = 1; tick <= 10; tick++) {
				screen.tick();
				require(screen.typewriter().revealed() == Math.min(length, 2 * tick),
						"after " + tick + " ticks expected " + 2 * tick + " letters, got " + screen.typewriter().revealed());
			}
		});
	}

	private static void screenButtonAndField(ClientGameTestContext context) {
		context.setScreen(CrtDemoScreen::new);
		ClientWait.screen(context, CrtDemoScreen.class);
		context.waitTicks(5);
		CrtDemoScreen screen = context.computeOnClient(client -> (CrtDemoScreen) client.gui.screen());
		require(screen.presses() == 0, "no presses before the click");
		context.clickScreenButton(CrtDemoScreen.BUTTON_LABEL);
		context.waitTicks(2);
		require(screen.presses() == 1, "clickScreenButton finds and presses the CRT button once, got " + screen.presses());
		context.runOnClient(client -> {
			CrtButton button = screen.acknowledge();
			MouseButtonEvent click = new MouseButtonEvent(button.getX() + button.getWidth() / 2.0,
					button.getY() + button.getHeight() / 2.0, new MouseButtonInfo(LEFT_MOUSE, 0));
			require(screen.mouseClicked(click, false), "the screen handles a click at the button centre");
			screen.mouseReleased(click);
		});
		context.waitTicks(2);
		require(screen.presses() == 2, "a click at the button centre presses it, got " + screen.presses());
		context.runOnClient(client -> screen.setFocused(screen.acknowledge()));
		context.runOnClient(client -> screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0)));
		require(screen.presses() == 3, "Enter on the focused button presses it, got " + screen.presses());
		context.runOnClient(client -> screen.setFocused(screen.field()));
		context.runOnClient(client -> "ABC".chars().forEach(c -> screen.charTyped(new CharacterEvent(c))));
		require(screen.field().getValue().equals("ABC"), "the text field takes typed text");
		require(screen.lastTyped().equals("ABC"), "the field responder saw the text, got '" + screen.lastTyped() + "'");
		context.setScreen(() -> null);
	}

	/** A resize runs init() again. The reveal must go on from where it was, and no letter may fire its hook twice. */
	private static void resizeKeepsTheReveal(ClientGameTestContext context) {
		context.setScreen(CrtDemoScreen::new);
		ClientWait.screen(context, CrtDemoScreen.class);
		CrtDemoScreen screen = context.computeOnClient(client -> (CrtDemoScreen) client.gui.screen());
		context.waitTicks(10);
		context.runOnClient(client -> {
			Typewriter writer = screen.typewriter();
			int before = writer.revealed();
			require(before > 0 && !writer.done(), "the reveal is under way before the resize, at " + before);
			screen.resize(screen.width + 20, screen.height + 10);
			require(screen.typewriter() == writer, "the resize keeps the same typewriter");
			require(writer.revealed() >= before, "the resize does not restart the reveal");
		});
		ClientWait.until(context, "the demo screen finished typing", client -> screen.typewriter().done(), client -> "typewriter text '" + screen.typewriter().text() + "'");
		List<Integer> calls = screen.hookCalls();
		int length = screen.typewriter().text().length();
		require(calls.size() == length, "each letter fired its hook once, got " + calls.size() + " calls for " + length + " letters");
		for (int i = 0; i < calls.size(); i++) {
			require(calls.get(i) == i, "hooks fire in order, call " + i + " was for letter " + calls.get(i));
		}
		require(screen.getNarrationMessage().getString().contains(screen.typewriter().text()),
				"the narration reads the full typewriter text");
		context.setScreen(() -> null);
	}
}
