package io.github.pkeppeler.deepcharter.test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

import io.github.pkeppeler.deepcharter.client.pod.BoneRole;
import io.github.pkeppeler.deepcharter.client.pod.GeoModel;
import io.github.pkeppeler.deepcharter.client.pod.PodLook;
import io.github.pkeppeler.deepcharter.pod.Chassis;

/**
 * Server GameTests for #334 and #243: every pod's Bedrock geometry parses with its rig, has the parts of a machine, holds the
 * cutters of its drill tiers, and each cutter keeps the bore rules at rest, with a hull that fits the pod's bore, and has its
 * textures; an unknown bone or a malformed file fails loud and says where.
 */
public class PodGeoModelTest {
	/** The smallest model that parses: a body, and a drill head on its mount. */
	private static final String VALID = """
			{"format_version": "1.12.0", "minecraft:geometry": [{
			  "description": {"identifier": "geometry.test", "texture_width": 64, "texture_height": 64},
			  "bones": [
			    {"name": "body", "pivot": [0, 0, 0], "cubes": [{"origin": [-4, 0, -4], "size": [8, 8, 8], "uv": [0, 0]}]},
			    {"name": "drill_mount", "parent": "body", "pivot": [0, 4, -4]},
			    {"name": "drill_head", "parent": "drill_mount", "pivot": [0, 4, -4], "cubes": [{"origin": [-1, 3, -8], "size": [2, 2, 4], "uv": [0, 16]}]}
			  ]}]}""";

	@GameTest
	public void everyPodParsesWithTheMachinesParts(GameTestHelper helper) throws IOException {
		for (Chassis chassis : Chassis.all()) {
			PodLook look = look(helper, chassis);
			GeoModel model = read(helper, look.modelFile());
			Set<BoneRole> roles = model.bones().stream().map(GeoModel.Bone::role).collect(Collectors.toCollection(() -> EnumSet.noneOf(BoneRole.class)));
			Set<String> names = model.bones().stream().map(GeoModel.Bone::name).collect(Collectors.toSet());
			require(helper, roles.contains(BoneRole.DRILL_HEAD), chassis.id() + " has no drill head");
			require(helper, roles.contains(BoneRole.ROTOR) || roles.contains(BoneRole.THRUSTER), chassis.id() + " has neither a rotor nor thrusters");
			require(helper, roles.contains(BoneRole.WHEEL) || roles.contains(BoneRole.LINKS) || roles.contains(BoneRole.LEG),
					chassis.id() + " has neither treads, wheels nor legs");
			require(helper, names.contains("lamps") && names.contains("canopy"), chassis.id() + " needs a lamps and a canopy bone, has " + names);
			require(helper, model.cutters().containsAll(List.of("tricone", "stacked", "fluted", "cluster")),
					chassis.id() + " should hold the four cutters of the drill tiers, holds " + model.cutters());
			look.check(model);
		}
		helper.succeed();
	}

	/** The Prospector is its own machine, not a Mole with a bigger number: a winch, a hatch for each of its two seats, a longer hull. */
	@GameTest
	public void theProspectorIsLongerWithTwoSeatsAndAWinch(GameTestHelper helper) throws IOException {
		GeoModel prospector = read(helper, look(helper, Chassis.PROSPECTOR).modelFile());
		Set<String> names = prospector.bones().stream().map(GeoModel.Bone::name).collect(Collectors.toSet());
		require(helper, names.contains("winch"), "the Prospector has a winch, has " + names);
		long hatches = prospector.bones().stream().filter(bone -> bone.name().equals("hatch")).flatMap(bone -> bone.cubes().stream()).count();
		require(helper, hatches == 2, "the Prospector has one hatch for each of its two seats in tandem, has " + hatches);
		GeoModel mole = read(helper, look(helper, Chassis.MOLE).modelFile());
		double[] longHull = prospector.restBounds(bone -> !prospector.inCutter(bone, null));
		double[] shortHull = mole.restBounds(bone -> !mole.inCutter(bone, null));
		require(helper, longHull[5] - longHull[2] > 1.3 * (shortHull[5] - shortHull[2]),
				"the Prospector's hull should be a third longer than the Mole's, %.1f and %.1f pixels".formatted(longHull[5] - longHull[2], shortHull[5] - shortHull[2]));
		helper.succeed();
	}

