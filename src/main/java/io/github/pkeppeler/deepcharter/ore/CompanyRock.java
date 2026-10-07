package io.github.pkeppeler.deepcharter.ore;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;

/**
 * Company rock cannot be broken by hand: its hardness of -1 stops a swing, and this is the backstop for a break that
 * does not come from a swing. Any block in {@code deepcharter:undiggable} is covered. Creative players are exempt, as
 * they are for the breach crust and deep rock. A pod drill has its own check in {@code PodDrill}.
 */
public final class CompanyRock {
	private CompanyRock() {
	}

	public static void init() {
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
				player.isCreative() || !state.is(HazardBlocks.UNDIGGABLE));
	}
}
