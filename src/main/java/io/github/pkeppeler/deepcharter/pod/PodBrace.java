package io.github.pkeppeler.deepcharter.pod;

import java.util.Comparator;
import java.util.Optional;
import java.util.OptionalInt;

import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * The crust warning and the breach brace (#378). The crust at the foot of a layer takes {@link PodStats#crustHullDamage} from the hull for each
 * row the drill bores, and a pod that came down hurt by gas and lava often has less hull than the crust costs. The warning is for every pod: when the
 * hull that is left would not outlast the crust rows still to be bored, the pilot is told while there is still time to act. The brace is a part that
 * acts: near the crust, while the pod rests, it burns the cheapest ore in the bay to patch the hull until it outlasts the crust.
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
		/** A patch is wanted and the drill is working: the brace waits for the pilot to stop it. */
		WAITS_FOR_DRILL,
		/** A patch is wanted and the bay is empty: there is nothing to burn. */
		NO_ORE,
		/** The brace is burning ore into hull. */
		WORKING
	}

	private PodBrace() {
	}

	public static void init() {
		PodEvents.AFTER_TICK.register(PodBrace::afterTick);
	}

	/** The tier the pod's brace works at: 0 for none, a void part or an unreadable one. */
	public static int tier(PodEntity pod) {
		return PodComponents.effectiveTier(pod, ComponentTrack.BRACE);
	}

	/** The crust rows left and the slabs to them, and what they would cost the pod; empty where there is no crust to bore or the pod is out of reach of it. Works on both sides. */
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
		return ahead(pod).filter(crust -> pod.hull() > 0f && pod.hull() <= crust.cost())
				.map(crust -> new Warning(crust.slabsAway(), crust.crustSlabs(), crust.cost(), pod.hull()));
	}

	/** What the brace is doing for this pod now. Works on both sides, from synced state. */
	public static Patching patching(PodEntity pod) {
		if (tier(pod) == 0 || pod.hull() <= 0f) {
			return Patching.IDLE;
		}
		Optional<Ahead> crust = ahead(pod);
		if (crust.isEmpty() || pod.hull() >= crust.get().cost() + PodBraceTuning.DEFAULT.reserveHull()) {
			return Patching.IDLE;
		}
		if (pod.cargoUsed() == 0) {
			return Patching.NO_ORE;
		}
		return pod.drilling() ? Patching.WAITS_FOR_DRILL : Patching.WORKING;
	}

	private static void afterTick(PodEntity pod) {
		PodBraceTuning tuning = PodBraceTuning.DEFAULT;
		if (pod.tickCount % tuning.patchTicks() != 0 || !PodEvents.isPowered(pod) || patching(pod) != Patching.WORKING || !pod.cargo().isReadable()) {
			return;
		}
		Optional<OreType> cheapest = pod.cargo().entries().stream().map(entry -> OreRegistry.typeOf(entry.stack()).orElseThrow())
				.min(Comparator.comparingInt(OreType::value));
		if (cheapest.isEmpty() || pod.cargo().take(pod, cheapest.get(), 1) == 0) {
			return;
		}
		pod.setHull(Math.min(pod.maxHull(), pod.hull() + tuning.hullPerOre()));
		pod.level().playSound(null, pod.getX(), pod.getY(), pod.getZ(), DeepSound.REPAIR_NANOBOTS.event(), SoundSource.NEUTRAL);
	}
}
