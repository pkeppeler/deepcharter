package io.github.pkeppeler.deepcharter.test.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodDrill;
import io.github.pkeppeler.deepcharter.pod.PodLinerTuning;
import io.github.pkeppeler.deepcharter.pod.PodLiningTuning;
import io.github.pkeppeler.deepcharter.pod.PodSounderTuning;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;

/**
 * What a run of a Mole earns, worked out from the data the game runs on (issue 201): the ore chances of a zone, the ore values,
 * the pod's stats and the fuel price. It counts the expected ore of a bore, not a sampled one, so it never flakes.
 *
 * <p>A run is a vertical bore of {@code n} slabs from the bottom of an old shaft, and the climb back up. {@code n} is the most
 * slabs that the tank pays for. Its assumptions are the ones marked (A) below.
 */
public final class EarlyRunModel {
	/** (A) The share of the expected ore that a player really gets: bore steps that miss a vein, a company rock to go round, a dead end. */
	public static final double YIELD = 0.7;

	/** (A) Blocks per tick that a pilot braking down a shaft holds the pod at: under the 0.7 landing speed, with room for the pilot's overshoot. */
	public static final double DRIVE_DOWN_SINK = 0.6;
	private static final int TICKS_PER_SECOND = 20;

	private EarlyRunModel() {
	}

	/**
	 * The ore of a zone: the chance of each sellable ore for one stone block, and apart from them the chance of the catalyst
	 * (Cicatrium). The catalyst is kept for the hangar, so it is not counted as income.
	 */
	public record Zone(Map<OreType, Double> chances, double catalystChance) {
		/** The zone's fill, read from the same JSON that the world generator reads. */
		public static Zone load(String name) {
			Map<OreType, Double> chances = new EnumMap<>(OreType.class);
			double catalystChance = 0;
			for (JsonElement entry : json("/data/deepcharter/worldgen/feature/fill_" + name + ".json").getAsJsonArray("ores")) {
				String block = entry.getAsJsonObject().get("block").getAsString();
				double chance = entry.getAsJsonObject().get("chance").getAsDouble();
				for (OreType type : OreType.values()) {
					if (type.blockId().toString().equals(block)) {
						if (type == OreType.CICATRIUM) {
							catalystChance = chance;
						} else {
							chances.put(type, chance);
						}
					}
				}
			}
			return new Zone(chances, catalystChance);
		}

		double oreChance() {
			return chances.values().stream().mapToDouble(Double::doubleValue).sum();
		}

		double valuePerBlock() {
			return chances.entrySet().stream().mapToDouble(entry -> entry.getValue() * entry.getKey().value()).sum();
		}
	}

	/**
	 * One run: how deep it bores, the fuel it burns, the ore it brings and what that is worth, before and after fuel, and the
	 * Cicatrium it brings (in expectation, so a fraction).
	 */
	public record Run(int slabs, double litres, double ores, double gross, double net, double catalysts) {
		/** Runs to earn {@code price}, rounded up. */
		public int toAfford(long price) {
			return (int) Math.ceil(price / net);
		}
	}

	/**
	 * The litres to bore {@code blocks} slabs straight down from the surface in one go, with no climb back: the cost of a first
	 * descent that cannot refuel at the pump. The way back down an open shaft is a braked drive instead (see {@link #driveDownLitres}).
	 */
	public static double boreLitres(Zone zone, PodStats stats, int blocks) {
		double litres = 0;
		for (int slab = 1; slab <= blocks; slab++) {
			litres += slabSeconds(zone, stats, slab - 0.5) * (stats.drillingLitresPerSecond() + stats.idleLitresPerSecond());
		}
		return litres;
	}

	/**
	 * The litres to drive down {@code blocks} of open shaft with the rotor braking (#319). (A) The pilot holds the sink at
	 * {@link #DRIVE_DOWN_SINK}, under the hard landing speed. Holding a speed takes the rotor for the share of ticks in which gravity
	 * is paid back ({@code gravity / thrustAcceleration}); those ticks burn the moving rate and the others the idle rate.
	 */
	public static double driveDownLitres(PodStats stats, int blocks) {
		return driveDownLitres(stats, blocks, 0f);
	}

	/**
	 * As {@link #driveDownLitres(PodStats, int)} for a pod that carries {@code extraMass} (a spoil hopper's bay and a brick rack, see
	 * {@link #hopperMass}). Mass cuts lift, and lift scales the rotor's thrust, so holding the sink takes the rotor for a larger share of the ticks.
	 */
	public static double driveDownLitres(PodStats stats, int blocks, float extraMass) {
		double seconds = blocks / (DRIVE_DOWN_SINK * TICKS_PER_SECOND);
		double thrustShare = PodTuning.DEFAULT.movement().gravity() / (stats.thrustAcceleration() * liftShare(stats, extraMass));
		return seconds * (thrustShare * stats.movingLitresPerSecond() + (1 - thrustShare) * stats.idleLitresPerSecond());
	}

	/** (A) The mass of a full spoil bay and a full brick rack: what a pod with a hopper adds to the cargo mass at worst. */
	public static float hopperMass() {
		PodLiningTuning tuning = PodLiningTuning.DEFAULT;
		return tuning.spoilCapacity() * tuning.spoilMass() + tuning.brickCapacity() * tuning.brickMass();
	}

	/** The share of the engine's power that is lift once {@code extraMass} is aboard (ore is not counted: a run that climbs home climbs light). */
	public static double liftShare(PodStats stats, float extraMass) {
		return Math.max(0, stats.enginePower() - extraMass) / stats.enginePower();
	}

