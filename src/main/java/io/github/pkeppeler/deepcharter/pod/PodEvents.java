package io.github.pkeppeler.deepcharter.pod;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import net.minecraft.world.entity.Entity;

/**
 * The hooks other features use to change what a pod does, so that they never edit {@link PodEntity}.
 * With no listener registered, every hook leaves the pod's M1 behaviour as it was.
 *
 * <p>Every hook runs on the server only, except where a hook says otherwise. Predicates are
 * combined with AND: one listener that says no is enough ({@link #IGNORES_BLOCK_COLLISION} is the
 * one OR). Call the static methods ({@link #canMount}, {@link #isPowered}, {@link #extraMass},
 * {@link #ignoresBlockCollision}), not the events' invokers, so the built-in rules and the
 * validation stay in one place.
 *
 * <ul>
 *   <li>{@link #HULL_DEPLETED}: wrecks (#67)</li>
 *   <li>{@link #CAN_MOUNT}: ownership (#65), wrecks (#67)</li>
 *   <li>{@link #IS_POWERED}: wrecks (#67), lights (#75)</li>
 *   <li>{@link #EXTRA_MASS}: towing (#76)</li>
 *   <li>{@link #IGNORES_BLOCK_COLLISION}: towing (#76)</li>
 *   <li>{@link #AFTER_TICK}: lights (#75), towing (#76)</li>
 * </ul>
 *
 * <p>Pod stats (#60) and components (#65) are not events: {@code PodStats.of(pod)} is the seam for
 * them. Unload and crossing of a pod need no hook of ours: use Fabric's
 * {@code ServerEntityEvents.ENTITY_UNLOAD} and {@link io.github.pkeppeler.deepcharter.layer.BreachEvents#CROSSED}.
 */
public final class PodEvents {
	/** The pod's hull has just reached 0 from above. Not fired for a pod that loads with no hull: that is #67's to restore. */
	public static final Event<HullDepleted> HULL_DEPLETED = EventFactory.createArrayBacked(HullDepleted.class, listeners -> pod -> {
		for (HullDepleted listener : listeners) {
			listener.onHullDepleted(pod);
		}
	});

	/** Asked before an entity is added as a passenger, which includes every player's use of the pod. */
	public static final Event<CanMount> CAN_MOUNT = EventFactory.createArrayBacked(CanMount.class, listeners -> (pod, passenger) -> {
		for (CanMount listener : listeners) {
			if (!listener.canMount(pod, passenger)) {
				return false;
			}
		}
		return true;
	});

	/** Asked whenever the pod would use power: to move, drill or burn fuel. A pod without power ignores its pilot. */
	public static final Event<Powered> IS_POWERED = EventFactory.createArrayBacked(Powered.class, listeners -> pod -> {
		for (Powered listener : listeners) {
			if (!listener.isPowered(pod)) {
				return false;
			}
		}
		return true;
	});

	/** Mass added to the pod's cargo mass when its lift is worked out. Listeners' values are summed. */
	public static final Event<ExtraMass> EXTRA_MASS = EventFactory.createArrayBacked(ExtraMass.class, listeners -> pod -> {
		float total = 0f;
		for (ExtraMass listener : listeners) {
			total += listener.extraMass(pod);
		}
		return total;
	});

	/** Whether the pod passes through blocks (a towed pod, #76). Unlike the other predicates this is an OR: one yes is enough. */
	public static final Event<IgnoresBlockCollision> IGNORES_BLOCK_COLLISION = EventFactory.createArrayBacked(IgnoresBlockCollision.class, listeners -> pod -> {
		for (IgnoresBlockCollision listener : listeners) {
			if (listener.ignoresBlockCollision(pod)) {
				return true;
			}
		}
		return false;
	});

	/** Fired at the end of each server tick of the pod, after its own movement, drill and fuel. */
	public static final Event<AfterTick> AFTER_TICK = EventFactory.createArrayBacked(AfterTick.class, listeners -> pod -> {
		for (AfterTick listener : listeners) {
			listener.afterTick(pod);
		}
	});

	private PodEvents() {
	}

	/** True when no listener forbids {@code passenger} from boarding. */
	public static boolean canMount(PodEntity pod, Entity passenger) {
		return CAN_MOUNT.invoker().canMount(pod, passenger);
	}

	/** True when a listener says the pod passes through blocks. */
	public static boolean ignoresBlockCollision(PodEntity pod) {
		return IGNORES_BLOCK_COLLISION.invoker().ignoresBlockCollision(pod);
	}

	/** True unless the pod is stranded (the built-in reason) or a listener says it has no power. */
	public static boolean isPowered(PodEntity pod) {
		return !pod.stranded() && IS_POWERED.invoker().isPowered(pod);
	}

	/** The listeners' total extra mass. A negative or NaN total is a bug in a listener, so it fails loudly. */
	public static float extraMass(PodEntity pod) {
		float mass = EXTRA_MASS.invoker().extraMass(pod);
		// NaN fails >= 0, so this refuses it too. Deliberate: a listener bug must not become a silent no-lift pod.
		if (!(mass >= 0f)) {
			throw new IllegalStateException("pod extra mass must not be negative, got " + mass);
		}
		return mass;
	}

	@FunctionalInterface
	public interface HullDepleted {
		void onHullDepleted(PodEntity pod);
	}

	@FunctionalInterface
	public interface CanMount {
		/** Runs on the server only: the client's use of the pod is a guess the server then settles. */
		boolean canMount(PodEntity pod, Entity passenger);
	}

	@FunctionalInterface
	public interface Powered {
		/** May be asked on either side if a client feature calls {@link PodEvents#isPowered}; answer from synced state. */
		boolean isPowered(PodEntity pod);
	}

	@FunctionalInterface
	public interface ExtraMass {
		float extraMass(PodEntity pod);
	}

	@FunctionalInterface
	public interface IgnoresBlockCollision {
		boolean ignoresBlockCollision(PodEntity pod);
	}

	@FunctionalInterface
	public interface AfterTick {
		void afterTick(PodEntity pod);
	}
}
