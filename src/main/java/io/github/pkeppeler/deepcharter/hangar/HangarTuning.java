package io.github.pkeppeler.deepcharter.hangar;

import java.util.Map;
import java.util.Optional;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.Chassis;

/**
 * Tunables for the hangar feature, read as {@code HangarTuning.DEFAULT.thing()}. Money is in dollars, and every price is
 * invented; the SPEC (section 7) says only "a modest fee". Why each is what it is (EconomyAffordabilityTest has the income):
 * <ul>
 * <li>The refurbished Mole, $150 plus $75 for each pod: one or two early runs of a stock Mole for a charter's first extra pod,
 * and the fee still grows with every pod.
 * <li>A Mole wreck's restore, $100: cheaper than a refurbished Mole, as before, for the tow it takes.
 * <li>The Prospector's restore, $1,500: about five layer 2 runs of a Mole with tier 2 parts (the braked drive down the shaft takes its fuel first, #319), which is what is left to earn after
 * the onboarding's other buys, so it ends the onboarding as the SPEC intends. The three Cicatrium are the longer wait
 * in the ore (about 50 runs in the deepest zone), so the Company advances them, once per charter, against its contract.
 * </ul>
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
	public static final HangarTuning DEFAULT = new HangarTuning(150, 75,
			Map.of("mole", new RestoreCost(100, 1, 0, Optional.empty()),
					"prospector", new RestoreCost(1_500, 3, 3, Optional.of(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "t17")))),
			OreType.CICATRIUM, 24, 6, 3);

	/**
	 * The dollars and the catalysts that restoring a wreck takes, the catalysts that the Company advances to each charter against
	 * its contract for this restore (once per charter, spent only by a restore, never held in a pack, so it cannot be sold), and the
	 * transmission it fires for the charter, if it fires one. An advance below the catalysts would gate the restore on ore again.
	 */
	public record RestoreCost(long money, int catalysts, int advance, Optional<Identifier> transmission) {
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