	/**
	 * Blocks per tick the rotor climbs at, as {@code PodMovement} works it out: each tick adds the thrust and takes gravity, and drag keeps a
	 * share, so the speed settles where the two balance, and the rotor's own cap ({@code maxClimbSpeed}) limits it.
	 */
	public static double climbSpeed(PodStats stats, float extraMass) {
		PodTuning.Movement movement = PodTuning.DEFAULT.movement();
		double thrust = stats.thrustAcceleration() * liftShare(stats, extraMass);
		double settled = (thrust - movement.gravity()) * movement.verticalDrag() / (1 - movement.verticalDrag());
		return Math.max(0, Math.min(stats.maxClimbSpeed(), settled));
	}

	/** Blocks of layer 1, which a run in layer 2 climbs through twice (the shaft is already bored). */
	public static int layerOneBlocks() {
		return json("/data/deepcharter/dimension/layer_1.json").getAsJsonObject("generator").getAsJsonObject("biome_source")
				.get("height").getAsInt();
	}

	/** The stats of a Mole with the given part tiers (0 for stock), as PodComponents would scale them. */
	public static PodStats mole(int drillTier, int tankTier, int bayTier) {
		UpgradeTuning parts = UpgradeTuning.DEFAULT;
		PodStats stock = PodStats.base();
		return stock
				.withTicksPerHardness(stock.ticksPerHardness() / parts.ratio(ComponentTrack.DRILL, drillTier))
				.withTankLitres(stock.tankLitres() * parts.ratio(ComponentTrack.FUEL_TANK, tankTier))
				.withCargoSlots(Math.round(stock.cargoSlots() * parts.ratio(ComponentTrack.CARGO_BAY, bayTier)));
	}

	/** The stats of a pod with a liner of {@code tier} (1 or more) fitted: its drill is slower by the tier's share (#339). */
	public static PodStats withLiner(PodStats stats, int tier) {
		return stats.withTicksPerHardness(PodLinerTuning.DEFAULT.tier(tier).slowedDrill(stats.ticksPerHardness()));
	}

	/** The stats of a pod with a seep sounder of {@code tier} (1 or more) fitted: its drill is slower by the tier's share (#373). */
	public static PodStats withSounder(PodStats stats, int tier) {
		return stats.withTicksPerHardness(PodSounderTuning.DEFAULT.tier(tier).slowedDrill(stats.ticksPerHardness()));
	}

	/**
	 * A run in {@code zone} below {@code shaftBlocks} of open shaft, from the surface with a full tank. (A) The pod drives down the shaft
	 * braked ({@link #driveDownLitres}), the bore goes straight down from its bottom; it
	 * climbs back at the rotor's top climb speed; fuel is bought at the surface at {@link FuelTuning}'s price.
	 */
	public static Run run(Zone zone, PodStats stats, int shaftBlocks) {
		return run(zone, stats, shaftBlocks, 0f);
	}

	/** As {@link #run(Zone, PodStats, int)} for a pod that carries {@code extraMass}: it slows the climb if the lift falls far enough, and costs rotor fuel on the way down. */
	public static Run run(Zone zone, PodStats stats, int shaftBlocks, float extraMass) {
		int cells = cellsPerSlab();
		double oreChance = zone.oreChance();
		double climbSecondsPerBlock = 1 / (climbSpeed(stats, extraMass) * 20);
		int slabs = 0;
		double litres = 0;
		for (int next = 1;; next++) {
			double drillSeconds = slabSeconds(zone, stats, shaftBlocks + next / 2.0);
			double climbSeconds = (shaftBlocks + next) * climbSecondsPerBlock;
			double used = driveDownLitres(stats, shaftBlocks, extraMass) + drillSeconds * next * stats.drillingLitresPerSecond() + climbSeconds * stats.movingLitresPerSecond()
					+ (drillSeconds * next + climbSeconds) * stats.idleLitresPerSecond();
			if (used > stats.tankLitres()) {
				break;
			}
			slabs = next;
			litres = used;
		}
		double ores = slabs * cells * oreChance * YIELD;
		double averageOre = zone.valuePerBlock() / oreChance;
		double gross = averageOre * Math.min(ores, stats.cargoSlots());
		double catalysts = slabs * cells * zone.catalystChance() * YIELD;
		return new Run(slabs, litres, ores, gross, gross - litres * FuelTuning.DEFAULT.pricePerLitre(), catalysts);
	}

	private static int cellsPerSlab() {
		int width = (int) Math.ceil(Chassis.MOLE.width());
		return width * width;
	}

	/** Seconds to drill one slab at {@code depthBlocks} below the surface. A slab with ore in it takes as long as its hardest block; the zone fill replaces vanilla stone. */
	private static double slabSeconds(Zone zone, PodStats stats, double depthBlocks) {
		float stoneHardness = Blocks.STONE.defaultDestroyTime();
		float oreHardness = zone.chances().keySet().stream().map(type -> OreRegistry.block(type).defaultDestroyTime()).max(Float::compare).orElseThrow();
		double slabHasOre = 1 - Math.pow(1 - zone.oreChance(), cellsPerSlab());
		int depthFeet = (int) (depthBlocks * LayerTuning.DEFAULT.feetPerBlock());
		double stoneTicks = PodDrill.drillTicks(stats, stoneHardness, depthFeet);
		double oreTicks = PodDrill.drillTicks(stats, oreHardness, depthFeet);
		return (stoneTicks * (1 - slabHasOre) + oreTicks * slabHasOre) / 20;
	}

	private static JsonObject json(String resource) {
		InputStream stream = EarlyRunModel.class.getResourceAsStream(resource);
		if (stream == null) {
			throw new IllegalStateException("no resource " + resource);
		}
		try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(reader).getAsJsonObject();
		} catch (IOException e) {
			throw new UncheckedIOException(resource, e);
		}
	}
}