	/**
	 * The bore is the pod's width rounded up to whole blocks; at rest nothing may stick out of it at the sides, the back or the top,
	 * and nothing but the cutter (the drill head, the drill ring and what rides them) may pass its front face, by one block at most.
	 * Every cutter of every pod is held to it: the hull alone, then the hull with that cutter.
	 */
	@GameTest
	public void everyCutterFitsItsPodsBoreAtRest(GameTestHelper helper) throws IOException {
		double slack = 1e-4;
		for (Chassis chassis : Chassis.all()) {
			double half = Mth.ceil(chassis.width()) * 16 / 2.0;
			double top = chassis.height() * 16;
			GeoModel model = read(helper, look(helper, chassis).modelFile());
			double[] hull = model.restBounds(bone -> !model.inCutter(bone, null));
			require(helper, hull[0] >= -half - slack && hull[3] <= half + slack && hull[5] <= half + slack && hull[2] >= -half - slack,
					chassis.id() + "'s hull spans x %.2f..%.2f, z %.2f..%.2f, outside its %.0f-pixel bore".formatted(hull[0], hull[3], hull[2], hull[5], 2 * half));
			for (String cutter : model.cutters()) {
				double[] bounds = model.restBounds(bone -> !model.inCutter(bone, null) || model.inCutter(bone, cutter));
				double[] cone = model.restBounds(bone -> model.inCutter(bone, cutter));
				String what = chassis.id() + "'s " + cutter + " cutter";
				require(helper, bounds[0] >= -half - slack && bounds[3] <= half + slack && bounds[5] <= half + slack,
						what + " at rest spans x %.2f..%.2f and back z %.2f, wider than its %.0f-pixel bore".formatted(bounds[0], bounds[3], bounds[5], 2 * half));
				require(helper, cone[2] >= -half - 16 - slack, what + " reaches z %.2f, more than a block past the bore face".formatted(cone[2]));
				require(helper, -cone[2] - half >= 9, what + " leads the bore face by only %.2f pixels: it should reach into the block it chews".formatted(-cone[2] - half));
				require(helper, bounds[1] >= -slack && bounds[4] <= top + slack, what + " at rest spans y %.2f..%.2f, outside 0..%.1f".formatted(bounds[1], bounds[4], top));
			}
		}
		helper.succeed();
	}

	@GameTest
	public void theSmallestModelParses(GameTestHelper helper) {
		GeoModel model = GeoModel.parse("valid", new StringReader(VALID));
		require(helper, model.bones().size() == 3 && model.textureWidth() == 64, "the valid model should parse to 3 bones on 64 x 64, got " + model);
		helper.succeed();
	}

	@GameTest
	public void anUnknownBoneFailsAndNamesIt(GameTestHelper helper) {
		String message = failure(helper, VALID.replace("\"drill_head\", \"parent\"", "\"drill_haed\", \"parent\""));
		requireContains(helper, message, "'drill_haed' is not a pod bone");
		helper.succeed();
	}

