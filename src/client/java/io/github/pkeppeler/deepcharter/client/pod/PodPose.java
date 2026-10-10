package io.github.pkeppeler.deepcharter.client.pod;

import java.util.ArrayList;
import java.util.List;

import com.geckolib.animation.state.BoneSnapshot;
import com.geckolib.renderer.base.BoneSnapshots;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Poses the bones of a pod model by role from a {@link PodGeoRenderState} (ADR 0038): the drill mount aims, the drill head and ring
 * spin, the rotor, fan and thrusters turn, wheels roll, links slide, legs stride, flames show while the pod flies. It is built
 * once from the model's own bones, and names no bone but by its role.
 *
 * <p>An angle here is the file's angle in degrees, added to the bone's rest rotation. GeckoLib bakes a file rotation as
 * (-x, -y, z) radians, so {@link #turn} negates x and y on the way in.
 */
public final class PodPose {
	/** Pixels of travel for one full stride of a leg. */
	private static final float STRIDE_PIXELS = 24f;
	private static final float STRIDE_SWING_DEGREES = 18f;
	private static final float STRIDE_LIFT_DEGREES = 22f;
	/** How far a thigh tucks its leg up while the pod flies. */
	private static final float FLIGHT_TUCK_DEGREES = 30f;
	/** How far a blade turns from out to folded, about the rotor's hub. */
	private static final float FOLD_DEGREES = 90f;
	/** A thruster swings from pointing back to pointing down. */
	private static final float THRUST_DEGREES = -90f;

	/** A blade of a rotor, and which side of the hub it is on: 1 for the pod's left (+x), -1 for its right. */
	private record Blade(String bone, float side) {
	}

	private record Wheel(String bone, float radius) {
	}

	private record Links(String bone, float pitch) {
	}

	/** A leg or thigh, and where in the stride it is: diagonal pairs move together. */
	private record Stride(String bone, float phase, float side) {
	}

	private final String drillMount;
	private final float mountRestPitch;
	private final List<String> drillHeads = new ArrayList<>();
	private final List<String> drillRings = new ArrayList<>();
	private final List<String> rotors = new ArrayList<>();
	private final List<Blade> blades = new ArrayList<>();
	private final List<String> fans = new ArrayList<>();
	private final List<String> thrusters = new ArrayList<>();
	private final List<String> flames = new ArrayList<>();
	private final List<Wheel> wheels = new ArrayList<>();
	private final List<Links> links = new ArrayList<>();
	private final List<Stride> legs = new ArrayList<>();
	private final List<Stride> thighs = new ArrayList<>();

	PodPose(GeoModel geo) {
		for (GeoModel.Bone bone : geo.bones()) {
			switch (bone.role()) {
				case FIXED, DRILL_MOUNT -> { }
				case DRILL_HEAD -> drillHeads.add(bone.name());
				case DRILL_RING -> drillRings.add(bone.name());
				case ROTOR -> rotors.add(bone.name());
				case BLADE -> blades.add(new Blade(bone.name(), hubSide(geo, bone)));
				case FAN -> fans.add(bone.name());
				case THRUSTER -> thrusters.add(bone.name());
				case FLAME -> flames.add(bone.name());
				case WHEEL -> wheels.add(new Wheel(bone.name(), radius(geo, bone)));
				case LINKS -> links.add(new Links(bone.name(), pitch(geo, bone)));
				case LEG -> legs.add(stride(bone));
				case THIGH -> thighs.add(stride(bone));
			}
		}
		// GeoModel.parse guarantees exactly one drill mount.
		GeoModel.Bone mount = geo.bones().stream().filter(bone -> bone.role() == BoneRole.DRILL_MOUNT).findFirst().orElseThrow();
		drillMount = mount.name();
		mountRestPitch = (float) mount.rotation().x;
	}

	/** The drill mount's x rotation in the file: where the drill rests while the pod is not drilling. */
	float mountRestPitch() {
		return mountRestPitch;
	}

	void apply(PodGeoRenderState state, BoneSnapshots snapshots) {
		snapshots.ifPresent(drillMount, snapshot -> turn(snapshot, state.mountPitch - mountRestPitch, 0f, 0f));
		for (String head : drillHeads) {
			snapshots.ifPresent(head, snapshot -> turn(snapshot, 0f, 0f, state.drillSpin));
		}
		for (String ring : drillRings) {
			snapshots.ifPresent(ring, snapshot -> turn(snapshot, 0f, 0f, -state.drillSpin));
		}
		for (String rotor : rotors) {
			snapshots.ifPresent(rotor, snapshot -> turn(snapshot, 0f, state.rotorSpin, 0f));
		}
		for (Blade blade : blades) {
			snapshots.ifPresent(blade.bone(), snapshot -> turn(snapshot, 0f, bladeFold(state.rotorOut, blade.side()), 0f));
		}
		for (String fan : fans) {
			snapshots.ifPresent(fan, snapshot -> turn(snapshot, 0f, 0f, state.fanSpin));
		}
		for (String thruster : thrusters) {
			snapshots.ifPresent(thruster, snapshot -> turn(snapshot, state.thrust * THRUST_DEGREES, 0f, 0f));
		}
		for (String flame : flames) {
			snapshots.ifPresent(flame, snapshot -> snapshot.skipRender(!state.flying).skipChildrenRender(!state.flying));
		}
		for (Wheel wheel : wheels) {
			snapshots.ifPresent(wheel.bone(), snapshot -> turn(snapshot, state.travel / wheel.radius() * Mth.RAD_TO_DEG, 0f, 0f));
		}
		for (Links run : links) {
			snapshots.ifPresent(run.bone(), snapshot -> snapshot.setTranslation(0f, 0f, Mth.positiveModulo(state.travel, run.pitch())));
		}
		float stride = state.travel / STRIDE_PIXELS * Mth.TWO_PI;
		for (Stride leg : legs) {
			snapshots.ifPresent(leg.bone(), snapshot -> turn(snapshot, 0f, leg.side() * state.walk * STRIDE_SWING_DEGREES * Mth.sin(stride + leg.phase()), 0f));
		}
		for (Stride thigh : thighs) {
			float lift = state.walk * STRIDE_LIFT_DEGREES * Math.max(0f, Mth.cos(stride + thigh.phase())) + state.thrust * FLIGHT_TUCK_DEGREES;
			snapshots.ifPresent(thigh.bone(), snapshot -> turn(snapshot, 0f, 0f, -lift));
		}
	}

	/**
	 * The file's y angle of a blade on {@code side} of the hub (1 for +x, -1 for -x), with the rotor {@code out} (0 folded, 1 out to fly): 0 out,
	 * and 90 degrees folded, turned so that each blade points forward (-z) and the pair lie along the roof.
	 */
	public static float bladeFold(float out, float side) {
		return side * (1f - Mth.clamp(out, 0f, 1f)) * FOLD_DEGREES;
	}

	/** Which side of its rotor's hub a blade's pivot lies: 1 for +x, -1 for -x. */
	private static float hubSide(GeoModel geo, GeoModel.Bone blade) {
		GeoModel.Bone rotor = geo.bones().stream().filter(bone -> bone.name().equals(blade.parent().orElseThrow())).findFirst().orElseThrow();
		float side = (float) Math.signum(blade.pivot().x - rotor.pivot().x);
		if (side == 0f) {
			throw new IllegalArgumentException(geo.source() + ": blade bone '" + blade.name() + "' has its pivot on its rotor's hub, so it has no side to fold to");
		}
		return side;
	}

	/** Turns by the file's angles in degrees: GeckoLib's x and y rotations are the file's, negated. */
	private static void turn(BoneSnapshot snapshot, float xDegrees, float yDegrees, float zDegrees) {
		snapshot.setRotation(-xDegrees * Mth.DEG_TO_RAD, -yDegrees * Mth.DEG_TO_RAD, zDegrees * Mth.DEG_TO_RAD);
	}

	/** Diagonal legs share a phase; a leg on the right swings the other way, as its hip is turned the other way. */
	private static Stride stride(GeoModel.Bone bone) {
		Vec3 hip = bone.pivot();
		boolean diagonal = Math.signum(hip.x) * Math.signum(hip.z) > 0;
		return new Stride(bone.name(), diagonal ? Mth.PI : 0f, Math.signum(hip.x) >= 0 ? 1f : -1f);
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
