package io.github.pkeppeler.deepcharter.creature;

/**
 * Tunables for the creature feature, read as {@code CreatureTuning.DEFAULT.thing()}.
 *
 * @param walkSpeed          the figure's movement speed attribute; a player walks at 0.1
 * @param approachBlocks     a player this close to the figure makes it fade
 * @param fadeBlockLight     block light at the figure's feet at which it fades: a pod's lights, a torch, lava
 * @param fadeTicks          ticks from the start of a fade to the figure's end
 * @param fadeCheckTicks     how often a figure looks for a light or a player
 * @param spawnIntervalTicks how often each rail level looks for a rail site that needs a figure
 * @param spawnRangeBlocks   a rail site spawns a figure when a player is within this many blocks of its centre
 * @param maxPerSite         figures on one rail site
 * @param maxPerLevel        figures in one level
 * @param respawnDelayTicks  ticks after a figure faded before the level spawns another
 */
public record CreatureTuning(float walkSpeed, double approachBlocks, int fadeBlockLight, int fadeTicks, int fadeCheckTicks,
		int spawnIntervalTicks, double spawnRangeBlocks, int maxPerSite, int maxPerLevel, int respawnDelayTicks) {
	public static final CreatureTuning DEFAULT = new CreatureTuning(0.1f, 8, 3, 40, 5, 100, 96, 1, 3, 400);

	public CreatureTuning {
		if (walkSpeed <= 0 || approachBlocks <= 0 || fadeBlockLight < 1 || fadeBlockLight > 15 || fadeTicks < 1 || fadeCheckTicks < 1
				|| spawnIntervalTicks < 1 || spawnRangeBlocks <= 0 || maxPerSite < 1 || maxPerLevel < maxPerSite || respawnDelayTicks < 0) {
			throw new IllegalArgumentException("creature tuning out of range: " + this);
		}
	}

	/** How far from every player a figure may appear: twice the distance at which it would fade. */
	public double spawnClearance() {
		return 2 * approachBlocks;
	}
}
