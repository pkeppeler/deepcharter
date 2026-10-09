package io.github.pkeppeler.deepcharter.client.pod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A {@link GeoModel} baked into vanilla {@link ModelPart}s, and posed each frame from a {@link PodGeoRenderState} by bone role.
 *
 * <p>The file's space is y up with the floor at 0; a ModelPart's is y down with the floor at 24, as vanilla entity models are. So a
 * point maps to (x, 24 - y, z), and a rotation keeps its angles (GeckoLib reads the same file the same way). A turned cube becomes
 * its own child part.
 */
public class PodGeoModel extends Model<PodGeoRenderState> {
	/** Pixels of travel for one full stride of a leg. */
	private static final float STRIDE_PIXELS = 24f;
	private static final float STRIDE_SWING_DEGREES = 18f;
	private static final float STRIDE_LIFT_DEGREES = 22f;
	/** How far a thigh tucks its leg up while the pod flies. */
	private static final float FLIGHT_TUCK_DEGREES = 30f;
	/** A thruster swings from pointing back to pointing down. */
	private static final float THRUST_DEGREES = -90f;
	private static final float FLOOR = 24f;
	/**
	 * A drill head that reaches this far from its axis, in pixels, spins at the full rate. A wider one spins slower, by the square of
	 * its reach: one that reaches twice as far turns at a quarter of the rate, so a giant cutter turns with weight and not like a toy.
	 * Every round-1 drill reaches 7.5 or less.
	 */
	private static final float FULL_SPIN_REACH = 8f;

	private record Wheel(ModelPart part, float radius) {
	}

	private record Links(ModelPart part, float pitch) {
	}

	/** A leg or thigh, and where in the stride it is: diagonal pairs move together. */
	private record Stride(ModelPart part, float phase, float side) {
	}

	private final ModelPart drillMount;
	private final float mountRestPitch;
	private final float drillSpinScale;
	private final List<ModelPart> drillHeads = new ArrayList<>();
	private final List<ModelPart> drillRings = new ArrayList<>();
	private final List<ModelPart> rotors = new ArrayList<>();
	private final List<ModelPart> fans = new ArrayList<>();
	private final List<ModelPart> thrusters = new ArrayList<>();
	private final List<ModelPart> flames = new ArrayList<>();
	private final List<Wheel> wheels = new ArrayList<>();
	private final List<Links> links = new ArrayList<>();
	private final List<Stride> legs = new ArrayList<>();
	private final List<Stride> thighs = new ArrayList<>();

	public PodGeoModel(GeoModel geo) {
		super(bake(geo), RenderTypes::entityCutout);
		Map<String, ModelPart> parts = new HashMap<>();
		for (GeoModel.Bone top : geo.bones().stream().filter(bone -> bone.parent().isEmpty()).toList()) {
			collect(geo, top, root().getChild(top.name()), parts);
		}
		for (GeoModel.Bone bone : geo.bones()) {
			ModelPart part = parts.get(bone.name());
			switch (bone.role()) {
				case FIXED, DRILL_MOUNT -> { }
				case DRILL_HEAD -> drillHeads.add(part);
				case DRILL_RING -> drillRings.add(part);
				case ROTOR -> rotors.add(part);
				case FAN -> fans.add(part);
				case THRUSTER -> thrusters.add(part);
				case FLAME -> flames.add(part);
				case WHEEL -> wheels.add(new Wheel(part, radius(geo, bone)));
				case LINKS -> links.add(new Links(part, pitch(geo, bone)));
				case LEG -> legs.add(stride(part, bone.pivot()));
				case THIGH -> thighs.add(stride(part, bone.pivot()));
			}
		}
		// GeoModel.parse guarantees exactly one drill mount.
		GeoModel.Bone mount = geo.bones().stream().filter(bone -> bone.role() == BoneRole.DRILL_MOUNT).findFirst().orElseThrow();
		drillMount = parts.get(mount.name());
		mountRestPitch = (float) mount.rotation().x;
		float share = (float) (FULL_SPIN_REACH / geo.drillReach());
		drillSpinScale = Math.min(1f, share * share);
	}

	/** The drill mount's x rotation in the file: where the drill rests while the pod is not drilling. */
	public float mountRestPitch() {
		return mountRestPitch;
	}

	/** How fast this model's drill spins, as a share of the full rate: 1 for a drill that reaches {@value #FULL_SPIN_REACH} pixels or less. */
	public float drillSpinScale() {
		return drillSpinScale;
	}

	@Override
	public void setupAnim(PodGeoRenderState state) {
		super.setupAnim(state);
		drillMount.xRot = state.mountPitch * Mth.DEG_TO_RAD;
		for (ModelPart head : drillHeads) {
			head.zRot += state.drillSpin * Mth.DEG_TO_RAD;
		}
		for (ModelPart ring : drillRings) {
			ring.zRot -= state.drillSpin * Mth.DEG_TO_RAD;
		}
		for (ModelPart rotor : rotors) {
			rotor.yRot += state.rotorSpin * Mth.DEG_TO_RAD;
		}
		for (ModelPart fan : fans) {
			fan.zRot += state.fanSpin * Mth.DEG_TO_RAD;
		}
		for (ModelPart thruster : thrusters) {
			thruster.xRot += state.thrust * THRUST_DEGREES * Mth.DEG_TO_RAD;
		}
		for (ModelPart flame : flames) {
			flame.visible = state.flying;
		}
		for (Wheel wheel : wheels) {
			wheel.part().xRot += state.travel / wheel.radius();
		}
		for (Links run : links) {
			run.part().z += Mth.positiveModulo(state.travel, run.pitch());
		}
		float stride = state.travel / STRIDE_PIXELS * Mth.TWO_PI;
		for (Stride leg : legs) {
			leg.part().yRot += leg.side() * state.walk * STRIDE_SWING_DEGREES * Mth.DEG_TO_RAD * Mth.sin(stride + leg.phase());
		}
		for (Stride thigh : thighs) {
			float lift = state.walk * STRIDE_LIFT_DEGREES * Math.max(0f, Mth.cos(stride + thigh.phase())) + state.thrust * FLIGHT_TUCK_DEGREES;
			thigh.part().zRot -= lift * Mth.DEG_TO_RAD;
		}
	}

