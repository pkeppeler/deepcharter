package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.client.pod.GeoModel;
import io.github.pkeppeler.deepcharter.client.pod.PodLook;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Server GameTests for #243 (ADR 0040): the pod look files that a resource pack replaces. The tier map is data, not code, and the
 * user's pick (the tricone for the stock drill, then stacked rings, a fluted auger and the cluster) is pinned here with literal
 * values, so a change to it fails a test and is a decision, not an accident.
 */
public class PodLookTest {
	/** A look of a model with one plain cutter. */
	private static final String PLAIN = """
			{"model": "deepcharter:pod/plain", "texture": "deepcharter:textures/entity/pod/plain.png",
			 "wreck": {"texture": "deepcharter:textures/entity/pod/plain_wreck.png"}}""";

	private static final String WITH_CUTTERS = """
			{"model": "deepcharter:pod/mole", "texture": "deepcharter:textures/entity/pod/mole.png",
			 "cutters": {"0": "tricone", "2": "stacked", "3": "fluted"},
			 "wreck": {"texture": "deepcharter:textures/entity/pod/mole_wreck.png", "glow": "always", "hide": ["rotor"]}}""";

	/** The user's pick (issue 243), remapped so that every early upgrade shows: the tricone is the stock drill (T0), then the stacked rings (T1), the auger (T2, the Mole's cap) and the cluster (T3, the Prospector's cap, to T6). */
	@GameTest
	public void theTierMapIsTheUsersPick(GameTestHelper helper) throws IOException {
		for (Chassis chassis : Chassis.all()) {
			PodLook look = PodGeoModelTest.look(helper, chassis);
			Map<Integer, String> expected = Map.of(0, "tricone", 1, "stacked", 2, "fluted", 3, "cluster", 4, "cluster", 5, "cluster", 6, "cluster");
			for (Map.Entry<Integer, String> entry : expected.entrySet()) {
				String shown = look.cutterFor(entry.getKey());
				if (!shown.equals(entry.getValue())) {
					throw helper.assertionException(Component.literal(chassis.id() + "'s drill tier " + entry.getKey() + " should show the " + entry.getValue() + ", shows the " + shown));
				}
			}
			require(helper, expected.size() == ComponentTrack.DRILL.maxTier() + 1, "the pin should cover every drill tier, 0 to " + ComponentTrack.DRILL.maxTier());
		}
		helper.succeed();
	}

	@GameTest
	public void aTierShowsTheCutterOfTheHighestEntryAtOrBelowIt(GameTestHelper helper) {
		PodLook look = PodLook.parse("test", new StringReader(WITH_CUTTERS));
		require(helper, look.cutterFor(0).equals("tricone"), "tier 0 shows the stock cutter");
		require(helper, look.cutterFor(1).equals("tricone"), "tier 1 shows the cutter of tier 0, the highest entry at or below it");
		require(helper, look.cutterFor(2).equals("stacked"), "tier 2 shows the stacked cutter");
		require(helper, look.cutterFor(6).equals("fluted"), "tier 6 shows the cutter of tier 3, the highest entry below it");
		helper.succeed();
	}

	@GameTest
	public void aLookNamesItsPaintMaskOrHasNone(GameTestHelper helper) {
		PodLook painted = PodLook.parse("test", new StringReader(PLAIN.replace("\"wreck\"", "\"paint\": \"deepcharter:textures/entity/pod/plain_paint.png\", \"wreck\"")));
		require(helper, painted.paintMask().equals(Optional.of(Identifier.parse("deepcharter:textures/entity/pod/plain_paint.png"))), "the paint mask, got " + painted.paintMask());
		require(helper, PodLook.parse("test", new StringReader(PLAIN)).paintMask().isEmpty(), "a look with no paint key is not painted");
		String bad = failure(helper, () -> PodLook.parse("test", new StringReader(PLAIN.replace("\"wreck\"", "\"paint\": \"deepcharter:pod/plain\", \"wreck\""))));
		require(helper, bad.contains("paint") && bad.contains("not a texture path"), "a paint mask that is no texture path is refused and says so, got " + bad);
		helper.succeed();
	}

