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
import io.github.pkeppeler.deepcharter.pod.PodStats;
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

	private EarlyRunModel() {
	}

	/** The ore of a zone: the chance of each sellable ore for one stone block. The catalyst is kept for the hangar, so it is not income. */
	public record Zone(Map<OreType, Double> chances) {
		/** The zone's fill, read from the same JSON that the world generator reads. */
		public static Zone load(String name) {
			Map<OreType, Double> chances = new EnumMap<>(OreType.class);
			for (JsonElement entry : json("/data/deepcharter/worldgen/feature/fill_" + name + ".json").getAsJsonArray("ores")) {
				String block = entry.getAsJsonObject().get("block").getAsString();
				for (OreType type : OreType.values()) {
					if (type != OreType.CICATRIUM && type.blockId().toString().equals(block)) {
						chances.put(type, entry.getAsJsonObject().get("chance").getAsDouble());
					}
				}
			}
			return new Zone(chances);
		}

		double oreChance() {
			return chances.values().stream().mapToDouble(Double::doubleValue).sum();
		}

		double valuePerBlock() {
			return chances.entrySet().stream().mapToDouble(entry -> entry.getValue() * entry.getKey().value()).sum();
		}
	}

	/** One run: how deep it bores, the fuel it burns, the ore it brings and what that is worth, before and after fuel. */
	public record Run(int slabs, double litres, double ores, double gross, double net) {
		/** Runs to earn {@code price}, rounded up. */
		public int toAfford(long price) {
			return (int) Math.ceil(price / net);
		}
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

	/**
	 * A run in {@code zone} that starts {@code shaftBlocks} below the surface with a full tank. (A) The bore goes straight down; it
	 * climbs back at the rotor's top climb speed; fuel is bought at the surface at {@link FuelTuning}'s price.
	 */
	public static Run run(Zone zone, PodStats stats, int shaftBlocks) {
		int width = (int) Math.ceil(Chassis.MOLE.width());
		int cells = width * width;
		double oreChance = zone.oreChance();
		// A slab with ore in it takes as long as its hardest block; the zone fill replaces vanilla stone.
		float stoneHardness = Blocks.STONE.defaultDestroyTime();
		float oreHardness = zone.chances().keySet().stream().map(type -> OreRegistry.block(type).defaultDestroyTime()).max(Float::compare).orElseThrow();
		double slabHasOre = 1 - Math.pow(1 - oreChance, cells);
		double climbSecondsPerBlock = 1 / (stats.maxClimbSpeed() * 20);
		int slabs = 0;
		double litres = 0;
		for (int next = 1;; next++) {
			int depthFeet = (int) ((shaftBlocks + next / 2.0) * LayerTuning.DEFAULT.feetPerBlock());
			double stoneTicks = PodDrill.drillTicks(stats, stoneHardness, depthFeet);
			double oreTicks = PodDrill.drillTicks(stats, oreHardness, depthFeet);
			double drillSeconds = (stoneTicks * (1 - slabHasOre) + oreTicks * slabHasOre) / 20;
			double climbSeconds = (shaftBlocks + next) * climbSecondsPerBlock;
			double used = drillSeconds * next * stats.drillingLitresPerSecond() + climbSeconds * stats.movingLitresPerSecond()
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
		return new Run(slabs, litres, ores, gross, gross - litres * FuelTuning.DEFAULT.pricePerLitre());
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