	private static ModelPart bake(GeoModel geo) {
		MeshDefinition mesh = new MeshDefinition();
		for (GeoModel.Bone top : geo.bones().stream().filter(bone -> bone.parent().isEmpty()).toList()) {
			add(geo, mesh.getRoot(), Vec3.ZERO, top);
		}
		return LayerDefinition.create(mesh, geo.textureWidth(), geo.textureHeight()).bakeRoot();
	}

	private static void add(GeoModel geo, PartDefinition parent, Vec3 parentPivot, GeoModel.Bone bone) {
		Vec3 pivot = toPart(bone.pivot());
		CubeListBuilder cubes = CubeListBuilder.create();
		for (GeoModel.Cube cube : bone.cubes()) {
			if (cube.turn().isEmpty()) {
				box(cubes, cube, bone.pivot());
			}
		}
		PartDefinition part = parent.addOrReplaceChild(bone.name(), cubes, pose(pivot.subtract(parentPivot), bone.rotation()));
		int turned = 0;
		for (GeoModel.Cube cube : bone.cubes()) {
			if (cube.turn().isPresent()) {
				GeoModel.Turn turn = cube.turn().get();
				CubeListBuilder one = CubeListBuilder.create();
				box(one, cube, turn.pivot());
				part.addOrReplaceChild(bone.name() + "#" + turned++, one, pose(toPart(turn.pivot()).subtract(pivot), turn.rotation()));
			}
		}
		for (GeoModel.Bone child : geo.children(bone.name())) {
			add(geo, part, pivot, child);
		}
	}

	private static void collect(GeoModel geo, GeoModel.Bone bone, ModelPart part, Map<String, ModelPart> parts) {
		parts.put(bone.name(), part);
		for (GeoModel.Bone child : geo.children(bone.name())) {
			collect(geo, child, part.getChild(child.name()), parts);
		}
	}

	/** A cube relative to {@code pivot} (file space), as ModelPart's y-down box. */
	private static void box(CubeListBuilder builder, GeoModel.Cube cube, Vec3 pivot) {
		Vec3 origin = cube.origin();
		Vec3 size = cube.size();
		builder.texOffs(cube.u(), cube.v()).mirror(cube.mirror()).addBox((float) (origin.x - pivot.x), (float) (pivot.y - origin.y - size.y),
				(float) (origin.z - pivot.z), (float) size.x, (float) size.y, (float) size.z, new CubeDeformation(cube.inflate()));
	}

	private static PartPose pose(Vec3 offset, Vec3 degrees) {
		return PartPose.offsetAndRotation((float) offset.x, (float) offset.y, (float) offset.z, (float) degrees.x * Mth.DEG_TO_RAD,
				(float) degrees.y * Mth.DEG_TO_RAD, (float) degrees.z * Mth.DEG_TO_RAD);
	}

	private static Vec3 toPart(Vec3 point) {
		return new Vec3(point.x, FLOOR - point.y, point.z);
	}

	/** Diagonal legs share a phase; a leg on the right swings the other way, as its hip is turned the other way. */
	private static Stride stride(ModelPart part, Vec3 hip) {
		boolean diagonal = Math.signum(hip.x) * Math.signum(hip.z) > 0;
		return new Stride(part, diagonal ? Mth.PI : 0f, Math.signum(hip.x) >= 0 ? 1f : -1f);
	}

	/** Half the widest cube of the wheel, across its turning face. */
	private static float radius(GeoModel geo, GeoModel.Bone wheel) {
		double radius = wheel.cubes().stream().mapToDouble(cube -> Math.max(cube.size().y, cube.size().z) / 2).max().orElse(0);
		if (radius <= 0) {
			throw new IllegalArgumentException(geo.source() + ": wheel bone '" + wheel.name() + "' has no cube to turn");
		}
		return (float) radius;
	}

	/** The spacing of a links bone's cubes along z, which it slides by before it starts over. */
	private static float pitch(GeoModel geo, GeoModel.Bone run) {
		List<Double> starts = run.cubes().stream().map(cube -> cube.origin().z).distinct().sorted().toList();
		double pitch = Double.MAX_VALUE;
		for (int i = 1; i < starts.size(); i++) {
			pitch = Math.min(pitch, starts.get(i) - starts.get(i - 1));
		}
		if (starts.size() < 2) {
			throw new IllegalArgumentException(geo.source() + ": links bone '" + run.name() + "' needs two cubes or more along z to run");
		}
		return (float) pitch;
	}
}