	@GameTest
	public void aMalformedFileFailsAndSaysWhy(GameTestHelper helper) {
		Map<String, String> broken = Map.ofEntries(
				Map.entry(VALID.substring(0, 40), "not valid JSON"),
				Map.entry(VALID.replace("\"minecraft:geometry\": [{", "\"minecraft:geometry\": [{}, {"), "holds 2 geometries"),
				Map.entry(VALID.replace("\"uv\": [0, 16]", "\"uv\": {\"north\": {\"uv\": [0, 0], \"uv_size\": [2, 2]}}"), "per-face UV"),
				Map.entry(VALID.replace("\"uv\": [0, 16]", "\"uv\": [60, 60]"), "outside the 64 x 64 texture"),
				Map.entry(VALID.replace("\"parent\": \"drill_mount\"", "\"parent\": \"frame_x\""), "names the parent 'frame_x', which is not a bone"),
				Map.entry(VALID.replace("\"drill_mount\", \"parent\"", "\"frame\", \"parent\"").replace("\"parent\": \"drill_mount\"", "\"parent\": \"frame\""),
						"has 0 drill_mount bones"),
				Map.entry(VALID.replace("\"drill_head\", \"parent\"", "\"drill_mount\", \"parent\""), "two bones are named 'drill_mount'"),
				Map.entry(VALID.replace("{\"name\": \"body\",", "{\"name\": \"body\", \"poly_mesh\": {},"), "the key 'poly_mesh'"),
				Map.entry(VALID.replace("\"size\": [2, 2, 4]", "\"size\": [2, 2, 4], \"rotation\": [0, 0, 45]"), "has a rotation and no pivot"),
				Map.entry(VALID.replace("\"size\": [8, 8, 8]", "\"size\": [8, -8, 8]"), "negative size"),
				Map.entry(VALID.replace("\"texture_width\": 64", "\"texture_width\": 0"), "texture_width must be above 0"));
		for (Map.Entry<String, String> entry : broken.entrySet()) {
			requireContains(helper, failure(helper, entry.getKey()), entry.getValue());
		}
		helper.succeed();
	}

	/** A drill ring turns against the drill head, so it rides the mount (it aims with the drill) and not the head (it would stand still). */
	@GameTest
	public void aDrillRingRidesTheMountAndNotTheHead(GameTestHelper helper) {
		String withoutEnd = VALID.substring(0, VALID.lastIndexOf("\n  ]}]}"));
		String ring = """
				,
				    {"name": "drill_ring", "parent": "%s", "pivot": [0, 4, -4], "cubes": [{"origin": [-3, 1, -6], "size": [6, 6, 1], "uv": [0, 24]}]}
				  ]}]}""";
		GeoModel onMount = GeoModel.parse("ring", new StringReader(withoutEnd + ring.formatted("drill_mount")));
		require(helper, onMount.bones().stream().anyMatch(bone -> bone.role() == BoneRole.DRILL_RING), "a drill ring on the mount should parse, got " + onMount);
		String misplaced = "drill_ring bone 'drill_ring' must be under drill_mount and not under drill_head";
		requireContains(helper, failure(helper, withoutEnd + ring.formatted("drill_head")), misplaced);
		requireContains(helper, failure(helper, withoutEnd + ring.formatted("body")), misplaced);
		helper.succeed();
	}

	/**
	 * Round 3 (#366) asks for a giant cone: every cutter is 12.5 pixels or more from its axis (a base that nearly fills the bore
	 * face), and turns at under half the full rate, so it looks heavy; even a tricone's tilted rollers and an auger's turned blades
	 * count at their turned corners. A cutter on the Prospector is at least as wide as the Mole's of the same kind.
	 */
	@GameTest
	public void everyConeNearlyFillsTheBoreFaceAndTurnsSlower(GameTestHelper helper) throws IOException {
		GeoModel valid = GeoModel.parse("valid", new StringReader(VALID));
		require(helper, valid.drillReach() == 1.0, "the valid model's 2-pixel drill head should reach 1 pixel from its axis, not " + valid.drillReach());
		require(helper, valid.drillSpinScale() == 1.0, "the valid model's 2-pixel drill head should turn at the full rate, not " + valid.drillSpinScale());
		GeoModel mole = read(helper, look(helper, Chassis.MOLE).modelFile());
		GeoModel prospector = read(helper, look(helper, Chassis.PROSPECTOR).modelFile());
		for (GeoModel model : List.of(mole, prospector)) {
			for (String cutter : model.cutters()) {
				double reach = model.drillReach(cutter);
				double scale = model.drillSpinScale(cutter);
				require(helper, reach >= 12.5, model.source() + "'s " + cutter + " cone reaches %.1f pixels from its axis, under the 12.5 that nearly fills the bore face".formatted(reach));
				require(helper, scale > 0 && scale < 0.5, model.source() + "'s " + cutter + " cone turns at %.3f of the full rate, not under half".formatted(scale));
			}
		}
		for (String cutter : mole.cutters()) {
			require(helper, prospector.drillReach(cutter) >= mole.drillReach(cutter),
					"the Prospector's " + cutter + " cutter should be at least as wide as the Mole's");
		}
		helper.succeed();
	}

