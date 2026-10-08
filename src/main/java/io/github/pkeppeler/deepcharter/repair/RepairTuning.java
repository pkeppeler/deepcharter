package io.github.pkeppeler.deepcharter.repair;

/**
 * Tunables for the repair feature, read as {@code RepairTuning.DEFAULT.thing()}. Item prices are the original's and live in
 * {@link Consumable}.
 *
 * @param repairCostPerHp   dollars for each hull point the station repairs. The original's $15 was for a 10 point hull; ours is
 *                          100 points, and $1 makes a full repair of the stock hull cost less than an early run's $115
 * @param itemCooldownTicks ticks before the same item can be used again (the original's half second)
 * @param reserveLitres     litres the Reserve Fuel Tank adds (the original's 25)
 * @param nanobotHp         hull points the Hull Repair Nanobots add (the original's 30)
 * @param dynamiteRadius    the dynamite clears blocks within this many of the pod on each axis, so 1 is 3 x 3 x 3
 * @param plasticRadius     the same for the plastic explosives, so 2 is 5 x 5 x 5
 * @param quantumScatter    blocks, at most, that the Quantum Teleporter lands from the destination ("results may vary")
 */
public record RepairTuning(long repairCostPerHp, int itemCooldownTicks, float reserveLitres, float nanobotHp,
		int dynamiteRadius, int plasticRadius, double quantumScatter) {
	public static final RepairTuning DEFAULT = new RepairTuning(1L, 10, 25f, 30f, 1, 2, 8.0);
}
