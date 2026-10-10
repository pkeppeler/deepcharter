package io.github.pkeppeler.deepcharter.pod;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.WeakHashMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * The crust warning and the breach brace (#378). The crust at the foot of a layer takes {@link PodStats#crustHullDamage} from the hull for each
 * row the drill bores, and a pod that came down hurt by gas and lava often has less hull than the crust costs. The warning is for every pod: when the
 * hull that is left would not outlast the crust rows still to be bored, the pilot is told while there is still time to act. The brace is a part that
 * acts: near the crust, while the pod rests, it burns an ore to patch the hull until it outlasts the crust. It never burns an ore that costs more a hull
 * than Hull Nanobots do ({@link #oreValueCap}), so the part is a discount on the nanobots and never a dearer way to the same hull.
 */
public final class PodBrace {
	/**
	 * A hull the crust would end.
	 *
	 * @param slabsAway  slabs between the pod's feet and the top of the crust, 0 once the pod is in it
	 * @param crustSlabs crust rows left to bore
	 * @param cost       hull the crust rows left would take from this pod
	 * @param hull       hull the pod has
	 */
	public record Warning(int slabsAway, int crustSlabs, float cost, float hull) {
	}

	/** What the brace is doing now. */
	public enum Patching {
		/** No brace, or a hull that needs no patch. */
		IDLE,
		/** A patch is wanted and the pod is not at rest: the pilot gives input, or the pod is in the air, or has been within the rest time. */
		WAITS_FOR_REST,
		/** A patch is wanted and the bay is empty: there is nothing to burn. */
		NO_ORE,
		/** A patch is wanted and every ore in the bay costs more a hull than Hull Nanobots do. */
		NO_CHEAP_ORE,
		/** The brace is burning ore into hull. */
		WORKING
	}

	/** What the HUD reads for a pod: its warning and what its brace does about it. */
	public record Status(Optional<Warning> warning, Patching patching) {
	}

	/** The ticks of the last time each pod was not at rest, server side. A pod with no entry has not moved since the brace first saw it. */
	private static final Map<PodEntity, Integer> LAST_ACTIVE = new WeakHashMap<>();

	private PodBrace() {
	}

	public static void init() {
		PodEvents.AFTER_TICK.register(PodBrace::afterTick);
	}

	/** The tier the pod's brace works at: 0 for none, a void part or an unreadable one. */
	public static int tier(PodEntity pod) {
		return PodComponents.effectiveTier(pod, ComponentTrack.BRACE);
	}

	/**
	 * The most an ore may be worth for the brace to burn it: what Hull Nanobots charge for the hull of one patch. So a patch never costs more a hull than the
	 * nanobots do; the cheapest ore is a deliberate discount.
	 */
	public static float oreValueCap() {
		return (float) Consumable.HULL_NANOBOTS.price() / RepairTuning.DEFAULT.nanobotHp() * PodBraceTuning.DEFAULT.hullPerOre();
	}

	/** The crust rows left and the slabs to them, and what they would cost the pod; empty where there is no crust to bore or the pod is out of reach of it. */
	private record Ahead(int slabsAway, int crustSlabs, float cost) {
	}

	private static Optional<Ahead> ahead(PodEntity pod) {
		Level level = pod.level();
		OptionalInt layer = LayerChain.layerOf(level.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier());
		if (layer.isEmpty() || layer.getAsInt() >= LayerChain.count(level.registryAccess())) {
			return Optional.empty();
		}
		int thickness = LayerTuning.DEFAULT.crustThickness();
		int aboveFloor = PodFootprint.of(pod).feetY() - level.getMinY();
		int crustSlabs = Math.min(thickness, aboveFloor);
		int slabsAway = Math.max(0, aboveFloor - thickness);
		if (crustSlabs < 1 || slabsAway > PodBraceTuning.DEFAULT.warnSlabs()) {
			return Optional.empty();
		}
		return Optional.of(new Ahead(slabsAway, crustSlabs, crustSlabs * PodStats.of(pod).crustHullDamage()));
	}

	/**
	 * The warning for this pod now, or empty: in a layer with a crust to bore, within {@link PodBraceTuning#warnSlabs} of the crust or in it, with a
	 * hull no greater than what the crust rows left would take (a hull of exactly that is gone with the last row). Works on both sides.
	 */
	public static Optional<Warning> warning(PodEntity pod) {
		return status(pod, true).warning();
	}

	/** What the brace is doing for this pod, if it is at rest. Works on both sides, from synced state. */
	public static Patching patching(PodEntity pod, boolean resting) {
		return status(pod, resting).patching();
	}

	/** The warning and the brace's state from one look at the crust ahead, for {@code resting} as the caller judges it. Works on both sides, from synced state. */
	public static Status status(PodEntity pod, boolean resting) {
		Optional<Ahead> crust = ahead(pod);
		Optional<Warning> warning = crust.filter(c -> pod.hull() > 0f && pod.hull() <= c.cost())
				.map(c -> new Warning(c.slabsAway(), c.crustSlabs(), c.cost(), pod.hull()));
		return new Status(warning, patching(pod, crust, resting));
	}

	private static Patching patching(PodEntity pod, Optional<Ahead> crust, boolean resting) {
		if (tier(pod) == 0 || pod.hull() <= 0f || pod.stranded()) {
			return Patching.IDLE;
		}
		if (crust.isEmpty() || pod.hull() >= crust.get().cost() + PodBraceTuning.DEFAULT.reserveHull()) {
			return Patching.IDLE;
		}
		if (pod.cargoUsed() == 0) {
			return Patching.NO_ORE;
		}
		if (!pod.braceOre()) {
			return Patching.NO_CHEAP_ORE;
		}
		return resting ? Patching.WORKING : Patching.WAITS_FOR_REST;
	}

	/** Whether the pilot gives no drill, drive or rotor input. A pod with no pilot has none. Server only. */
	public static boolean pilotIdle(PodEntity pod) {
		if (!(pod.getControllingPassenger() instanceof ServerPlayer pilot)) {
			return true;
		}
		return idle(pilot.getLastClientInput());
	}

	/** Whether {@code input} asks the pod for nothing: no sprint (the drill), no direction (the drive) and no jump (the rotor). */
	public static boolean idle(Input input) {
		return !(input.forward() || input.backward() || input.left() || input.right() || input.jump() || input.sprint());
	}

	/** Whether the bay holds an ore the brace may burn. */
	private static boolean hasCheapOre(PodEntity pod) {
		return pod.cargo().isReadable() && cheapest(pod).isPresent();
	}

	private static Optional<OreType> cheapest(PodEntity pod) {
		return pod.cargo().entries().stream().map(entry -> OreRegistry.typeOf(entry.stack()).orElseThrow())
				.min(Comparator.comparingInt(OreType::value)).filter(ore -> ore.value() <= oreValueCap());
	}

	private static void afterTick(PodEntity pod) {
		if (tier(pod) == 0) {
			LAST_ACTIVE.remove(pod);
			return;
		}
		PodBraceTuning tuning = PodBraceTuning.DEFAULT;
		boolean cheap = hasCheapOre(pod);
		if (pod.braceOre() != cheap) {
			pod.setBraceOre(cheap);
		}
		if (!pod.onGround() || !pilotIdle(pod)) {
			LAST_ACTIVE.put(pod, pod.tickCount);
			return;
		}
		boolean rested = pod.tickCount - LAST_ACTIVE.getOrDefault(pod, Integer.MIN_VALUE / 2) >= tuning.restTicks();
		if (!rested || pod.tickCount % tuning.patchTicks() != 0 || !PodEvents.isPowered(pod) || patching(pod, true) != Patching.WORKING) {
			return;
		}
		Optional<OreType> ore = cheapest(pod);
		if (ore.isEmpty() || pod.cargo().take(pod, ore.get(), 1) == 0) {
			return;
		}
		pod.setHull(Math.min(pod.maxHull(), pod.hull() + tuning.hullPerOre()));
		pod.level().playSound(null, pod.getX(), pod.getY(), pod.getZ(), DeepSound.REPAIR_NANOBOTS.event(), SoundSource.NEUTRAL);
	}
}
