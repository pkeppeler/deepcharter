package io.github.pkeppeler.deepcharter.pod;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * What a pod is worth right now: the numbers that movement, drill, fuel and cargo read. {@link #of} starts from
 * {@link PodTuning} and passes the result through every {@link #MODIFY} listener, so a feature (a part, a pump, a
 * damaged hull) changes a stat from its own code and never edits pod internals. Read stats through {@code of(pod)},
 * once for each use, and never keep the result: it is a snapshot.
 *
 * <p>Each listener gets what the last one returned. The phases run in this order: {@link #BASE} (parts and
 * additions), Fabric's default phase, then {@link #CAP} (tier caps, which must see the final value). Inside one
 * phase the order is registration order. Register with {@code MODIFY.register(PodStats.BASE, listener)}. A listener must answer from
 * state it can read on whichever side it is called, since {@code of} may run on the client too, and must not call
 * {@code of} for the same pod. {@code of} runs for each pod on each tick, so a listener must be cheap.
 *
 * <p>Speeds are blocks per tick, powers and masses share the unit of {@code OreType} masses, fuel rates are litres
 * per second, damage is in hull points. A stat that is not a number, or is out of range, fails where it is set, so
 * the listener that wrote it is in the stack trace.
 *
 * @param maxHull                 the most hull the pod can have
 * @param horizontalSpeed         speed along the one axis the pilot drives
 * @param enginePower             rotor power; lift is this minus the cargo mass
 * @param thrustAcceleration      upward acceleration at full lift
 * @param maxClimbSpeed           the rotor cannot push the pod up faster than this
 * @param hardLandingDistance     a fall of this many blocks or fewer does no damage
 * @param hullDamagePerBlock      hull damage for each block fallen beyond that
 * @param ticksPerHardness        ticks the drill needs at the surface for each point of block hardness
 * @param crustHullDamage         hull lost for each crust slab bored
 * @param alignSpeed              blocks per tick the pod slides to centre itself in its bore
 * @param cargoSlots              how many ore the bay holds
 * @param tankLitres              the size of the tank; fuel is a percentage of it
 * @param idleLitresPerSecond     fuel burned standing still
 * @param movingLitresPerSecond   fuel burned driving or climbing
 * @param drillingLitresPerSecond fuel burned drilling
 */
public record PodStats(float maxHull, float horizontalSpeed, float enginePower, float thrustAcceleration,
		float maxClimbSpeed, float hardLandingDistance, float hullDamagePerBlock, float ticksPerHardness,
		float crustHullDamage, float alignSpeed, int cargoSlots, float tankLitres, float idleLitresPerSecond,
		float movingLitresPerSecond, float drillingLitresPerSecond) {

	/** The phase of parts and additions: it runs before the default phase and {@link #CAP}. */
	public static final Identifier BASE = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "base");
	/** The phase of caps, such as a tier limit: it runs last, so it limits what every other listener made. */
	public static final Identifier CAP = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "cap");

	/** Changes a pod's stats. A listener returns the stats it was given or a derived copy, never null. */
	public static final Event<Modifier> MODIFY = EventFactory.createArrayBacked(Modifier.class, listeners -> (pod, stats) -> {
		PodStats result = stats;
		for (Modifier listener : listeners) {
			result = listener.modify(pod, result);
			if (result == null) {
				throw new IllegalStateException("a pod stats listener returned null: " + listener);
			}
		}
		return result;
	});

	static {
		MODIFY.addPhaseOrdering(BASE, Event.DEFAULT_PHASE);
		MODIFY.addPhaseOrdering(Event.DEFAULT_PHASE, CAP);
	}

	public PodStats {
		requirePositive("maxHull", maxHull);
		requireNotNegative("horizontalSpeed", horizontalSpeed);
		requirePositive("enginePower", enginePower);
		requireNotNegative("thrustAcceleration", thrustAcceleration);
		requireNotNegative("maxClimbSpeed", maxClimbSpeed);
		requireNotNegative("hardLandingDistance", hardLandingDistance);
		requireNotNegative("hullDamagePerBlock", hullDamagePerBlock);
		requirePositive("ticksPerHardness", ticksPerHardness);
		requireNotNegative("crustHullDamage", crustHullDamage);
		requireNotNegative("alignSpeed", alignSpeed);
		if (cargoSlots < 0) {
			throw new IllegalArgumentException("pod stat cargoSlots must not be negative, got " + cargoSlots);
		}
		requirePositive("tankLitres", tankLitres);
		requireNotNegative("idleLitresPerSecond", idleLitresPerSecond);
		requireNotNegative("movingLitresPerSecond", movingLitresPerSecond);
		requireNotNegative("drillingLitresPerSecond", drillingLitresPerSecond);
	}

	/** The pod's stats: the tuning, as every {@link #MODIFY} listener changed it. */
	public static PodStats of(PodEntity pod) {
		return MODIFY.invoker().modify(pod, base());
	}

	/** The stats of a pod nothing modifies: {@link PodTuning} as it is. */
	public static PodStats base() {
		PodTuning tuning = PodTuning.DEFAULT;
		PodTuning.Movement movement = tuning.movement();
		PodTuning.Drill drill = tuning.drill();
		PodTuning.Fuel fuel = tuning.fuel();
		return new PodStats(tuning.shell().fullHull(), movement.horizontalSpeed(), movement.enginePower(),
				movement.thrustAcceleration(), movement.maxClimbSpeed(), movement.hardLandingDistance(),
				movement.hullDamagePerBlock(), drill.ticksPerHardness(), drill.crustHullDamage(), (float) drill.alignSpeed(),
				tuning.cargo().slots(), fuel.tankLitres(), fuel.idleLitresPerSecond(), fuel.movingLitresPerSecond(),
				fuel.drillingLitresPerSecond());
	}

	public PodStats withMaxHull(float maxHull) {
		return new PodStats(maxHull, horizontalSpeed, enginePower, thrustAcceleration, maxClimbSpeed, hardLandingDistance,
				hullDamagePerBlock, ticksPerHardness, crustHullDamage, alignSpeed, cargoSlots, tankLitres,
				idleLitresPerSecond, movingLitresPerSecond, drillingLitresPerSecond);
	}

	public PodStats withHorizontalSpeed(float horizontalSpeed) {
		return new PodStats(maxHull, horizontalSpeed, enginePower, thrustAcceleration, maxClimbSpeed, hardLandingDistance,
				hullDamagePerBlock, ticksPerHardness, crustHullDamage, alignSpeed, cargoSlots, tankLitres,
				idleLitresPerSecond, movingLitresPerSecond, drillingLitresPerSecond);
	}

	public PodStats withEnginePower(float enginePower) {
		return new PodStats(maxHull, horizontalSpeed, enginePower, thrustAcceleration, maxClimbSpeed, hardLandingDistance,
				hullDamagePerBlock, ticksPerHardness, crustHullDamage, alignSpeed, cargoSlots, tankLitres,
				idleLitresPerSecond, movingLitresPerSecond, drillingLitresPerSecond);
	}

	public PodStats withThrustAcceleration(float thrustAcceleration) {
		return new PodStats(maxHull, horizontalSpeed, enginePower, thrustAcceleration, maxClimbSpeed, hardLandingDistance,
				hullDamagePerBlock, ticksPerHardness, crustHullDamage, alignSpeed, cargoSlots, tankLitres,
				idleLitresPerSecond, movingLitresPerSecond, drillingLitresPerSecond);
	}

	public PodStats withMaxClimbSpeed(float maxClimbSpeed) {
		return new PodStats(maxHull, horizontalSpeed, enginePower, thrustAcceleration, maxClimbSpeed, hardLandingDistance,
				hullDamagePerBlock, ticksPerHardness, crustHullDamage, alignSpeed, cargoSlots, tankLitres,
				idleLitresPerSecond, movingLitresPerSecond, drillingLitresPerSecond);
	}

	public PodStats withHardLandingDistance(float hardLandingDistance) {
		return new PodStats(maxHull, horizontalSpeed, enginePower, thrustAcceleration, maxClimbSpeed, hardLandingDistance,
				hullDamagePerBlock, ticksPerHardness, crustHullDamage, alignSpeed, cargoSlots, tankLitres,
				idleLitresPerSecond, movingLitresPerSecond, drillingLitresPerSecond);
	}

	public PodStats withHullDamagePerBlock(float hullDamagePerBlock) {
		return new PodStats(maxHull, horizontalSpeed, enginePower, thrustAcceleration, maxClimbSpeed, hardLandingDistance,
				hullDamagePerBlock, ticksPerHardness, crustHullDamage, alignSpeed, cargoSlots, tankLitres,
				idleLitresPerSecond, movingLitresPerSecond, drillingLitresPerSecond);
	}

	public PodStats withTicksPerHardness(float ticksPerHardness) {
		return new PodStats(maxHull, horizontalSpeed, enginePower, thrustAcceleration, maxClimbSpeed, hardLandingDistance,
				hullDamagePerBlock, ticksPerHardness, crustHullDamage, alignSpeed, cargoSlots, tankLitres,
				idleLitresPerSecond, movingLitresPerSecond, drillingLitresPerSecond);
	}

	public PodStats withCrustHullDamage(float crustHullDamage) {
		return new PodStats(maxHull, horizontalSpeed, enginePower, thrustAcceleration, maxClimbSpeed, hardLandingDistance,
				hullDamagePerBlock, ticksPerHardness, crustHullDamage, alignSpeed, cargoSlots, tankLitres,
				idleLitresPerSecond, movingLitresPerSecond, drillingLitresPerSecond);
	}

	public PodStats withAlignSpeed(float alignSpeed) {
		return new PodStats(maxHull, horizontalSpeed, enginePower, thrustAcceleration, maxClimbSpeed, hardLandingDistance,
				hullDamagePerBlock, ticksPerHardness, crustHullDamage, alignSpeed, cargoSlots, tankLitres,
				idleLitresPerSecond, movingLitresPerSecond, drillingLitresPerSecond);
	}

	public PodStats withCargoSlots(int cargoSlots) {
		return new PodStats(maxHull, horizontalSpeed, enginePower, thrustAcceleration, maxClimbSpeed, hardLandingDistance,
				hullDamagePerBlock, ticksPerHardness, crustHullDamage, alignSpeed, cargoSlots, tankLitres,
				idleLitresPerSecond, movingLitresPerSecond, drillingLitresPerSecond);
	}

	public PodStats withTankLitres(float tankLitres) {
		return new PodStats(maxHull, horizontalSpeed, enginePower, thrustAcceleration, maxClimbSpeed, hardLandingDistance,
				hullDamagePerBlock, ticksPerHardness, crustHullDamage, alignSpeed, cargoSlots, tankLitres,
				idleLitresPerSecond, movingLitresPerSecond, drillingLitresPerSecond);
	}

	public PodStats withIdleLitresPerSecond(float idleLitresPerSecond) {
		return new PodStats(maxHull, horizontalSpeed, enginePower, thrustAcceleration, maxClimbSpeed, hardLandingDistance,
				hullDamagePerBlock, ticksPerHardness, crustHullDamage, alignSpeed, cargoSlots, tankLitres,
				idleLitresPerSecond, movingLitresPerSecond, drillingLitresPerSecond);
	}

	public PodStats withMovingLitresPerSecond(float movingLitresPerSecond) {
		return new PodStats(maxHull, horizontalSpeed, enginePower, thrustAcceleration, maxClimbSpeed, hardLandingDistance,
				hullDamagePerBlock, ticksPerHardness, crustHullDamage, alignSpeed, cargoSlots, tankLitres,
				idleLitresPerSecond, movingLitresPerSecond, drillingLitresPerSecond);
	}

	public PodStats withDrillingLitresPerSecond(float drillingLitresPerSecond) {
		return new PodStats(maxHull, horizontalSpeed, enginePower, thrustAcceleration, maxClimbSpeed, hardLandingDistance,
				hullDamagePerBlock, ticksPerHardness, crustHullDamage, alignSpeed, cargoSlots, tankLitres,
				idleLitresPerSecond, movingLitresPerSecond, drillingLitresPerSecond);
	}

	private static void requirePositive(String name, float value) {
		// NaN fails > 0, so this refuses it too.
		if (!(value > 0f) || Float.isInfinite(value)) {
			throw new IllegalArgumentException("pod stat " + name + " must be a positive number, got " + value);
		}
	}

	private static void requireNotNegative(String name, float value) {
		if (!(value >= 0f) || Float.isInfinite(value)) {
			throw new IllegalArgumentException("pod stat " + name + " must be a number, not negative, got " + value);
		}
	}

	@FunctionalInterface
	public interface Modifier {
		PodStats modify(PodEntity pod, PodStats stats);
	}
}
