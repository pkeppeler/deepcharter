package io.github.pkeppeler.deepcharter.handbook;

/**
 * Tunables for the handbook feature, read as {@code HandbookTuning.DEFAULT.thing()}.
 *
 * @param progressPollTicks    how often each online player's directive advancements are checked, in ticks. A vanilla trigger
 *                             completes a directive up to this long after it happens; {@code Directives.fire} does not wait
 * @param triggerPollTicks     how often the directives that watch the world are checked: a pod's pilot, and a terminal that was
 *                             repaired before the charter began
 * @param drillDownBlocks      how far below the colony's ground a pod must drill for the directive "Drill down ten blocks"
 * @param findProspectorBlocks how close a player must come to a Prospector wreck to have found it: a wreck site's bay reaches
 *                             six blocks from its middle
 */
public record HandbookTuning(int progressPollTicks, int triggerPollTicks, int drillDownBlocks, int findProspectorBlocks) {
	public static final HandbookTuning DEFAULT = new HandbookTuning(10, 10, 10, 10);

	public HandbookTuning {
		if (progressPollTicks < 1) {
			throw new IllegalArgumentException("progressPollTicks must be at least 1, got " + progressPollTicks);
		}
		if (triggerPollTicks < 1) {
			throw new IllegalArgumentException("triggerPollTicks must be at least 1, got " + triggerPollTicks);
		}
		if (drillDownBlocks < 1) {
			throw new IllegalArgumentException("drillDownBlocks must be at least 1, got " + drillDownBlocks);
		}
		if (findProspectorBlocks < 1) {
			throw new IllegalArgumentException("findProspectorBlocks must be at least 1, got " + findProspectorBlocks);
		}
	}
}
