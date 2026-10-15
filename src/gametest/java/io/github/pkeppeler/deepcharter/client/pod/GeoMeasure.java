package io.github.pkeppeler.deepcharter.client.pod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/** Measuring queries on a parsed pod model that production does not need. It sits in the package of {@link GeoModel} to reach its rest cubes. */
public final class GeoMeasure {
    private GeoMeasure() {}

    /** True for a bone that is a cutter's own, or under one: {@code drill_head}, {@code drill_ring} and what rides them, of the cutter {@code cutter} (null: of any). */
    public static boolean inCutter(GeoModel geo, GeoModel.Bone bone, @Nullable String cutter) {
        Map<String, GeoModel.Bone> byName = new HashMap<>();
        geo.bones().forEach(each -> byName.put(each.name(), each));
        for (GeoModel.Bone at = bone;
                at != null;
                at = at.parent().map(byName::get).orElse(null)) {
            if (at.role() == BoneRole.DRILL_HEAD || at.role() == BoneRole.DRILL_RING) {
                return cutter == null
                        || GeoModel.cutterOf(at).map(cutter::equals).orElse(false);
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
        return geo.restCubes(only, OptionalDouble.empty());
    }
}
