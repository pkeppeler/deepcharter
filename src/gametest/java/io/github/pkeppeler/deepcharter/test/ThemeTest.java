package io.github.pkeppeler.deepcharter.test;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

import io.github.pkeppeler.deepcharter.theme.ThemeData;
import io.github.pkeppeler.deepcharter.theme.ThemeData.Layer;

/** Server GameTests for #226: a theme area reads colours and numbers, layers merge key by key, and a bad value fails loud. */
public class ThemeTest {
	private static ThemeData area(Layer... layers) {
		return ThemeData.parse("crt", List.of(layers));
	}

	private static Layer layer(String source, String json) {
		return new Layer(source, json);
	}

	@GameTest
	public void colorsReadAsArgb(GameTestHelper helper) {
		ThemeData data = area(layer("base", """
				{ "six": "#7CFC9A", "eight": "#58000000", "lower": "#7cfc9a" }"""));
		expect(helper, "six digits are opaque", 0xFF7CFC9A, data.color("six"));
		expect(helper, "eight digits carry their alpha", 0x58000000, data.color("eight"));
		expect(helper, "hex digits are case-blind", 0xFF7CFC9A, data.color("lower"));
		helper.succeed();
	}

	@GameTest
	public void numbersReadAsIntegersAndDecimals(GameTestHelper helper) {
		ThemeData data = area(layer("base", """
				{ "padding": 6, "speed": 40.5, "whole": 40.0 }"""));
		expect(helper, "an integer", 6, data.integer("padding"));
		expect(helper, "a decimal", 40.5, data.decimal("speed"));
		expect(helper, "an integer is also a decimal", 6.0, data.decimal("padding"));
		expect(helper, "a whole decimal is an integer", 40, data.integer("whole"));
		helper.succeed();
	}

	@GameTest
	public void aLaterLayerReplacesOnlyTheKeysItNames(GameTestHelper helper) {
		ThemeData data = area(
				layer("mod", """
						{ "phosphor": "#7CFC9A", "dim": "#2E7A45", "padding": 6 }"""),
				layer("amber pack", """
						{ "phosphor": "#FFB000" }"""));
		expect(helper, "the pack's key wins", 0xFFFFB000, data.color("phosphor"));
		expect(helper, "a key the pack leaves out keeps the mod's value", 0xFF2E7A45, data.color("dim"));
		expect(helper, "so does a number", 6, data.integer("padding"));
		helper.succeed();
	}

	@GameTest
	public void aMissingKeyNamesTheAreaAndTheKey(GameTestHelper helper) {
		ThemeData data = area(layer("base", "{ \"a\": 1 }"));
		String message = failure(helper, () -> data.color("phosphor"));
		requireContains(helper, message, "crt");
		requireContains(helper, message, "phosphor");
		helper.succeed();
	}

	@GameTest
	public void aBadColorFailsAtParseAndNamesThePack(GameTestHelper helper) {
		for (String bad : List.of("\"7CFC9A\"", "\"#7CFC9\"", "\"#GGGGGG\"", "\"#7CFC9A00FF\"", "\"green\"")) {
			String message = failure(helper, () -> area(layer("broken pack", "{ \"phosphor\": " + bad + " }")));
			requireContains(helper, message, "broken pack");
			requireContains(helper, message, "phosphor");
		}
		helper.succeed();
	}

	@GameTest
	public void aValueOfAnotherTypeFailsAtParse(GameTestHelper helper) {
		for (String bad : List.of("true", "null", "[1]", "{ \"x\": 1 }")) {
			String message = failure(helper, () -> area(layer("broken pack", "{ \"padding\": " + bad + " }")));
			requireContains(helper, message, "padding");
		}
		failure(helper, () -> area(layer("broken pack", "[1, 2]")));
		failure(helper, () -> area(layer("broken pack", "{ not json")));
		helper.succeed();
	}

	@GameTest
	public void aNumberIsNotAColorAndAColorIsNotANumber(GameTestHelper helper) {
		ThemeData data = area(layer("base", "{ \"padding\": 6, \"phosphor\": \"#7CFC9A\" }"));
		failure(helper, () -> data.color("padding"));
		failure(helper, () -> data.integer("phosphor"));
		failure(helper, () -> data.decimal("phosphor"));
		helper.succeed();
	}

	@GameTest
	public void aFractionIsNotAnInteger(GameTestHelper helper) {
		ThemeData data = area(layer("base", "{ \"padding\": 6.5 }"));
		String message = failure(helper, () -> data.integer("padding"));
		requireContains(helper, message, "padding");
		helper.succeed();
	}

	@GameTest
	public void anIntegerBelowItsMinimumFails(GameTestHelper helper) {
		ThemeData data = area(layer("base", "{ \"flip\": 0, \"ok\": 1 }"));
		expect(helper, "at the minimum", 1, data.integer("ok", 1));
		String message = failure(helper, () -> data.integer("flip", 1));
		requireContains(helper, message, "flip");
		helper.succeed();
	}

	@GameTest
	public void keysNobodyReadAreReportedSoATypoShows(GameTestHelper helper) {
		ThemeData data = area(layer("base", "{ \"phosphor\": \"#7CFC9A\", \"phospor\": \"#FFB000\", \"padding\": 6 }"));
		data.color("phosphor");
		data.integer("padding");
		expect(helper, "the typo is the only key left", Set.of("phospor"), data.unread());
		helper.succeed();
	}

	@GameTest
	public void noLayersIsAnEmptyAreaNotAnError(GameTestHelper helper) {
		ThemeData data = ThemeData.parse("crt", List.of());
		expect(helper, "no keys", Set.of(), data.unread());
		failure(helper, () -> data.color("phosphor"));
		helper.succeed();
	}

	private static void expect(GameTestHelper helper, String what, Object expected, Object actual) {
		if (!expected.equals(actual)) {
			throw helper.assertionException(Component.literal("%s: expected %s, got %s".formatted(what, expected, actual)));
		}
	}

	/** Runs {@code action}, which must throw, and returns the message of what it threw. */
	private static String failure(GameTestHelper helper, Supplier<?> action) {
		try {
			action.get();
		} catch (IllegalArgumentException | IllegalStateException e) {
			return String.valueOf(e.getMessage());
		}
		throw helper.assertionException(Component.literal("expected an IllegalArgumentException or IllegalStateException, none was thrown"));
	}

	private static void requireContains(GameTestHelper helper, String message, String part) {
		if (!message.contains(part)) {
			throw helper.assertionException(Component.literal("message '%s' should name '%s'".formatted(message, part)));
		}
	}
}