	@GameTest
	public void aLookReadsItsVariants(GameTestHelper helper) {
		PodLook look = PodLook.parse("test", new StringReader(WITH_CUTTERS));
		require(helper, look.intact().glow() == PodLook.Glow.LIT, "the intact pod glows while lit by default");
		require(helper, look.wreck().glow() == PodLook.Glow.ALWAYS && look.wreck().hide().equals(List.of("rotor")), "the wreck keeps a lamp lit and hides the rotor");
		require(helper, look.intact().glowmask().equals(Identifier.parse("deepcharter:textures/entity/pod/mole_glowmask.png")),
				"the glowmask is the texture's name and _glowmask, got " + look.intact().glowmask());
		require(helper, look.modelFile().equals(Identifier.parse("deepcharter:geckolib/models/pod/mole.geo.json")), "the model file, got " + look.modelFile());
		PodLook plain = PodLook.parse("test", new StringReader(PLAIN));
		require(helper, plain.cutters().isEmpty() && plain.wreck().glow() == PodLook.Glow.NEVER, "a plain look has no cutters, and its wreck never glows");
		helper.succeed();
	}

	@GameTest
	public void aBadLookFailsAndSaysWhy(GameTestHelper helper) {
		Map<String, String> broken = Map.ofEntries(
				Map.entry(WITH_CUTTERS.substring(0, 20), "not valid JSON"),
				Map.entry(WITH_CUTTERS.replace("\"0\": \"tricone\", ", ""), "an entry for tier 0, the stock drill"),
				Map.entry(WITH_CUTTERS.replace("\"2\"", "\"two\""), "'two' is not a drill tier"),
				Map.entry(WITH_CUTTERS.replace("\"stacked\"", "7"), "the cutter of tier 2 is not a name"),
				Map.entry(WITH_CUTTERS.replace("\"glow\": \"always\"", "\"glow\": \"sometimes\""), "glow 'sometimes' is none of lit, always, never"),
				Map.entry(WITH_CUTTERS.replace("\"cutters\"", "\"cutter\""), "the key 'cutter'"),
				Map.entry(WITH_CUTTERS.replace("pod/mole.png", "pod/mole.jpg"), "is not a texture path"),
				Map.entry(WITH_CUTTERS.replace("\"wreck\"", "\"wrecked\""), "the key 'wrecked'"),
				Map.entry("{\"model\": \"deepcharter:pod/plain\", \"texture\": \"deepcharter:textures/entity/pod/plain.png\"}", "wreck is missing or not an object"));
		for (Map.Entry<String, String> entry : broken.entrySet()) {
			String message = failure(helper, () -> PodLook.parse("broken.json", new StringReader(entry.getKey())));
			require(helper, message.contains(entry.getValue()) && message.contains("broken.json"), "expected '" + entry.getValue() + "' in: " + message);
		}
		helper.succeed();
	}

	/** A look that names a cutter its model does not hold, a bone it has not, or no map for a model of several cutters, fails and says which. */
	@GameTest
	public void aLookThatDoesNotFitItsModelFails(GameTestHelper helper) throws IOException {
		GeoModel mole = PodGeoModelTest.read(helper, PodGeoModelTest.look(helper, Chassis.MOLE).modelFile());
		String unknownCutter = failure(helper, () -> PodLook.parse("look", new StringReader(WITH_CUTTERS.replace("fluted", "flute"))).check(mole));
		require(helper, unknownCutter.contains("maps drill tier 3 to the cutter 'flute'"), unknownCutter);
		String unknownBone = failure(helper, () -> PodLook.parse("look", new StringReader(WITH_CUTTERS.replace("rotor", "propeller"))).check(mole));
		require(helper, unknownBone.contains("hides 'propeller'"), unknownBone);
		String noMap = failure(helper, () -> PodLook.parse("look", new StringReader(PLAIN)).check(mole));
		require(helper, noMap.contains("has no cutters map"), noMap);
		helper.succeed();
	}

	private static String failure(GameTestHelper helper, Runnable action) {
		try {
			action.run();
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
		throw helper.assertionException(Component.literal("expected the look to fail"));
	}

	private static void require(GameTestHelper helper, boolean condition, String message) {
		if (!condition) {
			throw helper.assertionException(Component.literal(message));
		}
	}
}
