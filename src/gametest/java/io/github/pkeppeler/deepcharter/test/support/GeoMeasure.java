package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.client.pod.BoneRole;
import io.github.pkeppeler.deepcharter.client.pod.GeoModel;

/** Measuring queries on a parsed pod model that production does not need. */
public final class GeoMeasure {
	private static final int CUBE_CORNERS = 8;

	private GeoMeasure() {
	}

	/** True for a bone that is a cutter's own, or under one: {@code drill_head}, {@code drill_ring} and what rides them, of the cutter {@code cutter} (null: of any). */
	public static boolean inCutter(GeoModel geo, GeoModel.Bone bone, String cutter) {
		Map<String, GeoModel.Bone> byName = new HashMap<>();
		geo.bones().forEach(each -> byName.put(each.name(), each));
		for (GeoModel.Bone at = bone; at != null; at = at.parent().map(byName::get).orElse(null)) {
			if (at.role() == BoneRole.DRILL_HEAD || at.role() == BoneRole.DRILL_RING) {
				return cutter == null || GeoModel.cutterOf(at).map(cutter::equals).orElse(false);
			}
		}
		return false;
	}

	/**
	 * The box of each cube of the bones {@code only} accepts at rest, in pixels, as {minX, minY, minZ, maxX, maxY, maxZ}: the box of the
	 * cube's corners turned by its own rotation and its bones'. A turned cube is held by the box of its turned corners, which holds it
	 * and a little more, so a test that a point is clear of every cube errs towards "not clear".
	 */
	public static List<GeoModel.RestCube> restCubes(GeoModel geo, Predicate<GeoModel.Bone> only) {
		Map<String, GeoModel.Bone> byName = new HashMap<>();
		geo.bones().forEach(bone -> byName.put(bone.name(), bone));
		List<GeoModel.RestCube> cubes = new ArrayList<>();
		for (GeoModel.Bone bone : geo.bones().stream().filter(only).toList()) {
			for (GeoModel.Cube cube : bone.cubes()) {
				double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
				for (int corner = 0; corner < CUBE_CORNERS; corner++) {
					Vec3 p = GeoModel.turnedCorner(cube, corner);
					for (GeoModel.Bone at = bone; at != null; at = at.parent().map(byName::get).orElse(null)) {
						p = GeoModel.turn(p, at.pivot(), at.rotation());
					}
					double[] xyz = {p.x, p.y, p.z};
					for (int i = 0; i < 3; i++) {
						box[i] = Math.min(box[i], xyz[i]);
						box[i + 3] = Math.max(box[i + 3], xyz[i]);
					}
				}
				cubes.add(new GeoModel.RestCube(bone, cube, box));
			}
		}
		return cubes;
	}
}
