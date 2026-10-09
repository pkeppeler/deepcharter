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
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.client.pod.BoneRole;
import io.github.pkeppeler.deepcharter.client.pod.GeoModel;
import io.github.pkeppeler.deepcharter.client.pod.PodConcept;
import io.github.pkeppeler.deepcharter.pod.Chassis;

/**
 * Server GameTests for #334: every Mole concept's Bedrock geometry parses with its rig, has the parts of a machine, fits the Mole's
 * bore at rest and has its two textures; an unknown bone or a malformed file fails loud and says where.
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
	public void everyConceptParsesWithTheMachinesParts(GameTestHelper helper) throws IOException {
		for (PodConcept concept : PodConcept.values()) {
			GeoModel model = read(helper, concept.model());
			Set<BoneRole> roles = model.bones().stream().map(GeoModel.Bone::role).collect(Collectors.toCollection(() -> EnumSet.noneOf(BoneRole.class)));
			Set<String> names = model.bones().stream().map(GeoModel.Bone::name).collect(Collectors.toSet());
			require(helper, roles.contains(BoneRole.DRILL_HEAD), concept + " has no drill head");
			require(helper, roles.contains(BoneRole.ROTOR) || roles.contains(BoneRole.THRUSTER), concept + " has neither a rotor nor thrusters");
			require(helper, roles.contains(BoneRole.WHEEL) || roles.contains(BoneRole.LINKS) || roles.contains(BoneRole.LEG),
					concept + " has neither treads, wheels nor legs");
			require(helper, names.contains("lamps") && names.contains("canopy"), concept + " needs a lamps and a canopy bone, has " + names);
			int cubes = model.bones().stream().mapToInt(bone -> bone.cubes().size()).sum();
			// tooling-options.md: a Mole is 80 to 150 cubes; a concept may run a little over while the user picks.
			require(helper, cubes >= 80 && cubes <= 160, concept + " has " + cubes + " cubes, outside the Mole's 80 to 160");
			for (Identifier texture : List.of(concept.texture(), concept.glowmask())) {
				int[] size = pngSize(helper, texture);
				require(helper, size[0] == model.textureWidth() && size[1] == model.textureHeight(),
						texture + " is " + size[0] + " x " + size[1] + ", the model's UV is laid out for " + model.textureWidth() + " x " + model.textureHeight());
			}
		}
		helper.succeed();
	}

	/** The bore is the Mole's width rounded up to whole blocks; at rest nothing may stick out of it, or rise above the hitbox. */
	@GameTest
	public void everyConceptFitsTheMolesBoreAtRest(GameTestHelper helper) throws IOException {
		double half = Mth.ceil(Chassis.MOLE.width()) * 16 / 2.0;
		double top = Chassis.MOLE.height() * 16;
		double slack = 1e-4;
		for (PodConcept concept : PodConcept.values()) {
			double[] bounds = restBounds(read(helper, concept.model()));
			require(helper, bounds[0] >= -half - slack && bounds[3] <= half + slack && bounds[2] >= -half - slack && bounds[5] <= half + slack,
					concept + " at rest spans x %.2f..%.2f and z %.2f..%.2f, wider than its %.0f-pixel bore".formatted(bounds[0], bounds[3], bounds[2],
							bounds[5], 2 * half));
			require(helper, bounds[1] >= -slack && bounds[4] <= top + slack,
					concept + " at rest spans y %.2f..%.2f, outside 0..%.1f".formatted(bounds[1], bounds[4], top));
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
	 * Round 2 (#352) asks for the Borer's cutter made giant, filling or nearly filling the 32-pixel bore face: every round-2 cutter is
	 * 25 pixels across or more. Every round-1 drill reaches 7.5 pixels or less, so the renderer spins it at the full rate, as before.
	 */
	@GameTest
	public void roundTwoCuttersNearlyFillTheBoreFace(GameTestHelper helper) throws IOException {
		double validReach = GeoModel.parse("valid", new StringReader(VALID)).drillReach();
		require(helper, validReach == 1.0, "the valid model's 2-pixel drill head should reach 1 pixel from its axis, not " + validReach);
		for (PodConcept concept : PodConcept.values()) {
			double reach = read(helper, concept.model()).drillReach();
			if (concept.round() == 2) {
				require(helper, reach >= 12.5, concept + "'s cutter reaches %.1f pixels from its axis, under the 12.5 that nearly fills the bore face".formatted(reach));
			} else {
				require(helper, reach <= 7.5, concept + "'s drill reaches %.1f pixels from its axis, past round 1's 7.5".formatted(reach));
			}
		}
		helper.succeed();
	}

	/** Each concept's texture and glowmask pass the check the renderer makes before it draws. */
	@GameTest
	public void everyConceptTexturePassesTheRenderersCheck(GameTestHelper helper) throws IOException {
		for (PodConcept concept : PodConcept.values()) {
			GeoModel model = read(helper, concept.model());
			for (Identifier texture : List.of(concept.texture(), concept.glowmask())) {
				try (InputStream png = stream(helper, texture)) {
					model.checkTexture(texture.toString(), png);
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

	/** A dev switch that names no concept fails, and says which names it knows. */
	@GameTest
	public void anUnknownDevSwitchNamesTheConcepts(GameTestHelper helper) {
		String before = System.getProperty(PodConcept.PROPERTY);
		System.setProperty(PodConcept.PROPERTY, "dril");
		try {
			PodConcept.selected();
			throw helper.assertionException(Component.literal("the unknown concept 'dril' should fail"));
		} catch (IllegalArgumentException e) {
			for (String part : List.of("dril", "capsule", "borer", "strider", "gyro")) {
				requireContains(helper, e.getMessage(), part);
			}
		} finally {
			if (before == null) {
				System.clearProperty(PodConcept.PROPERTY);
			} else {
				System.setProperty(PodConcept.PROPERTY, before);
			}
		}
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

	/** The extent of every cube corner at rest, turned by its own rotation and its bones', as GeckoLib and our loader read the file. */
	private static double[] restBounds(GeoModel model) {
		Map<String, GeoModel.Bone> byName = model.bones().stream().collect(Collectors.toMap(GeoModel.Bone::name, bone -> bone));
		double[] bounds = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
		for (GeoModel.Bone bone : model.bones()) {
			for (GeoModel.Cube cube : bone.cubes()) {
				for (int corner = 0; corner < 8; corner++) {
					Vec3 p = cube.origin().add((corner & 1) * cube.size().x, (corner >> 1 & 1) * cube.size().y, (corner >> 2 & 1) * cube.size().z);
					if (cube.turn().isPresent()) {
						p = turn(p, cube.turn().get().pivot(), cube.turn().get().rotation());
					}
					for (GeoModel.Bone at = bone; at != null; at = at.parent().map(byName::get).orElse(null)) {
						p = turn(p, at.pivot(), at.rotation());
					}
					double[] xyz = {p.x, p.y, p.z};
					for (int i = 0; i < 3; i++) {
						bounds[i] = Math.min(bounds[i], xyz[i]);
						bounds[i + 3] = Math.max(bounds[i + 3], xyz[i]);
					}
				}
			}
		}
		return bounds;
	}

	/** Bedrock's rotation in y-up space: x, then y, then z, with the x and z angles turning the other way from ModelPart's y-down space. */
	private static Vec3 turn(Vec3 point, Vec3 pivot, Vec3 degrees) {
		return point.subtract(pivot).xRot((float) Math.toRadians(degrees.x)).yRot((float) Math.toRadians(degrees.y))
				.zRot((float) Math.toRadians(degrees.z)).add(pivot);
	}

	private static GeoModel read(GameTestHelper helper, Identifier id) throws IOException {
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

	private static InputStream stream(GameTestHelper helper, Identifier id) {
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
