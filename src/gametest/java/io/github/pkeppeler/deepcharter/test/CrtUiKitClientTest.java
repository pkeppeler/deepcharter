package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import io.github.pkeppeler.deepcharter.client.ui.CrtDemoScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;

/**
 * Client GameTest for #53: the typewriter timing and hook, then a real screen with a button and a text field.
 * The timing is pure logic, so it is checked first with explicit time steps and no game running.
 */
public class CrtUiKitClientTest implements FabricClientGameTest {
	private static final double RATE = 20;
	private static final String TEXT = "HELLO CHARTER";

	@Override
	public void runTest(ClientGameTestContext context) {
		revealsAtConfiguredRate();
		hookFiresOncePerLetterInOrder();
		hookFiresInOrderWhenOneStepRevealsMany();
		skipRevealsTheRestAndFiresTheHook();
		defaultRateIsTheTuningRate();
		screenButtonAndField(context);
	}

	private static void revealsAtConfiguredRate() {
		Typewriter writer = new Typewriter(TEXT, RATE, (index, letter) -> { });
		check(writer.revealed() == 0, "nothing is revealed before time passes");
		writer.advance(0.24);
		check(writer.revealed() == 4, "0.24 s at 20 letters/s reveals 4 letters, got " + writer.revealed());
		check(writer.visible().equals("HELL"), "visible text is the prefix, got '" + writer.visible() + "'");
		writer.advance(0.02);
		check(writer.revealed() == 5, "partial time carries over: 0.26 s reveals 5 letters, got " + writer.revealed());
		for (int i = 0; i < 100; i++) {
			writer.advance(0.05);
		}
		check(writer.done() && writer.revealed() == TEXT.length(), "reveal stops at the full text");
	}

	private static void hookFiresOncePerLetterInOrder() {
		List<String> fired = new ArrayList<>();
		Typewriter writer = new Typewriter(TEXT, RATE, (index, letter) -> fired.add(index + ":" + letter));
		int steps = 0;
		while (!writer.done() && steps++ < 1000) {
			writer.advance(0.05);
		}
		check(fired.size() == TEXT.length(), "hook fired " + fired.size() + " times for " + TEXT.length() + " letters");
		for (int i = 0; i < TEXT.length(); i++) {
			check(fired.get(i).equals(i + ":" + TEXT.charAt(i)), "hook " + i + " was '" + fired.get(i) + "'");
		}
		writer.advance(5);
		check(fired.size() == TEXT.length(), "hook does not fire after the text is done");
	}

	private static void hookFiresInOrderWhenOneStepRevealsMany() {
		List<Integer> indexes = new ArrayList<>();
		Typewriter writer = new Typewriter(TEXT, RATE, (index, letter) -> indexes.add(index));
		writer.advance(0.5);
		check(indexes.equals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)), "one 0.5 s step fires hooks 0..9 in order, got " + indexes);
	}

	private static void skipRevealsTheRestAndFiresTheHook() {
		List<Integer> indexes = new ArrayList<>();
		Typewriter writer = new Typewriter(TEXT, RATE, (index, letter) -> indexes.add(index));
		writer.advance(0.1);
		writer.skip();
		check(writer.done() && writer.visible().equals(TEXT), "skip reveals the whole text");
		check(indexes.size() == TEXT.length(), "skip still fires the hook once per letter, got " + indexes.size());
	}

	private static void defaultRateIsTheTuningRate() {
		Typewriter writer = new Typewriter(TEXT, (index, letter) -> { });
		writer.advance(0.1);
		int expected = (int) Math.min(TEXT.length(), 0.1 * CrtTuning.DEFAULT.lettersPerSecond());
		check(writer.revealed() == expected, "0.1 s reveals the tuned rate, got " + writer.revealed() + " expected " + expected);
	}

	private static void screenButtonAndField(ClientGameTestContext context) {
		context.setScreen(CrtDemoScreen::new);
		context.waitForScreen(CrtDemoScreen.class);
		context.waitTicks(5);
		CrtDemoScreen screen = context.computeOnClient(client -> (CrtDemoScreen) client.gui.screen());
		check(screen.presses() == 0, "no presses before the click");
		context.runOnClient(client -> screen.setFocused(screen.acknowledge()));
		context.runOnClient(client -> screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0)));
		context.waitTicks(2);
		check(screen.presses() == 1, "pressing Enter on the focused CRT button runs its action once, got " + screen.presses());
		context.runOnClient(client -> screen.setFocused(screen.field()));
		context.runOnClient(client -> "ABC".chars().forEach(c -> screen.charTyped(new CharacterEvent(c))));
		context.waitTicks(2);
		check(screen.field().getValue().equals("ABC"), "the text field takes typed text");
		check(screen.lastTyped().equals("ABC"), "the field responder saw the text, got '" + screen.lastTyped() + "'");
		context.waitTicks(40);
		check(screen.typewriter().revealed() > 0, "the screen advanced its typewriter on ticks");
		context.setScreen(() -> null);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