	/** A turned cube counts at its turned corners: a 10-pixel bar turned 45 degrees about z reaches 5 pixels along x and y at its ends, and 7.07 at its corners. */
	@GameTest
	public void aTurnedCubeInTheDrillReachesAtItsTurnedCorners(GameTestHelper helper) {
		String turned = VALID.replace("\"size\": [2, 2, 4]", "\"size\": [10, 0.001, 4], \"pivot\": [0, 4, -6], \"rotation\": [0, 0, 45]")
				.replace("\"origin\": [-1, 3, -8]", "\"origin\": [-5, 4, -8]");
		GeoModel model = GeoModel.parse("turned", new StringReader(turned));
		require(helper, Math.abs(model.drillReach() - Math.sqrt(12.5)) < 0.01, "a 10-pixel bar turned 45 degrees should reach 3.54 pixels, not " + model.drillReach());
		helper.succeed();
	}

	/**
	 * The renderer culls a pod by this box, so it must hold the whole model at every heading, every cutter included: a cone leads the
	 * hitbox by a block, and sinks under the floor when the drill points down. The box is round the pod's feet, in blocks.
	 */
	@GameTest
	public void theCullingBoxHoldsTheWholeModelAtAnyHeading(GameTestHelper helper) throws IOException {
		for (Chassis chassis : Chassis.all()) {
			GeoModel model = read(helper, look(helper, chassis).modelFile());
			AABB box = model.cullingBox();
			for (String cutter : model.cutters()) {
				for (double pitch : new double[] {0, 45, 90}) {
					double[] b = model.restBounds(bone -> model.inCutter(bone, cutter), pitch);
					for (double x : new double[] {b[0], b[3]}) {
						for (double z : new double[] {b[2], b[5]}) {
							for (int degrees = 0; degrees < 360; degrees += 15) {
								double yaw = Math.toRadians(degrees);
								double px = (x * Math.cos(yaw) - z * Math.sin(yaw)) / 16;
								double pz = (x * Math.sin(yaw) + z * Math.cos(yaw)) / 16;
								require(helper, box.inflate(1e-6).contains(px, b[1] / 16, pz) && box.inflate(1e-6).contains(px, b[4] / 16, pz),
										chassis.id() + "'s " + cutter + " cutter corner (" + x + ", " + z + ") at " + degrees + " degrees with the drill turned " + pitch
												+ " is outside its culling box " + box);
							}
						}
					}
				}
			}
			double[] all = model.restBounds();
			require(helper, box.minY <= all[1] / 16 + 1e-9 && box.maxY >= all[4] / 16 - 1e-9, chassis.id() + "'s culling box " + box + " does not hold its height");
			require(helper, box.minY <= -12.0 / 16, chassis.id() + "'s culling box " + box + " does not reach the cutter's tip under the floor");
			// The cone's tip is two blocks from the pod's middle: well past the hitbox's half width.
			require(helper, box.maxX >= 2.0 - 1e-9, chassis.id() + "'s culling box " + box + " does not reach the cone's tip");
		}
		helper.succeed();
	}

