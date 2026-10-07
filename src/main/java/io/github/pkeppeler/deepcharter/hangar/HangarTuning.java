package io.github.pkeppeler.deepcharter.hangar;

import io.github.pkeppeler.deepcharter.ore.OreType;

/**
 * Tunables for the hangar feature, read as {@code HangarTuning.DEFAULT.thing()}. Money is in dollars. Only the refurbished
 * Mole's $500 and $250 for each pod are from the SPEC (section 7): the rest are invented.
 *
 * @param refurbishedMole   what a refurbished Mole costs before the registration fee
 * @param registrationFee   the fee for each pod the charter already has, added to the price of a refurbished Mole
 * @param restoreMoney      what the hangar charges to restore a wreck
 * @param catalyst          the ore that restoring a wreck uses up
 * @param restoreCatalysts  how many of the catalyst a restore uses up
 * @param wreckRadius       the hangar restores the nearest wreck this many blocks from the console, at most
 * @param bayRadius         a new Mole stands in a free place in the bay, this many blocks from the hangar anchor at most
 * @param slotSpacing       the places of the bay are this many blocks apart
 */
public record HangarTuning(long refurbishedMole, long registrationFee, long restoreMoney, OreType catalyst, int restoreCatalysts,
		double wreckRadius, int bayRadius, int slotSpacing) {
	public static final HangarTuning DEFAULT = new HangarTuning(500, 250, 400, OreType.CICATRIUM, 1, 24, 6, 3);

	/** The price of a refurbished Mole for a charter that has {@code pods} pods. */
	public long refurbishedPrice(int pods) {
		return refurbishedMole + registrationFee * pods;
	}
}
