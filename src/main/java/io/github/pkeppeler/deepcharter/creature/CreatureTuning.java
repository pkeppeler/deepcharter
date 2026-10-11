package io.github.pkeppeler.deepcharter.creature;

/**
 * Tunables for the creature feature, read as {@code CreatureTuning.DEFAULT.thing()}.
 *
 * @param walkSpeed          the figure's movement speed attribute; 0.22 is about 2 blocks a second
 * @param approachBlocks     a player on foot this close to the figure makes it fade
 * @param fadeBlockLight     block light at the figure's feet at which it fades: a pod's lights, a torch, lava
 * @param fadeTicks          ticks from the start of a fade to the figure's end
 * @param fadeCheckTicks     how often a figure looks for a light, a player or a dark pod
 * @param spawnIntervalTicks how often each rail level looks for a rail site that needs a figure
 * @param spawnRangeBlocks   a rail site spawns a figure when a player is within this many blocks of its centre
 * @param maxPerSite         figures on one rail site
 * @param maxPerLevel        figures in one level, however they came
 * @param respawnDelayTicks  ticks after a figure faded before the level spawns another
 * @param cling              what a figure does to a dark pod
 * @param natural            how figures appear in the open dark of the layers
 */
public record CreatureTuning(float walkSpeed, double approachBlocks, int fadeBlockLight, int fadeTicks, int fadeCheckTicks,
		int spawnIntervalTicks, double spawnRangeBlocks, int maxPerSite, int maxPerLevel, int respawnDelayTicks, Cling cling, Natural natural) {
	public static final CreatureTuning DEFAULT = new CreatureTuning(0.22f, 8, 3, 40, 5, 100, 96, 1, 3, 400, Cling.DEFAULT, Natural.DEFAULT);

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

	/**
	 * A pod with a pilot, no lights and power draws a figure; it grabs the hull and drains the tank until it has had its fill, the
	 * pilot shakes it off by moving the pod, or the pod is lit. It never takes the tank below the floor.
	 *
	 * @param senseBlocks  a dark pod this close draws a figure that is not clinging
	 * @param reachBlocks  a figure this close to the hull (the gap between the two boxes) grabs it
	 * @param latchSpeed   blocks per tick the figure closes the gap to its place on the hull
	 * @param drainLitres  litres a second that a clinging figure takes
	 * @param fillLitres   litres a figure takes in all before it lets go
	 * @param floorPercent fuel percent below which a figure takes nothing, and lets go
	 * @param shakeTicks   ticks of the pod moving (the rotor, or the treads) that shake a figure off; a tick standing still gives one back
	 */
	public record Cling(double senseBlocks, double reachBlocks, double latchSpeed, float drainLitres, float fillLitres, float floorPercent,
			int shakeTicks) {
		public static final Cling DEFAULT = new Cling(20, 2.5, 0.4, 0.25f, 2.5f, 5f, 30);

		public Cling {
			if (senseBlocks <= 0 || reachBlocks <= 0 || latchSpeed <= 0 || drainLitres <= 0 || fillLitres <= 0 || floorPercent < 0 || floorPercent >= 100
					|| shakeTicks < 1) {
				throw new IllegalArgumentException("cling tuning out of range: " + this);
			}
		}

		/** Ticks a figure that is never shaken off and finds fuel to take clings for. */
		public int fillTicks() {
			return Math.round(fillLitres / drainLitres * 20f);
		}
	}

	/**
	 * @param lampShare    the share of figures that carry a lamp
	 * @param chancePerTry the chance that a place the game tries for a natural spawn, and that passes every rule, gets a figure
	 * @param roomBlocks   air blocks above the floor that a figure needs to appear
	 */
	public record Natural(double lampShare, double chancePerTry, int roomBlocks) {
		public static final Natural DEFAULT = new Natural(0.35, 0.02, 3);

		public Natural {
			if (lampShare < 0 || lampShare > 1 || chancePerTry <= 0 || chancePerTry > 1 || roomBlocks < 1) {
				throw new IllegalArgumentException("natural spawn tuning out of range: " + this);
			}
		}
	}
}