	/** Each pod's textures and glowmasks pass the check the renderer makes before it draws. */
	@GameTest
	public void everyPodTexturePassesTheRenderersCheck(GameTestHelper helper) throws IOException {
		for (Chassis chassis : Chassis.all()) {
			PodLook look = look(helper, chassis);
			GeoModel model = read(helper, look.modelFile());
			for (PodLook.Variant variant : List.of(look.intact(), look.wreck())) {
				List<Identifier> textures = variant.glow() == PodLook.Glow.NEVER ? List.of(variant.texture()) : List.of(variant.texture(), variant.glowmask());
				for (Identifier texture : textures) {
					try (InputStream png = stream(helper, texture)) {
						model.checkTexture(texture.toString(), png);
					}
				}
			}
		}
		helper.succeed();
	}

	/** A texture of another size than the model's UV, or a file that is no PNG, fails and names the texture and the model. */
	@GameTest
	public void aTextureThatDoesNotFitFailsAndNamesBothFiles(GameTestHelper helper) throws IOException {
		GeoModel model = GeoModel.parse("valid.geo.json", new StringReader(VALID));
		String wrongSize = textureFailure(helper, model, pngHeader(64, 32));
		requireContains(helper, wrongSize, "broken.png is 64 x 32");
		requireContains(helper, wrongSize, "valid.geo.json");
		requireContains(helper, wrongSize, "64 x 64");
		requireContains(helper, textureFailure(helper, model, "not a png at all, just text".getBytes(StandardCharsets.UTF_8)), "broken.png is not a PNG");
		helper.succeed();
	}

	private static String textureFailure(GameTestHelper helper, GeoModel model, byte[] png) throws IOException {
		try {
			model.checkTexture("broken.png", new ByteArrayInputStream(png));
			throw helper.assertionException(Component.literal("expected the texture check to fail"));
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
	}

	/** The first 24 bytes of a PNG of {@code width} x {@code height}: the signature and the start of its IHDR chunk. */
	private static byte[] pngHeader(int width, int height) {
		return ByteBuffer.allocate(24).putLong(0x89504E470D0A1A0AL).putInt(13).put("IHDR".getBytes(StandardCharsets.US_ASCII)).putInt(width)
				.putInt(height).array();
	}

	/** The look of {@code chassis} as the mod ships it, read from the classpath. */
	static PodLook look(GameTestHelper helper, Chassis chassis) throws IOException {
		Identifier file = PodLook.file(chassis);
		try (Reader reader = new InputStreamReader(stream(helper, file), StandardCharsets.UTF_8)) {
			return PodLook.parse(file.toString(), reader);
		}
	}

	static GeoModel read(GameTestHelper helper, Identifier id) throws IOException {
		try (Reader reader = new InputStreamReader(stream(helper, id), StandardCharsets.UTF_8)) {
			return GeoModel.parse(id.toString(), reader);
		}
	}

	/** Width and height from a PNG's IHDR chunk. */
	private static int[] pngSize(GameTestHelper helper, Identifier id) throws IOException {
		try (InputStream in = stream(helper, id)) {
			ByteBuffer header = ByteBuffer.wrap(in.readNBytes(24));
			require(helper, header.limit() == 24 && header.getLong(0) == 0x89504E470D0A1A0AL, id + " is not a PNG");
			return new int[] {header.getInt(16), header.getInt(20)};
		}
	}

	static InputStream stream(GameTestHelper helper, Identifier id) {
		String path = "/assets/%s/%s".formatted(id.getNamespace(), id.getPath());
		InputStream in = PodGeoModelTest.class.getResourceAsStream(path);
		require(helper, in != null, path + " is not on the classpath");
		return in;
	}

	/** Parses {@code json}, which must fail, and returns the message. */
	private static String failure(GameTestHelper helper, String json) {
		try {
			GeoModel model = GeoModel.parse("broken.geo.json", new StringReader(json));
			throw helper.assertionException(Component.literal("expected a failure, parsed " + model));
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
	}

	private static void requireContains(GameTestHelper helper, String message, String part) {
		require(helper, message.contains(part), "expected the error to say '" + part + "', it says: " + message);
	}

	private static void require(GameTestHelper helper, boolean condition, String message) {
		if (!condition) {
			throw helper.assertionException(Component.literal(message));
		}
	}
}
