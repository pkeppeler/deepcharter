package io.github.pkeppeler.deepcharter.hangar;

import java.util.Map;
import java.util.Optional;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.Chassis;

/**
 * Tunables for the hangar feature, read as {@code HangarTuning.DEFAULT.thing()}. Money is in dollars. Only the refurbished
 * Mole's $500 and $250 for each pod are from the SPEC (section 7): the rest are invented.
 *
 * @param refurbishedMole   what a refurbished Mole costs before the registration fee
 * @param registrationFee   the fee for each pod the charter already has, added to the price of a refurbished Mole
 * @param restoreCosts      what the hangar charges to restore a wreck, by chassis id
 * @param catalyst          the ore that restoring a wreck uses up
 * @param wreckRadius       the hangar restores the nearest wreck this many blocks from the console, at most
 * @param bayRadius         a new Mole stands in a free place in the bay, this many blocks from the hangar anchor at most
 * @param slotSpacing       the places of the bay are this many blocks apart
 */
public record HangarTuning(long refurbishedMole, long registrationFee, Map<String, RestoreCost> restoreCosts, OreType catalyst,
		double wreckRadius, int bayRadius, int slotSpacing) {
	public static final HangarTuning DEFAULT = new HangarTuning(500, 250,
			Map.of("mole", new RestoreCost(400, 1, Optional.empty()),
					"prospector", new RestoreCost(5_000, 3, Optional.of(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "t17")))),
			OreType.CICATRIUM, 24, 6, 3);

	/** The dollars and the catalysts that restoring a wreck takes, and the transmission it fires for the charter, if it fires one. */
	public record RestoreCost(long money, int catalysts, Optional<Identifier> transmission) {
	}

	public HangarTuning {
		restoreCosts = Map.copyOf(restoreCosts);
	}

	/** The price of a refurbished Mole for a charter that has {@code pods} pods. */
	public long refurbishedPrice(int pods) {
		return refurbishedMole + registrationFee * pods;
	}

	/** What restoring a wreck of {@code chassis} costs. A chassis with no price is a bug, so it throws. */
	public RestoreCost restoreCost(Chassis chassis) {
		RestoreCost cost = restoreCosts.get(chassis.id());
		if (cost == null) {
			throw new IllegalArgumentException("no restore price for chassis " + chassis.id());
		}
		return cost;
	}
}
